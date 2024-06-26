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
import com.swiftmq.jms.ExceptionConverter;
import com.swiftmq.jms.MessageImpl;
import com.swiftmq.jms.smqp.v750.AsyncMessageDeliveryRequest;
import com.swiftmq.jms.smqp.v750.CloseSessionRequest;
import com.swiftmq.jms.smqp.v750.StartConsumerRequest;
import com.swiftmq.swiftlet.queue.MessageEntry;
import com.swiftmq.swiftlet.threadpool.AsyncTask;
import com.swiftmq.swiftlet.threadpool.ThreadPool;
import com.swiftmq.tools.queue.SingleProcessorQueue;
import com.swiftmq.tools.requestreply.*;
import com.swiftmq.tools.util.IdGenerator;
import jakarta.jms.ConnectionConsumer;
import jakarta.jms.JMSException;
import jakarta.jms.ServerSession;
import jakarta.jms.ServerSessionPool;

import java.util.Date;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public abstract class ConnectionConsumerImpl
        implements ConnectionConsumer, RequestService, Recreatable, RequestRetryValidator {
    public static final String DISPATCH_TOKEN = "sys$jms.client.session.connectionconsumer.queuetask";
    private static final long CC_SS_DELAY = Long.parseLong(System.getProperty("swiftmq.cc.ss.delay", "100"));
    static final boolean DEBUG = Boolean.valueOf(System.getProperty("swiftmq.reconnect.debug", "false")).booleanValue();

    String uniqueConsumerId = IdGenerator.getInstance().nextId('/');
    ConnectionImpl myConnection = null;
    int dispatchId = 0;
    int myDispatchId = 0;
    RequestRegistry requestRegistry = null;
    ThreadPool sessionPool = null;
    DeliveryQueue deliveryQueue = null;
    QueueTask queueTask = null;
    ServerSessionPool serverSessionPool;
    int maxMessages = 0;
    volatile ServerSession currentServerSession = null;
    volatile SessionImpl currentSession = null;
    volatile int nCurrent = 0;
    volatile boolean closed = false;
    volatile boolean resetInProgress = false;
    Set<String> messagesInProgress = ConcurrentHashMap.newKeySet();


    public ConnectionConsumerImpl(ConnectionImpl myConnection, int dispatchId, RequestRegistry requestRegistry, ServerSessionPool serverSessionPool, int maxMessages) {
        this.myConnection = myConnection;
        this.dispatchId = dispatchId;
        this.requestRegistry = requestRegistry;
        this.serverSessionPool = serverSessionPool;
        this.maxMessages = maxMessages;
        this.sessionPool = PoolManager.getInstance().getSessionPool();
        queueTask = new QueueTask();
        deliveryQueue = new DeliveryQueue();
    }

    public void setResetInProgress(boolean resetInProgress) {
        if (DEBUG) System.out.println(new Date() + " " + toString() + ", setResetInProgress=" + resetInProgress);
        this.resetInProgress = resetInProgress;
        if (resetInProgress) {
            deliveryQueue.stopQueue();
            deliveryQueue.clear();
            deliveryQueue.setCurrentCallInvalid(true);
        } else {
            deliveryQueue.clear();
            deliveryQueue.startQueue();
            fillCache();
        }
    }

    public void validate(Request request) throws ValidationException {
        request.setDispatchId(dispatchId);
    }

    void startConsumer() {
        deliveryQueue.startQueue();
    }

    void stopConsumer() {
        deliveryQueue.stopQueue();
    }

    void fillCache() {
        if (DEBUG) System.out.println(new Date() + " " + toString() + ", fillCache");
        requestRegistry.request(new StartConsumerRequest(this, dispatchId, 0, myDispatchId, 0, myConnection.getSmqpConsumerCacheSize(), myConnection.getSmqpConsumerCacheSizeKB()));
    }

    protected abstract String getQueueName();

    public void setMyDispatchId(int myDispatchId) {
        this.myDispatchId = myDispatchId;
    }

    int getMsgNo(AsyncMessageDeliveryRequest request) {
        int n = 0;
        try {
            n = request.getMessageEntry().getMessage().getIntProperty("no");
        } catch (JMSException e) {
            e.printStackTrace();
        }
        return n;
    }

    public void serviceRequest(Request request) {
        deliveryQueue.enqueue(request);
    }

    public boolean isDuplicate(MessageImpl msg) {
        if (!myConnection.isDuplicateMessageDetection())
            return false;
        msg.setDuplicateId(SessionImpl.buildId(uniqueConsumerId, msg));
        return myConnection.isDuplicate(msg.getDuplicateId());
    }

    public void removeFromDuplicateLog(MessageImpl msg) {
        myConnection.removeFromDuplicateLog(msg.getDuplicateId());
    }

    public void markInProgress(MessageImpl msg, String messageId) {
        if (!myConnection.isDuplicateMessageDetection())
            return;
        if (messageId != null) {
            messagesInProgress.add(messageId);
        }
    }

    public void unmarkInProgress(MessageImpl msg, String messageId) {
        if (!myConnection.isDuplicateMessageDetection())
            return;
        if (messageId != null) {
            messagesInProgress.remove(messageId);
        }
    }

    private void checkInProgress(MessageImpl msg, String messageId) {
        if (!myConnection.isDuplicateMessageDetection())
            return;
        boolean inProgress = false;
        do {
            inProgress = messagesInProgress.contains(messageId);
            if (inProgress) {
                try {
                    Thread.sleep(CC_SS_DELAY);
                } catch (InterruptedException e) {
                }
            }
        } while (inProgress && !(resetInProgress || deliveryQueue.isCurrentCallInvalid()));
    }

    public void processRequest(AsyncMessageDeliveryRequest request, boolean hasNext) {
        if (resetInProgress || deliveryQueue.isCurrentCallInvalid()) {
            return;
        }
        if (request.getConnectionId() != myConnection.getConnectionId()) {
            return;
        }
        try {
            if (currentServerSession == null) {
                currentServerSession = serverSessionPool.getServerSession();
                if (currentServerSession.getSession() instanceof XASessionImpl)
                    currentSession = ((XASessionImpl) currentServerSession.getSession()).session;
                else
                    currentSession = (SessionImpl) currentServerSession.getSession();
                nCurrent = 0;
                if (!currentSession.isShadowConsumerCreated())
                    currentSession.createShadowConsumer(getQueueName());
                currentSession.setConnectionConsumer(this);
                if (resetInProgress || deliveryQueue.isCurrentCallInvalid()) {
                    return;
                }
            }
            MessageEntry me = request.getMessageEntry();
            checkInProgress(me.getMessage(), me.getMessage().getJMSMessageID());
            if (resetInProgress || deliveryQueue.isCurrentCallInvalid()) {
                return;
            }
            me.setConnectionId(request.getConnectionId());
            currentSession.addMessageChunk(me);
            nCurrent++;
            if (nCurrent == maxMessages || !hasNext) {
                currentServerSession.start();
                currentServerSession = null;
                currentSession = null;
                nCurrent = 0;
            }
        } catch (Exception e) {
            e.printStackTrace();
            // TODO: Handle it some way like InvalidateConsumerRequest!!!
        }
        if (request.isRequiresRestart())
            fillCache();
    }

    public ServerSessionPool getServerSessionPool() throws JMSException {
        return serverSessionPool;
    }

    public boolean isClosed() {
        return closed;
    }

    public void close() throws JMSException {
        if (closed)
            return;
        Reply reply = null;
        try {
            reply = requestRegistry.request(new CloseSessionRequest(this, 0, dispatchId));
        } catch (Exception e) {
            throw ExceptionConverter.convert(e);
        }
        deliveryQueue.stopQueue();
        deliveryQueue.close();
        myConnection.removeRequestService(myDispatchId);
        myConnection.removeConnectionConsumer(this);
        if (!reply.isOk()) {
            throw ExceptionConverter.convert(reply.getException());
        }
    }

    void cancel() {
        closed = true;
        deliveryQueue.stopQueue();
        deliveryQueue.close();
    }

    private class DeliveryQueue extends SingleProcessorQueue {
        volatile boolean currentCallInvalid = false;

        public DeliveryQueue() {
            super(myConnection.smqpConsumerCacheSize);
        }

        public boolean isCurrentCallInvalid() {
            return currentCallInvalid;
        }

        public void setCurrentCallInvalid(boolean currentCallInvalid) {
            this.currentCallInvalid = currentCallInvalid;
        }

        protected void startProcessor() {
            if (!closed)
                sessionPool.dispatchTask(queueTask);
        }

        private boolean valid() {
            return !resetInProgress && isStarted();
        }

        protected void process(Object[] bulk, int n) {
            // This flag needs only to be respected when returning from processRequest!
            if (currentCallInvalid)
                currentCallInvalid = false;
            for (int i = 0; i < n; i++) {
                processRequest((AsyncMessageDeliveryRequest) bulk[i], i + 1 < n);
                if (!valid() || currentCallInvalid)
                    return;
            }
        }
    }

    private class QueueTask implements AsyncTask {
        public boolean isValid() {
            return !closed;
        }

        public String getDispatchToken() {
            return DISPATCH_TOKEN;
        }

        public String getDescription() {
            return myConnection.myHostname + "/ConnectionConsumer/QueueTask";
        }

        public void run() {
            if (!closed && deliveryQueue.dequeue())
                sessionPool.dispatchTask(this);
        }

        public void stop() {
        }
    }
}
