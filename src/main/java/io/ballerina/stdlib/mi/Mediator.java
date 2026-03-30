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
import io.ballerina.stdlib.mi.executor.BalExecutor;
import org.apache.axis2.AxisFault;
import org.apache.synapse.MessageContext;
import org.apache.synapse.SynapseConstants;
import org.apache.synapse.SynapseException;
import org.apache.synapse.mediators.AbstractMediator;

public class Mediator extends AbstractMediator {

    private static volatile Runtime rt = null;
    private static Module module = null;
    private String orgName;
    private String moduleName;
    private String version;
    private final BalExecutor balExecutor = new BalExecutor();

    public Mediator() {
    }

    // This constructor is added to test the mediator
    public Mediator(ModuleInfo moduleInfo) {
        this.orgName = moduleInfo.getOrgName();
        this.moduleName = moduleInfo.getModuleName();
        this.version = moduleInfo.getModuleVersion();
        init();
    }

    public boolean mediate(MessageContext context) {

        if (rt == null) {
            synchronized (Mediator.class) {
                if (rt == null) {
                    init();
                }
            }
        }
        try {
            return balExecutor.execute(rt, module, context);
        } catch (AxisFault | BallerinaExecutionException e) {
            context.setProperty(SynapseConstants.ERROR_CODE, "BALLERINA_EXECUTION_ERROR");
            context.setProperty(SynapseConstants.ERROR_MESSAGE, e.getMessage());
            context.setProperty(SynapseConstants.ERROR_DETAIL, e.getCause() != null ? e.getCause().toString() : e.toString());
            context.setProperty(SynapseConstants.ERROR_EXCEPTION, e);
            throw new SynapseException(e.getMessage(), e);
        }
    }

    private void init() {
        module = new Module(orgName, moduleName, version);
        rt = Runtime.from(module);
        rt.init();
        rt.start();
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
