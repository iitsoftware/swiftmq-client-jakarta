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

import jakarta.jms.JMSException;
import jakarta.jms.QueueSession;
import jakarta.jms.XAQueueSession;

public class XAQueueSessionImpl extends XASessionImpl
        implements XAQueueSession {
    XAQueueSessionImpl(SessionImpl session) {
        super(session);
    }

    public QueueSession getQueueSession() throws JMSException {
        return (QueueSession) session;
    }
}
