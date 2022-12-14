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

package com.swiftmq.filetransfer.protocol;

import com.swiftmq.jms.MessageImpl;
import jakarta.jms.JMSException;
import jakarta.jms.Message;

public class ProtocolRequest extends MessageBasedRequest {
    public static final String VERSION_PROP = "JMS_SWIFTMQ_FT_VERSION";
    int version = 0;

    public ProtocolRequest(int version) {
        this.version = version;
        setReplyRequired(true);
    }

    public ProtocolRequest(Message message) throws JMSException {
        this(message.getIntProperty(VERSION_PROP));
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public MessageBasedReply createReplyInstance() {
        return new ProtocolReply();
    }

    public void accept(MessageBasedRequestVisitor visitor) {
        ((ProtocolVisitor) visitor).visit(this);
    }

    public Message toMessage() throws JMSException {
        Message message = new MessageImpl();
        message.setIntProperty(ProtocolFactory.DUMPID_PROP, ProtocolFactory.PROTOCOL_REQ);
        message.setIntProperty(VERSION_PROP, version);
        return message;
    }

    public String toString() {
        return "[ProtocolRequest, version=" + version + "]";
    }
}
