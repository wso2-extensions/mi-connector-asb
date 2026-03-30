/*
 * Copyright (c) 2025, WSO2 LLC. (https://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
 
package io.ballerina.stdlib.mi;

import io.ballerina.runtime.api.Module;
import io.ballerina.runtime.api.values.BObject;
import org.wso2.integration.connector.core.ConnectException;
import org.wso2.integration.connector.core.connection.Connection;
import org.wso2.integration.connector.core.connection.ConnectionConfig;

public class BalConnectorConnection implements Connection {
    private final BObject balConnectorObj;

    public BalConnectorConnection(Module module, String objectTypeName, BObject clientObj) {
        this.balConnectorObj = clientObj;
    }

    @Override
    public void connect(ConnectionConfig connectionConfig) throws ConnectException {
        // No-op: the Ballerina client object is constructed during connection creation.
    }

    @Override
    public void close() throws ConnectException {
        // No-op: managed runtime does not require explicit connector-level teardown here.
    }

    public BObject getBalConnectorObj() {
        return balConnectorObj;
    }
}
