/*
 * Copyright 2019 IIT Software GmbH
 *
 * IIT Software GmbH licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.swiftmq.jms.v750;

import com.swiftmq.client.thread.PoolManager;
import com.swiftmq.jms.*;
import com.swiftmq.jms.smqp.v750.*;
import com.swiftmq.ms.MessageSelector;
import com.swiftmq.swiftlet.queue.MessageEntry;
import com.swiftmq.swiftlet.queue.MessageIndex;
import com.swiftmq.swiftlet.threadpool.AsyncTask;
import com.swiftmq.swiftlet.threadpool.ThreadPool;
import com.swiftmq.tools.collection.RingBuffer;
import com.swiftmq.tools.concurrent.Semaphore;
import com.swiftmq.tools.queue.SingleProcessorQueue;
import com.swiftmq.tools.requestreply.*;
import com.swiftmq.util.SwiftUtilities;
import jakarta.jms.IllegalStateException;
import jakarta.jms.Queue;
import jakarta.jms.*;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class SessionImpl
        implements Session, RequestService, QueueSession, TopicSession, SwiftMQSession, SessionExtended, Recreatable, RequestRetryValidator {
    public static final String DISPATCH_TOKEN = "sys$jms.client.session.sessiontask";

    static final int TYPE_SESSION = 0;
    static final int TYPE_QUEUE_SESSION = 1;
    static final int TYPE_TOPIC_SESSION = 2;
    static final boolean DEBUG = Boolean.valueOf(System.getProperty("swiftmq.reconnect.debug", "false")).booleanValue();
    public ConnectionImpl myConnection = null;
    volatile boolean ignoreClose = false;
    volatile boolean closed = false;
    boolean transacted = false;
    int acknowledgeMode = 0;
    volatile int dispatchId = 0;
    volatile int myDispatchId = 0;
    String clientId = null;
    RequestRegistry requestRegistry = null;
    String myHostname = null;
    String userName = null;
    ExceptionListener exceptionListener = null;
    Map consumerMap = new HashMap();
    List producers = new ArrayList();
    List browsers = new ArrayList();
    int lastConsumerId = -1;
    ArrayList transactedRequestList = new ArrayList();
    Set rollbackIdLog = new HashSet();
    Set currentTxLog = new HashSet();
    MessageListener messageListener = null;
    RingBuffer messageChunk = new RingBuffer(32);
    volatile boolean shadowConsumerCreated = false;
    MessageEntry lastMessage = null;
    boolean autoAssign = true;
    ThreadPool sessionPool = null;
    SessionDeliveryQueue sessionQueue = null;
    SessionTask sessionTask = null;
    volatile int recoveryEpoche = 0;
    volatile boolean recoveryInProgress = false;
    int type = TYPE_SESSION;
    boolean useThreadContextCL = false;
    volatile boolean resetInProgress = false;
    ConnectionConsumerImpl connectionConsumer = null;
    volatile String shadowConsumerQueueName = null;
    List delayedClosedProducers = new ArrayList();
    volatile boolean withinOnMessage = false;
    MessageImpl onMessageMessage = null;
    MessageConsumerImpl onMessageConsumer = null;
    volatile boolean isRunning = false;
    boolean xaMode = false;
    volatile int minConnectionId = Integer.MAX_VALUE;
    volatile boolean txCancelled = false;
    volatile Semaphore blockSem = null;
    volatile boolean consumerDirty = false;
    ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    protected SessionImpl(int type, ConnectionImpl myConnection, boolean transacted, int acknowledgeMode, int dispatchId, RequestRegistry requestRegistry, String myHostname, String clientId) {
        this.type = type;
        this.myConnection = myConnection;
        this.transacted = transacted;
        this.acknowledgeMode = acknowledgeMode;
        this.dispatchId = dispatchId;
        this.requestRegistry = requestRegistry;
        this.myHostname = myHostname;
        this.clientId = clientId;
        this.sessionPool = PoolManager.getInstance().getSessionPool();
        useThreadContextCL = myConnection.isUseThreadContextCL();
        sessionTask = new SessionTask();
        sessionQueue = new SessionDeliveryQueue();
    }

    static String buildId(String uniqueConsumerId, MessageImpl msg) {
        String jmsMsgId = null;
        try {
            jmsMsgId = msg.getJMSMessageID();
        } catch (JMSException e) {
        }
        if (jmsMsgId == null)
            return null;
        StringBuffer id = new StringBuffer();
        id.append(uniqueConsumerId);
        id.append('-');
        id.append(jmsMsgId);
        return id.toString();
    }

    public void setBlocked(boolean blocked) {
        if (blocked)
            blockSem = new Semaphore();
        else {
            if (blockSem != null)
                blockSem.notifyAllWaiters();
        }
    }

    Reply requestBlockable(Request request) throws Exception {
        if (blockSem != null)
            blockSem.waitHere();
        return requestRegistry.request(request);
    }

    public void setRunning(boolean running) {
        lock.writeLock().lock();
        try {
            isRunning = running;
            if (!running)
                clearMessageChunks();
        } finally {
            lock.writeLock().unlock();
        }

    }

    public void setXaMode(boolean xaMode) {
        lock.writeLock().lock();
        try {
            this.xaMode = xaMode;
        } finally {
            lock.writeLock().unlock();
        }

    }

    public int getRecoveryEpoche() {
        return recoveryEpoche;
    }

    public Request getRecreateRequest() {
        Request request = null;
        switch (type) {
            case TYPE_SESSION:
                request = new CreateSessionRequest(0, transacted, acknowledgeMode, CreateSessionRequest.UNIFIED, recoveryEpoche);
                break;
            case TYPE_QUEUE_SESSION:
                request = new CreateSessionRequest(0, transacted, acknowledgeMode, CreateSessionRequest.QUEUE_SESSION, recoveryEpoche);
                break;
            case TYPE_TOPIC_SESSION:
                request = new CreateSessionRequest(0, transacted, acknowledgeMode, CreateSessionRequest.TOPIC_SESSION, recoveryEpoche);
                break;
        }
        return request;
    }

    public void setRecreateReply(Reply reply) {
        CreateSessionReply r = (CreateSessionReply) reply;
        dispatchId = r.getSessionDispatchId();
    }

    public List getRecreatables() {
        List list = new ArrayList();
        for (Iterator iter = consumerMap.entrySet().iterator(); iter.hasNext(); ) {
            list.add(((Map.Entry) iter.next()).getValue());
        }
        for (int i = 0; i < producers.size(); i++) {
            list.add(producers.get(i));
        }
        for (int i = 0; i < browsers.size(); i++) {
            list.add(browsers.get(i));
        }
        if (shadowConsumerCreated)
            list.add(new ShadowConsumerRecreator());
        return list;
    }

    public void validate(Request request) throws ValidationException {
        request.setDispatchId(dispatchId);
        if (request instanceof AcknowledgeMessageRequest ||
                request instanceof AssociateMessageRequest ||
                request instanceof DeleteMessageRequest ||
                request instanceof RecoverSessionRequest ||
                request instanceof RollbackRequest) {
            request.setCancelledByValidator(true);
        }
    }

    public boolean isTxCancelled() {
        return txCancelled;
    }

    public void setTxCancelled(boolean txCancelled) {
        this.txCancelled = txCancelled;
    }

    public void setResetInProgress(boolean resetInProgress) {
        lock.writeLock().lock();
        try {
            this.resetInProgress = resetInProgress;
            if (DEBUG) System.out.println(new Date() + " " + toString() + ", setResetInProgress=" + resetInProgress);
            if (resetInProgress) {
                sessionQueue.stopQueue();
                sessionQueue.clear();
                sessionQueue.setCurrentCallInvalid(true);
                for (Iterator iter = consumerMap.entrySet().iterator(); iter.hasNext(); ) {
                    MessageConsumerImpl c = (MessageConsumerImpl) ((Map.Entry) iter.next()).getValue();
                    c.clearCache();
                }
                clearMessageChunks();
            } else {
                if (DEBUG)
                    System.out.println(new Date() + " " + toString() + ", setResetInProgress, c.size=" + consumerMap + ", recoveryInProgress=" + recoveryInProgress);
                sessionQueue.clear();
                for (Object o : consumerMap.entrySet()) {
                    Map.Entry entry = (Map.Entry) o;
                    MessageConsumerImpl c = (MessageConsumerImpl) entry.getValue();
                    if (DEBUG)
                        System.out.println(new Date() + " " + toString() + ", setResetInProgress, c=" + c + ", recoveryInProgress=" + recoveryInProgress + ", started=" + c.isConsumerStarted() +
                                ", fillCachePending=" + c.fillCachePending + ", closed=" + c.isClosed());
                    if (!recoveryInProgress && c.isConsumerStarted()) {
                        if (DEBUG)
                            System.out.println(new Date() + " " + toString() + ", setResetInProgress, c=" + c + ", call fill cache");
                        c.fillCache(true);
                    }
                }
            }
        } finally {
            lock.writeLock().unlock();
        }

    }

    public void setConnectionConsumer(ConnectionConsumerImpl connectionConsumer) {
        this.connectionConsumer = connectionConsumer;
    }

    void startSession() {
        if (messageListener == null) {
            sessionQueue.startQueue();
            sessionQueue.triggerInvocation();
        }
    }

    void stopSession() {
        sessionQueue.stopQueue();
    }

    boolean isSessionStarted() {
        return sessionQueue.isStarted();
    }

    String getUserName() {
        return userName;
    }

    void setUserName(String userName) {
        this.userName = userName;
    }

    public ConnectionImpl getMyConnection() {
        return myConnection;
    }

    void verifyState() throws JMSException {
        if (closed) {
            throw new jakarta.jms.IllegalStateException("Session is closed");
        }
    }

    public boolean isIgnoreClose() {
        return ignoreClose;
    }

    public void setIgnoreClose(boolean ignoreClose) {
        this.ignoreClose = ignoreClose;
    }

    public void storeTransactedMessage(MessageProducerImpl producer, MessageImpl msg) {
        lock.writeLock().lock();
        try {
            minConnectionId = Math.min(minConnectionId, myConnection.getConnectionId());
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(bos);
            try {
                msg.writeContent(dos);
            } catch (Exception e) {
                e.printStackTrace();
            }
            transactedRequestList.add(new Object[]{producer, bos.toByteArray()});
        } finally {
            lock.writeLock().unlock();
        }

    }

    public Reply requestTransaction(CommitRequest req) {
        lock.writeLock().lock();
        try {
            req.setMessages((List) transactedRequestList.clone());
            transactedRequestList.clear();
        } finally {
            lock.writeLock().unlock();
        }

        return requestRegistry.request(req);
    }

    public int getMinConnectionId() {
        return minConnectionId;
    }

    public List getAndClearCurrentTransaction() {
        lock.writeLock().lock();
        try {
            List clone = null;
            minConnectionId = Integer.MAX_VALUE;
            clone = (List) transactedRequestList.clone();
            transactedRequestList.clear();
            return clone;
        } finally {
            lock.writeLock().unlock();
        }

    }

    public void dropTransaction() {
        lock.writeLock().lock();
        try {
            transactedRequestList.clear();
        } finally {
            lock.writeLock().unlock();
        }

    }

    void setExceptionListener(ExceptionListener exceptionListener) {
        this.exceptionListener = exceptionListener;
    }

    int getMyDispatchId() {
        return myDispatchId;
    }

    void setMyDispatchId(int myDispatchId) {
        this.myDispatchId = myDispatchId;
    }

    void addMessageConsumerImpl(MessageConsumerImpl consumer) {
        lock.writeLock().lock();
        try {
            if (lastConsumerId == Integer.MAX_VALUE)
                lastConsumerId = -1;
            lastConsumerId++;
            consumerMap.put(new Integer(lastConsumerId), consumer);
            consumerDirty = true;
            consumer.setConsumerId(lastConsumerId);
            myConnection.increaseDuplicateLogSize(myConnection.getSmqpConsumerCacheSize());
        } finally {
            lock.writeLock().unlock();
        }

    }

    void removeMessageConsumerImpl(MessageConsumerImpl consumer) {
        lock.writeLock().lock();
        try {
            consumerMap.remove(new Integer(consumer.getConsumerId()));
            myConnection.decreaseDuplicateLogSize(myConnection.getSmqpConsumerCacheSize());
            consumerDirty = true;
        } finally {
            lock.writeLock().unlock();
        }

    }

    void addMessageProducerImpl(MessageProducerImpl producer) {
        lock.writeLock().lock();
        try {
            producers.add(producer);
        } finally {
            lock.writeLock().unlock();
        }

    }

    void removeMessageProducerImpl(MessageProducerImpl producer) {
        lock.writeLock().lock();
        try {
            producers.remove(producer);
        } finally {
            lock.writeLock().unlock();
        }

    }

    void addQueueBrowserImpl(QueueBrowserImpl browser) {
        lock.writeLock().lock();
        try {
            browsers.add(browser);
        } finally {
            lock.writeLock().unlock();
        }

    }

    // --> JMS 1.1

    void removeQueueBrowserImpl(QueueBrowserImpl browser) {
        lock.writeLock().lock();
        try {
            browsers.remove(browser);
        } finally {
            lock.writeLock().unlock();
        }

    }

    public QueueReceiver createReceiver(Queue queue) throws JMSException {
        verifyState();

        return (createReceiver(queue, null));
    }

    public QueueReceiver createReceiver(Queue queue, String messageSelector)
            throws JMSException {
        verifyState();

        if (queue == null)
            throw new InvalidDestinationException("createReceiver, queue is null!");
        if (queue instanceof TemporaryQueueImpl && !((TemporaryQueueImpl) queue).isCreatungSession(this))
            throw new JMSException("A receiver on a TemporaryQueue can only be created from the session the temporary queue was created from");

        QueueReceiverImpl qr = null;
        CreateConsumerReply reply = null;

        try {
            String ms = messageSelector;
            if (messageSelector != null && messageSelector.trim().length() == 0)
                ms = null;
            if (ms != null) {
                MessageSelector msel = new MessageSelector(ms);
                msel.compile();
            }
            reply = (CreateConsumerReply) requestRegistry.request(new CreateConsumerRequest(this, dispatchId, (QueueImpl) queue, ms));
        } catch (Exception e) {
            throw ExceptionConverter.convert(e);
        }

        if (reply.isOk()) {
            int qcId = reply.getQueueConsumerId();

            qr = new QueueReceiverImpl(transacted, acknowledgeMode, requestRegistry, queue, messageSelector, this);
            qr.setServerQueueConsumerId(qcId);
            qr.setDoAck(!transacted && acknowledgeMode != Session.CLIENT_ACKNOWLEDGE);
            addMessageConsumerImpl(qr);
        } else {
            throw ExceptionConverter.convert(reply.getException());
        }

        return (qr);
    }

    public QueueSender createSender(Queue queue) throws JMSException {
        verifyState();

        // queue can be null = unidentified sender!
        if (queue == null)
            return new QueueSenderImpl(this, queue, -1, requestRegistry, myHostname);

        QueueSenderImpl queueSender = null;
        CreateProducerReply reply = null;

        try {
            reply = (CreateProducerReply) requestRegistry.request(new CreateProducerRequest(this, dispatchId, (QueueImpl) queue));
        } catch (Exception e) {
            throw ExceptionConverter.convert(e);
        }

        if (reply.isOk()) {
            // create the sender
            queueSender = new QueueSenderImpl(this, queue, reply.getQueueProducerId(), requestRegistry, myHostname);
            queueSender.setDestinationImpl(queue);
            addMessageProducerImpl(queueSender);
        } else {
            throw ExceptionConverter.convert(reply.getException());
        }

        return (queueSender);
    }

    public TopicSubscriber createSubscriber(Topic topic)
            throws JMSException {
        return createSubscriber(topic, null, false);
    }

    public TopicSubscriber createSubscriber(Topic topic, String messageSelector, boolean noLocal)
            throws JMSException {
        verifyState();

        if (topic == null)
            throw new InvalidDestinationException("createSubscriber, topic is null!");
        if (topic instanceof TemporaryTopicImpl && !((TemporaryTopicImpl) topic).isCreatingSession(this))
            throw new JMSException("A receiver on a TemporaryTopic can only be created from the session the temporary topic was created from");

        TopicSubscriberImpl ts = null;
        CreateSubscriberReply reply = null;

        try {
            String ms = messageSelector;
            if (messageSelector != null && messageSelector.trim().length() == 0)
                ms = null;
            if (ms != null) {
                MessageSelector msel = new MessageSelector(ms);
                msel.compile();
            }
            reply = (CreateSubscriberReply) requestRegistry.request(new CreateSubscriberRequest(this, dispatchId, (TopicImpl) topic, ms, noLocal, true));
        } catch (Exception e) {
            throw ExceptionConverter.convert(e);
        }

        if (reply.isOk()) {
            int tsId = reply.getTopicSubscriberId();
            ts = new TopicSubscriberImpl(transacted, acknowledgeMode, requestRegistry, topic, messageSelector, this, noLocal);
            ts.setServerQueueConsumerId(tsId);
            ts.setDoAck(false);
            ts.setRecordLog(false);
            addMessageConsumerImpl(ts);
        } else {
            throw ExceptionConverter.convert(reply.getException());
        }

        return (ts);
    }

    public TopicSubscriber createDurableSubscriber(Topic topic, String name)
            throws JMSException {
        return createDurableSubscriber(topic, name, null, false);
    }

    public TopicSubscriber createDurableSubscriber(Topic topic, String name, String messageSelector, boolean noLocal)
            throws JMSException {
        verifyState();

        if (topic == null)
            throw new InvalidDestinationException("createDurableSubscriber, topic is null!");

        if (name == null)
            throw new NullPointerException("createDurableSubscriber, name is null!");

        if (myConnection.getClientID() == null)
            throw new IllegalStateException("unable to create durable subscriber, no client ID has been set");

        try {
            SwiftUtilities.verifyDurableName(name);
        } catch (Exception e) {
            throw new JMSException(e.getMessage());
        }

        TopicSubscriberImpl ts = null;
        CreateDurableReply reply = null;

        try {
            String ms = messageSelector;
            if (messageSelector != null && messageSelector.trim().length() == 0)
                ms = null;
            if (ms != null) {
                MessageSelector msel = new MessageSelector(ms);
                msel.compile();
            }
            reply = (CreateDurableReply) requestRegistry.request(new CreateDurableRequest(this, dispatchId, (TopicImpl) topic, ms, noLocal, name));
        } catch (Exception e) {
            throw ExceptionConverter.convert(e);
        }

        if (reply.isOk()) {
            int tsId = reply.getTopicSubscriberId();

            ts = new DurableTopicSubscriberImpl(transacted, acknowledgeMode, requestRegistry, topic, messageSelector, this, noLocal, name);
            ts.setServerQueueConsumerId(tsId);
            ts.setDoAck(!transacted && acknowledgeMode != Session.CLIENT_ACKNOWLEDGE);
            addMessageConsumerImpl(ts);
        } else {
            throw ExceptionConverter.convert(reply.getException());
        }

        return (ts);
    }

    @Override
    public MessageConsumer createDurableConsumer(Topic topic, String s) throws JMSException {
        return null;
    }

    @Override
    public MessageConsumer createDurableConsumer(Topic topic, String s, String s1, boolean b) throws JMSException {
        return null;
    }

    @Override
    public MessageConsumer createSharedDurableConsumer(Topic topic, String s) throws JMSException {
        return null;
    }

    @Override
    public MessageConsumer createSharedDurableConsumer(Topic topic, String s, String s1) throws JMSException {
        return null;
    }

    public TopicPublisher createPublisher(Topic topic)
            throws JMSException {
        verifyState();

        // topic can be null = unidentified publisher
        if (topic == null)
            return new TopicPublisherImpl(this, topic, -1, requestRegistry, myHostname, clientId);

        TopicPublisherImpl topicPublisher = null;
        CreatePublisherReply reply = null;

        try {
            reply = (CreatePublisherReply) requestRegistry.request(new CreatePublisherRequest(this, dispatchId, (TopicImpl) topic));
        } catch (Exception e) {
            throw ExceptionConverter.convert(e);
        }

        if (reply.isOk()) {

            // create the publisher
            topicPublisher = new TopicPublisherImpl(this, topic, reply.getTopicPublisherId(),
                    requestRegistry, myHostname, clientId);
            topicPublisher.setDestinationImpl(topic);
            addMessageProducerImpl(topicPublisher);
        } else {
            throw ExceptionConverter.convert(reply.getException());
        }

        return (topicPublisher);
    }

    public MessageProducer createProducer(Destination destination) throws JMSException {
        if (destination == null) // unidentified
            return createSender(null);
        DestinationImpl destImpl = (DestinationImpl) destination;
        MessageProducer producer = null;
        switch (destImpl.getType()) {
            case DestinationFactory.TYPE_QUEUE:
            case DestinationFactory.TYPE_TEMPQUEUE:
                producer = createSender((Queue) destination);
                break;
            case DestinationFactory.TYPE_TOPIC:
            case DestinationFactory.TYPE_TEMPTOPIC:
                producer = createPublisher((Topic) destination);
                break;
        }
        return producer;
    }

    public MessageConsumer createConsumer(Destination destination) throws JMSException {
        return createConsumer(destination, null, false);
    }

    public MessageConsumer createConsumer(Destination destination, String selector) throws JMSException {
        return createConsumer(destination, selector, false);
    }

    public MessageConsumer createConsumer(Destination destination, String selector, boolean noLocal) throws JMSException {
        if (destination == null)
            throw new InvalidDestinationException("createConsumer, destination is null!");
        DestinationImpl destImpl = (DestinationImpl) destination;
        MessageConsumer consumer = null;
        switch (destImpl.getType()) {
            case DestinationFactory.TYPE_TEMPQUEUE:
            case DestinationFactory.TYPE_QUEUE:
                consumer = createReceiver((Queue) destination, selector);
                break;
            case DestinationFactory.TYPE_TEMPTOPIC:
            case DestinationFactory.TYPE_TOPIC:
                consumer = createSubscriber((Topic) destination, selector, noLocal);
                break;
        }
        return consumer;
    }

    @Override
    public MessageConsumer createSharedConsumer(Topic topic, String s) throws JMSException {
        return null;
    }

    @Override
    public MessageConsumer createSharedConsumer(Topic topic, String s, String s1) throws JMSException {
        return null;
    }

    public Queue createQueue(String queueName) throws JMSException {
        verifyState();
        if (type == TYPE_TOPIC_SESSION)
            throw new IllegalStateException("Operation not allowed on this session type");

        if (queueName == null)
            throw new InvalidDestinationException("createQueue, queueName is null!");

        return new QueueImpl(queueName);
    }

    public Topic createTopic(String topicName)
            throws JMSException {
        verifyState();
        if (type == TYPE_QUEUE_SESSION)
            throw new IllegalStateException("Operation not allowed on this session type");
        if (topicName == null)
            throw new InvalidDestinationException("createTopic, topicName is null!");
        if (topicName.indexOf('@') != -1)
            throw new InvalidDestinationException("Invalid character '@' in topic name! Hint: a topic name must NOT be qualified with the router name!");

        return new TopicImpl(topicName);
    }

    public QueueBrowser createBrowser(Queue queue) throws JMSException {
        verifyState();
        if (type == TYPE_TOPIC_SESSION)
            throw new IllegalStateException("Operation not allowed on this session type");
        return (createBrowser(queue, null));
    }

    public QueueBrowser createBrowser(Queue queue, String messageSelector)
            throws JMSException {
        verifyState();
        if (type == TYPE_TOPIC_SESSION)
            throw new IllegalStateException("Operation not allowed on this session type");

        if (queue == null)
            throw new InvalidDestinationException("createBrowser, queue is null!");

        QueueBrowserImpl queueBrowser = null;
        CreateBrowserReply reply = null;

        try {
            String ms = messageSelector;
            if (messageSelector != null && messageSelector.trim().length() == 0)
                ms = null;
            if (ms != null) {
                MessageSelector msel = new MessageSelector(ms);
                msel.compile();
            }
            reply = (CreateBrowserReply) requestRegistry.request(new CreateBrowserRequest(this, dispatchId, (QueueImpl) queue, ms));
        } catch (Exception e) {
            throw ExceptionConverter.convert(e);
        }

        if (reply.isOk()) {
            // create the browser
            queueBrowser = new QueueBrowserImpl(this, queue, messageSelector, dispatchId, reply.getQueueBrowserId(), requestRegistry);
            addQueueBrowserImpl(queueBrowser);
        } else {
            throw ExceptionConverter.convert(reply.getException());
        }

        return (queueBrowser);
    }

    public TemporaryQueue createTemporaryQueue() throws JMSException {
        verifyState();
        if (type == TYPE_TOPIC_SESSION)
            throw new IllegalStateException("Operation not allowed on this session type");

        TemporaryQueueImpl tempQueue = null;
        CreateTmpQueueReply reply = null;

        try {
            reply = (CreateTmpQueueReply) requestRegistry.request(new CreateTmpQueueRequest(this, 0));
            tempQueue = new TemporaryQueueImpl(reply.getQueueName(), myConnection);
            tempQueue.setCreatingSession(this);
            myConnection.addTmpQueue(tempQueue);
        } catch (Exception e) {
            throw ExceptionConverter.convert(e);
        }

        if (!reply.isOk()) {
            throw ExceptionConverter.convert(reply.getException());
        }

        return tempQueue;
    }

    public TemporaryTopic createTemporaryTopic()
            throws JMSException {
        verifyState();
        if (type == TYPE_QUEUE_SESSION)
            throw new IllegalStateException("Operation not allowed on this session type");

        TemporaryTopicImpl tempTopic = null;
        CreateTmpQueueReply reply = null;

        try {
            reply = (CreateTmpQueueReply) requestRegistry.request(new CreateTmpQueueRequest(this, 0));
            tempTopic = new TemporaryTopicImpl(reply.getQueueName(), myConnection);
            tempTopic.setCreatingSession(this);
            myConnection.addTmpQueue(tempTopic);
        } catch (Exception e) {
            throw ExceptionConverter.convert(e);
        }

        if (!reply.isOk()) {
            throw ExceptionConverter.convert(reply.getException());
        }

        return tempTopic;
    }

    public void unsubscribe(String name)
            throws JMSException {
        verifyState();
        if (type == TYPE_QUEUE_SESSION)
            throw new IllegalStateException("Operation not allowed on this session type");

        if (name == null)
            throw new NullPointerException("unsubscribe, name is null!");

        DeleteDurableReply reply = null;

        try {
            reply =
                    (DeleteDurableReply) requestRegistry.request(new DeleteDurableRequest(this, dispatchId, name));
        } catch (Exception e) {
            throw ExceptionConverter.convert(e);
        }

        if (!reply.isOk()) {
            throw ExceptionConverter.convert(reply.getException());
        }
    }
    // <-- JMS 1.1

    public int getAcknowledgeMode() throws JMSException {
        verifyState();
        return acknowledgeMode;
    }

    public BytesMessage createBytesMessage() throws JMSException {
        verifyState();

        return (new BytesMessageImpl());
    }

    public MapMessage createMapMessage() throws JMSException {
        verifyState();

        return (new MapMessageImpl());
    }

    public Message createMessage() throws JMSException {
        verifyState();

        return (new MessageImpl());
    }

    public ObjectMessage createObjectMessage() throws JMSException {
        verifyState();

        return (new ObjectMessageImpl());
    }

    public ObjectMessage createObjectMessage(Serializable object)
            throws JMSException {
        verifyState();

        ObjectMessage msg = createObjectMessage();

        msg.setObject(object);

        return (msg);
    }

    public StreamMessage createStreamMessage() throws JMSException {
        verifyState();

        return (new StreamMessageImpl());     // NYI
    }

    public TextMessage createTextMessage() throws JMSException {
        verifyState();

        return (new TextMessageImpl());
    }

    public TextMessage createTextMessage(String s)
            throws JMSException {
        verifyState();

        TextMessage msg = createTextMessage();

        msg.setText(s);

        return (msg);
    }

    public boolean getTransacted() throws JMSException {
        verifyState();

        return (transacted);
    }

    public void commit() throws JMSException {
        verifyState();

        if (transacted) {
            CommitReply reply = null;
            try {
                CommitRequest req = new CommitRequest(this, dispatchId);
                reply = (CommitReply) requestTransaction(req);
                txCancelled = req.isCancelledByValidator() || req.isWasRetry();
            } catch (Exception e) {
                throw ExceptionConverter.convert(e);
            }

            if (!reply.isOk()) {
                throw ExceptionConverter.convert(reply.getException());
            }
            afterCommit();

            long delay = reply.getDelay();
            if (delay > 0) {
                try {
                    Thread.sleep(delay);
                } catch (Exception ignored) {
                }
            }
        } else {
            throw new jakarta.jms.IllegalStateException("Session is not transacted - commit not allowed");
        }
    }

    void afterCommit() throws JMSException {
        closeDelayedProducers();
        addCurrentTxToDuplicateLog();
        removeCurrentTxFromRollbackLog();
        clearCurrentTxLog();
    }

    void delayClose(MessageProducerImpl producer) {
        delayedClosedProducers.add(producer);
    }

    void closeDelayedProducers() throws JMSException {
        for (int i = 0; i < delayedClosedProducers.size(); i++) {
            ((MessageProducerImpl) delayedClosedProducers.get(i))._close(false);
        }
        delayedClosedProducers.clear();
    }

    void startRecoverConsumers() {
        lock.writeLock().lock();
        try {
            sessionQueue.stopQueue();
            recoveryInProgress = true;
            recoveryEpoche++;
            for (Iterator iter = consumerMap.entrySet().iterator(); iter.hasNext(); ) {
                MessageConsumerImpl c = (MessageConsumerImpl) ((Map.Entry) iter.next()).getValue();
                c.setWasRecovered(true);
                c.clearCache();
            }
            sessionQueue.clear();
            if (connectionConsumer != null && xaMode)
                connectionConsumer.removeFromDuplicateLog(lastMessage.getMessage());
        } finally {
            lock.writeLock().unlock();
        }

    }

    void endRecoverConsumers() {
        addCurrentTxToRollbackLog();
        clearCurrentTxLog();
        recoveryInProgress = false;
        if (!resetInProgress) {
            sessionQueue.startQueue();
            for (Iterator iter = consumerMap.entrySet().iterator(); iter.hasNext(); ) {
                MessageConsumerImpl c = (MessageConsumerImpl) ((Map.Entry) iter.next()).getValue();
                if (c.isConsumerStarted())
                    c.fillCache(true);
            }
        }
    }

    void endRecoverConsumersXA() {
        addCurrentTxToRollbackLog();
        clearCurrentTxLog();
        recoveryInProgress = false;
        if (!resetInProgress && !txCancelled) {
            sessionQueue.startQueue();
            for (Iterator iter = consumerMap.entrySet().iterator(); iter.hasNext(); ) {
                MessageConsumerImpl c = (MessageConsumerImpl) ((Map.Entry) iter.next()).getValue();
                if (c.isConsumerStarted())
                    c.fillCache(true);
            }
        }
    }

    public void rollback() throws JMSException {
        verifyState();

        if (transacted) {
            startRecoverConsumers();

            // drop stored messages on the client side (produced messages)
            dropTransaction();

            // also rollback on client side (for consumed messages)
            Reply reply = null;

            try {
                RollbackRequest req = new RollbackRequest(this, dispatchId, recoveryEpoche);
                reply = requestRegistry.request(req);
                txCancelled = req.isCancelledByValidator();
            } catch (Exception e) {
                throw ExceptionConverter.convert(e);
            }

            if (reply.isOk()) {
                endRecoverConsumers();
                closeDelayedProducers();
            } else {
                throw ExceptionConverter.convert(reply.getException());
            }
        } else {
            throw new jakarta.jms.IllegalStateException("Session is not transacted - rollback not allowed");
        }
    }

    boolean isClosed() {
        return closed;
    }

    public void close() throws JMSException {
        if (closed)
            return;
        if (messageListener == null && !isSessionStarted()) {
            _close();
            return;
        }
        CloseSession request = new CloseSession();
        request._sem = new Semaphore();
        if (messageListener == null)
            serviceRequest(request);
        else {
            addMessageChunk(request);
        }
        request._sem.waitHere(5000);
        if (!request._sem.isNotified())
            _close();
    }

    private void _close() {
        if (ignoreClose || closed)
            return;
        sessionQueue.stopQueue();
        sessionQueue.clear();
        lock.writeLock().lock();
        try {
            closed = true;
            if (consumerMap.size() > 0) {
                myConnection.decreaseDuplicateLogSize(myConnection.getSmqpConsumerCacheSize() * consumerMap.size());
                for (Object o : consumerMap.entrySet()) {
                    MessageConsumerImpl consumer = (MessageConsumerImpl) ((Map.Entry) o).getValue();
                    consumer.cancel();
                }
                consumerMap.clear();
            }
            consumerDirty = true;
            producers.clear();
            if (transacted) {
                dropTransaction();
            }
        } finally {
            lock.writeLock().unlock();
        }


        try {
            requestRegistry.request(new CloseSessionRequest(0, dispatchId));
        } catch (Exception e) {
        }
        myConnection.removeRequestService(myDispatchId);
        myConnection.removeSession(this);
    }

    void cancel() {
        closed = true;
        sessionQueue.stopQueue();
        sessionQueue.clear();
        for (Iterator iter = consumerMap.entrySet().iterator(); iter.hasNext(); ) {
            MessageConsumerImpl consumer = (MessageConsumerImpl) ((Map.Entry) iter.next()).getValue();
            consumer.cancel();
        }
        consumerMap.clear();
        consumerDirty = true;
        producers.clear();
        if (transacted) {
            dropTransaction();
        }
    }

    public void recover() throws JMSException {
        verifyState();

        if (!transacted) {
            // JMS-TCK to force JMSRedelivered on auto-ack/recover within onMessage
            if (acknowledgeMode == Session.AUTO_ACKNOWLEDGE && withinOnMessage && onMessageConsumer != null && onMessageMessage != null) {
                onMessageConsumer.reportDelivered(onMessageMessage, false);
                onMessageConsumer = null;
            }

            startRecoverConsumers();

            Reply reply = null;
            try {
                RecoverSessionRequest req = new RecoverSessionRequest(this, dispatchId, recoveryEpoche);
                reply = requestRegistry.request(req);
                txCancelled = req.isCancelledByValidator();
            } catch (Exception e) {
                throw ExceptionConverter.convert(e);
            }

            if (reply.isOk()) {
                endRecoverConsumers();
            } else {
                throw ExceptionConverter.convert(reply.getException());
            }
        } else {
            throw new jakarta.jms.IllegalStateException("Session is transacted - recover not allowed");
        }
    }

    public MessageListener getMessageListener() throws JMSException {
        verifyState();
        return messageListener;
    }

    public void setMessageListener(MessageListener messageListener) throws JMSException {
        lock.writeLock().lock();
        try {
            verifyState();
            this.messageListener = messageListener;
            if (messageListener != null)
                sessionQueue.stopQueue();
        } finally {
            lock.writeLock().unlock();
        }

    }

    boolean isShadowConsumerCreated() {
        return shadowConsumerCreated;
    }

    void createShadowConsumer(String queueName) throws Exception {
        lock.writeLock().lock();
        try {
            shadowConsumerQueueName = queueName;
            Reply reply = requestRegistry.request(new CreateShadowConsumerRequest(this, dispatchId, queueName));
            if (!reply.isOk())
                throw reply.getException();
            shadowConsumerCreated = true;
        } finally {
            lock.writeLock().unlock();
        }

    }

    void addMessageChunk(Object obj) {
        lock.writeLock().lock();
        try {
            if (obj instanceof CloseSession && !isRunning) {
                _close();
                ((CloseSession) obj)._sem.notifySingleWaiter();
                return;
            }
            messageChunk.add(obj);
        } finally {
            lock.writeLock().unlock();
        }

    }

    void clearMessageChunks() {
        lock.writeLock().lock();
        try {
            messageChunk.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }

    private MessageEntry nextMessageChunk() {
        lock.writeLock().lock();
        try {
            if (messageChunk.getSize() == 0)
                return null;
            Object o = messageChunk.remove();
            if (o instanceof CloseSession) {
                _close();
                ((CloseSession) o)._sem.notifySingleWaiter();
                return null;
            }
            return (MessageEntry) o;
        } finally {
            lock.writeLock().unlock();
        }
    }

    boolean assignLastMessage() throws Exception {
        return assignLastMessage(false);
    }

    boolean assignLastMessage(boolean duplicate) throws Exception {
        if (lastMessage != null) {
            Request request = new AssociateMessageRequest(this, dispatchId, lastMessage.getMessageIndex(), duplicate);
            Reply reply = requestRegistry.request(request);
            if (!reply.isOk()) {
                throw reply.getException();
            }
            return request.isCancelledByValidator();
        }
        return false;
    }

    void deleteMessage(MessageEntry messageEntry, boolean fromReadTx) {
        Request request = new DeleteMessageRequest(this, dispatchId, lastMessage.getMessageIndex(), fromReadTx);
        requestRegistry.request(request);
    }

    void setAutoAssign(boolean autoAssign) {
        this.autoAssign = autoAssign;
    }

    public boolean acknowledgeMessage(MessageIndex messageIndex) throws JMSException {
        if (closed)
            throw new jakarta.jms.IllegalStateException("Connection is closed");
        Request request = new AcknowledgeMessageRequest(this, dispatchId, 0, messageIndex);
        Reply reply = requestRegistry.request(request);
        if (!reply.isOk())
            throw ExceptionConverter.convert(reply.getException());
        addCurrentTxToDuplicateLog();
        removeCurrentTxFromRollbackLog();
        clearCurrentTxLog();
        return request.isCancelledByValidator();
    }

    public void run() {
        if (closed || resetInProgress)
            return;
        if (messageListener == null)
            throw new RuntimeException("No MessageListener has been set!");
        setRunning(true);
        while ((lastMessage = nextMessageChunk()) != null) {
            MessageImpl message = lastMessage.getMessage();
            String messageId = null;
            try {
                messageId = message.getJMSMessageID();
                connectionConsumer.markInProgress(message, messageId);
                if (closed || resetInProgress) {
                    setRunning(false);
                    return;
                }
                if (lastMessage.getConnectionId() != myConnection.getConnectionId()) {
                    continue; // continue with next message
                }
                lastMessage.moveMessageAttributes();
                // eventually assign message to session
                boolean cancelled = false;
                try {
                    if (autoAssign)
                        cancelled = assignLastMessage();
                } catch (Exception e) {
                    setRunning(false);
                    return;
                }
                if (cancelled) {
                    setRunning(false);
                    return;
                }
                boolean duplicate = connectionConsumer.isDuplicate(message);
                if (autoAssign && duplicate) {
                    deleteMessage(lastMessage, acknowledgeMode != Session.CLIENT_ACKNOWLEDGE);
                    continue;
                }
                message.setSessionImpl(this);
                message.setReadOnly(true);
                message.reset();
                message.setUseThreadContextCL(useThreadContextCL);
                if (xaMode && duplicate) {
                    cancelled = assignLastMessage(true);
                    if (cancelled)
                        connectionConsumer.removeFromDuplicateLog(message);
                } else {
                    withinOnMessage = true;
                    onMessageMessage = message;
                    messageListener.onMessage(message);
                    onMessageMessage = null;
                    withinOnMessage = false;
                }
                // eventually ack message when auto-ack
                if (!cancelled && !transacted && acknowledgeMode != Session.CLIENT_ACKNOWLEDGE) {
                    cancelled = acknowledgeMessage(lastMessage.getMessageIndex());
                    if (cancelled) {
                        setRunning(false);
                        return;
                    }
                }
            } catch (Exception e) {
                withinOnMessage = false;
                setRunning(false);
                return;
            } finally {
                connectionConsumer.unmarkInProgress(message, messageId);
            }
            lastMessage = null;
        }
        setRunning(false);
    }

    void addCurrentTxLog(String id) {
        if (id != null) {
            currentTxLog.add(id);
        }
    }

    void clearCurrentTxLog() {
        currentTxLog.clear();
    }

    void addCurrentTxToDuplicateLog() {
        myConnection.addToDuplicateLog(currentTxLog);
    }

    void addCurrentTxToRollbackLog() {
        rollbackIdLog.addAll(currentTxLog);
    }

    void removeCurrentTxFromRollbackLog() {
        rollbackIdLog.removeAll(currentTxLog);
    }

    void addRollbackLogToDuplicateLog() {
        myConnection.addToDuplicateLog(rollbackIdLog);
    }

    boolean isDuplicate(String id) {
        if (id == null)
            return false;
        boolean duplicate = myConnection.isDuplicate(id);
        if (currentTxLog.contains(id))
            duplicate = true;
        else if (rollbackIdLog.contains(id))
            duplicate = false;
        return duplicate;
    }

    private void doDeliverMessage(AsyncMessageDeliveryRequest request) {
        lock.writeLock().lock();
        try {
            if (closed || resetInProgress || recoveryInProgress || request.getRecoveryEpoche() != recoveryEpoche)
                return;

            int consumerId = request.getListenerId();
            MessageConsumerImpl consumer = (MessageConsumerImpl) consumerMap.get(new Integer(consumerId));
            if (consumer != null) {
                if (SMQPUtil.isBulk(request)) {
                    AsyncMessageDeliveryRequest[] requests = SMQPUtil.createRequests(request);
                    consumer.addToCache(requests, request.isRequiresRestart());
                } else {
                    consumer.addToCache(request);
                }
            }
        } finally {
            lock.writeLock().unlock();
        }

    }

    void triggerInvocation() {
        sessionQueue.triggerInvocation();
    }

    public void serviceRequest(Request request) {
        sessionQueue.enqueue(request);
    }

    private class SessionDeliveryQueue extends SingleProcessorQueue {
        Visitor visitor = new Visitor();
        TriggerConsumerInvocation trigger = new TriggerConsumerInvocation();
        MessageConsumerImpl consumerCopy[] = null;
        boolean currentCallInvalid = false;

        public SessionDeliveryQueue() {
            super(100);
        }

        public boolean isCurrentCallInvalid() {
            return currentCallInvalid;
        }

        public void setCurrentCallInvalid(boolean currentCallInvalid) {
            this.currentCallInvalid = currentCallInvalid;
        }

        protected void startProcessor() {
            if (!closed)
                sessionPool.dispatchTask(sessionTask);
        }

        void triggerInvocation() {
            enqueue(trigger);
        }

        void copyConsumers() {
            lock.readLock().lock();
            try {
                if (consumerDirty) {
                    consumerCopy = new MessageConsumerImpl[consumerMap.size()];
                    int i = 0;
                    for (Object o : consumerMap.entrySet()) {
                        consumerCopy[i++] = (MessageConsumerImpl) ((Map.Entry) o).getValue();
                    }
                    consumerDirty = false;
                }
            } finally {
                lock.readLock().unlock();
            }

        }

        // Checks if the session is in valid state.
        private boolean valid() {
            return !resetInProgress && !recoveryInProgress && isStarted() && !closed;
        }

        protected boolean validateClearElement(Object obj) {
            return !(obj instanceof CloseSession || obj instanceof CloseConsumer);
        }

        protected void process(Object[] bulk, int n) {
            // This flag needs only to be respected when returning from processRequest!
            if (currentCallInvalid)
                currentCallInvalid = false;
            if (!valid())
                return;
            // First: fill the cache
            for (int i = 0; i < n; i++) {
                ((Request) bulk[i]).accept(visitor);
                if (!valid() || currentCallInvalid)
                    return;
            }
            // Next: invoke consumers
            boolean moreToDeliver = false;
            copyConsumers();
            if (consumerCopy != null) {
                for (int i = 0; i < consumerCopy.length; i++) {
                    if (!valid())
                        break;
                    MessageConsumerImpl c = (MessageConsumerImpl) consumerCopy[i];
                    boolean b = c.invokeConsumer();
                    if (!valid() || currentCallInvalid)
                        return;
                    if (b)
                        moreToDeliver = true;
                }
            }
            // Last: if there is more to deliver, trigger myself
            if (moreToDeliver && valid())
                triggerInvocation();
        }
    }

    private class Visitor extends SessionVisitorAdapter {
        public void visit(TriggerConsumerInvocation request) {
            // do nothing, it's only a trigger
        }

        public void visit(AsyncMessageDeliveryRequest req) {
            if (req.getConnectionId() == myConnection.getConnectionId())
                doDeliverMessage(req);
        }

        public void visit(CloseConsumer request) {
            try {
                MessageConsumerImpl c = (MessageConsumerImpl) consumerMap.get(new Integer(request.getId()));
                if (c != null)
                    c.close(null);
            } catch (Exception e) {
            }
            request._sem.notifySingleWaiter();
        }

        public void visit(CloseSession request) {
            _close();
            request._sem.notifySingleWaiter();
        }
    }

    private class SessionTask implements AsyncTask {
        public boolean isValid() {
            return !closed;
        }

        public String getDispatchToken() {
            return DISPATCH_TOKEN;
        }

        public String getDescription() {
            return myConnection.myHostname + "/Session/SessionTask";
        }

        public void run() {
            if (!closed && sessionQueue.dequeue())
                sessionPool.dispatchTask(this);
        }

        public void stop() {
        }
    }

    private class ShadowConsumerRecreator implements Recreatable {
        public Request getRecreateRequest() {
            return new CreateShadowConsumerRequest(SessionImpl.this, dispatchId, shadowConsumerQueueName);
        }

        public void setRecreateReply(Reply reply) {
            if (reply.isOk())
                shadowConsumerCreated = true;
        }

        public List getRecreatables() {
            return null;
        }
    }
}



