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

import com.swiftmq.net.client.Reconnector;

import jakarta.jms.JMSException;
import jakarta.jms.XAConnection;
import jakarta.jms.XASession;

public class XAConnectionImpl extends ConnectionImpl implements XAConnection {
    public XAConnectionImpl(String userName, String password, Reconnector reconnector) throws JMSException {
        super(userName, password, reconnector);
    }

    public XASession createXASession() throws JMSException {
        return new XASessionImpl((SessionImpl) createSession(true, 0));
    }
}
