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
import io.ballerina.runtime.api.Runtime;
import io.ballerina.runtime.api.creators.ValueCreator;
import io.ballerina.runtime.api.values.BError;
import io.ballerina.runtime.api.values.BObject;
import io.ballerina.stdlib.mi.executor.ParamHandler;
import org.apache.synapse.MessageContext;
import org.apache.synapse.SynapseConstants;
import org.apache.synapse.mediators.template.TemplateContext;
import org.wso2.integration.connector.core.AbstractConnector;
import org.wso2.integration.connector.core.ConnectException;
import org.wso2.integration.connector.core.connection.ConnectionHandler;

import java.util.Stack;

public class BalConnectorConfig extends AbstractConnector {
    private static volatile Runtime rt = null;
    private static Module module = null;
    private String orgName;
    private String moduleName;
    private String version;
    private final ParamHandler paramHandler = new ParamHandler();

    public BalConnectorConfig() {

    }

    // This constructor is added to test the connector
    public BalConnectorConfig(ModuleInfo moduleInfo) {
        this.orgName = moduleInfo.getOrgName();
        this.moduleName = moduleInfo.getModuleName();
        this.version = moduleInfo.getModuleVersion();
        init();
    }

    @Override
    public void connect(MessageContext messageContext) throws ConnectException {
        // Connection metadata is resolved from template parameters and message properties.
        // A distinct connection entry is created per connector/module and connection name.
        if (rt == null) {
            synchronized (BalConnectorConfig.class) {
                if (rt == null) {
                    init();
                }
            }
        }
        String connectorName = module.getName();
        String connectionName = lookupTemplateParamater(messageContext, "name");
        String connectionType = lookupTemplateParamater(messageContext, "connectionType");
        BObject clientObject = null;
        ConnectionHandler handler = ConnectionHandler.getConnectionHandler();
        if (!handler.checkIfConnectionExists(connectorName, connectionName)) {
            try {

                // Using json string to create a record value
//                BString jsonString = StringUtils.fromString("");
//                BMap<BString, Object> recValue = ValueCreator.createRecordValue(module, "ConnectionConfig");
////                TypeCreator.createRecordType(module, StringUtils.fromString("https://disease.sh"))
//                Type type = recValue.getType();
//                Object o = FromJsonStringWithType.fromJsonStringWithType(jsonString, ValueCreator.createTypedescValue(type));

                String paramSizeName = connectionType + "_" + Constants.SIZE;
                String paramSize = getPropertyAsString(messageContext, paramSizeName);
                if (paramSize == null) {
                    throw new ConnectException("Required property '" + paramSizeName + "' is missing in message context");
                }
                
                int paramCount;
                try {
                    paramCount = Integer.parseInt(paramSize);
                } catch (NumberFormatException e) {
                    throw new ConnectException("Invalid value for property '" + paramSizeName + "': expected an integer but got '" + paramSize + "'");
                }
                Object[] args = new Object[paramCount];
                setParameters(args, messageContext, connectionType);
                
                String objectTypeNameKey = connectionType + "_objectTypeName";
                String objectTypeName = getPropertyAsString(messageContext, objectTypeNameKey);
                if (objectTypeName == null) {
                    throw new ConnectException("Required property '" + objectTypeNameKey + "' is missing in message context");
                }
                
                clientObject = ValueCreator.createObjectValue(module, objectTypeName, args);
            } catch (BError clientError) {
                messageContext.setProperty(SynapseConstants.ERROR_CODE, "BALLERINA_CLIENT_ERROR");
                messageContext.setProperty(SynapseConstants.ERROR_MESSAGE, clientError.getMessage());
                messageContext.setProperty(SynapseConstants.ERROR_DETAIL, clientError.toString());
                messageContext.setProperty(SynapseConstants.ERROR_EXCEPTION, clientError);
                throw new ConnectException(clientError, clientError.getMessage());
            }
            BalConnectorConnection balConnection = new BalConnectorConnection(module, getPropertyAsString(messageContext, connectionType + "_objectTypeName"), clientObject);
            try {
                handler.createConnection(connectorName, connectionName, balConnection, messageContext);
            } catch (NoSuchMethodError e) {
                handler.createConnection(connectorName, connectionName, balConnection);
            }
        }
        messageContext.setProperty("connectionName", connectionName);
    }
    
    private String getPropertyAsString(MessageContext context, String key) {
        Object property = context.getProperty(key);
        return property != null ? property.toString() : null;
    }

    private void init() {
        module = new Module(orgName, moduleName, version);
        rt = Runtime.from(module);
        rt.init();
        rt.start();
    }

    public static Runtime getRuntime() {
        return rt;
    }

    public static Module getModule() {
        return module;
    }

    public static String lookupTemplateParamater(MessageContext ctxt, String paramName) throws ConnectException {
        Stack<TemplateContext> funcStack = (Stack) ctxt.getProperty(Constants.SYNAPSE_FUNCTION_STACK);
        TemplateContext currentFuncHolder = funcStack.peek();
        Object value = currentFuncHolder.getParameterValue(paramName);
        if (value == null) {
            throw new ConnectException("Required template parameter '" + paramName + "' is missing");
        }
        return value.toString();
    }

    private void setParameters(Object[] args, MessageContext context, String connectionType) {
        for (int i = 0; i < args.length; i++) {
            Object param = paramHandler.getParameter(context, connectionType + "_param" + i, connectionType + "_paramType" + i, i);
            // Null is preserved for optional/nil-capable parameters and validated by object construction.
            args[i] = param;
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
