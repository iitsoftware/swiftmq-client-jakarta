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

import com.swiftmq.jms.QueueImpl;
import com.swiftmq.jms.smqp.v750.CreateProducerReply;
import com.swiftmq.jms.smqp.v750.CreateProducerRequest;
import com.swiftmq.tools.requestreply.Reply;
import com.swiftmq.tools.requestreply.Request;
import com.swiftmq.tools.requestreply.RequestRegistry;

import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.Queue;
import jakarta.jms.QueueSender;
import java.util.List;

public class QueueSenderImpl extends MessageProducerImpl
        implements QueueSender, Recreatable {
    Queue queue = null;

    public QueueSenderImpl(SessionImpl mySession, Queue queue,
                           int producerId, RequestRegistry requestRegistry,
                           String myHostname) {
        super(mySession, producerId, requestRegistry, myHostname, null);
        this.queue = queue;
    }

    public Request getRecreateRequest() {
        return new CreateProducerRequest(mySession, mySession.dispatchId, (QueueImpl) queue);
    }

    public void setRecreateReply(Reply reply) {
        producerId = ((CreateProducerReply) reply).getQueueProducerId();
    }

    public List getRecreatables() {
        return null;
    }

    public Queue getQueue() throws JMSException {
        verifyState();

        return (queue);
    }

    public void send(Queue queue, Message message) throws JMSException {
        super.send(queue, message);
    }

    public void send(Queue queue, Message message, int deliveryMode,
                     int priority, long timeToLive) throws JMSException {
        super.send(queue, message, deliveryMode, priority, timeToLive);
    }

}



