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

import io.ballerina.runtime.api.values.BObject;
import io.ballerina.stdlib.mi.executor.BalExecutor;
import org.apache.axis2.AxisFault;
import org.apache.synapse.MessageContext;
import org.apache.synapse.SynapseConstants;
import org.wso2.integration.connector.core.AbstractConnector;
import org.wso2.integration.connector.core.ConnectException;
import org.wso2.integration.connector.core.connection.ConnectionHandler;

public class BalConnectorFunction extends AbstractConnector {

    private String orgName;
    private String moduleName;
    private String version;
    private final BalExecutor balExecutor = new BalExecutor();

    @Override
    public void connect(MessageContext messageContext) throws ConnectException {
        String connectorName = BalConnectorConfig.getModule().getName();
        ConnectionHandler handler = ConnectionHandler.getConnectionHandler();
        BalConnectorConnection balConnection = (BalConnectorConnection) handler.getConnection(connectorName, messageContext.getProperty("connectionName").toString());
        BObject clientObj = balConnection.getBalConnectorObj();

        if (clientObj == null) {
            throw new ConnectException("No connection found for " + connectorName);
        }
        try {
            balExecutor.execute(BalConnectorConfig.getRuntime(), clientObj, messageContext);
        } catch (AxisFault | BallerinaExecutionException e) {
            messageContext.setProperty(SynapseConstants.ERROR_CODE, "BALLERINA_EXECUTION_ERROR");
            messageContext.setProperty(SynapseConstants.ERROR_MESSAGE, e.getMessage());
            messageContext.setProperty(SynapseConstants.ERROR_DETAIL, e.getCause() != null ? e.getCause().toString() : e.toString());
            messageContext.setProperty(SynapseConstants.ERROR_EXCEPTION, e);
            throw new ConnectException(e, e.getMessage());
        }
    }

    public String getOrgName() {
        return orgName;
    }

    public void setOrgName(String orgName) {
        this.orgName = orgName;
    }

    public String getModuleName() {
        return moduleName;
    }

    public void setModuleName(String moduleName) {
        this.moduleName = moduleName;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }
}
