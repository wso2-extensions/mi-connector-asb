/*
 * Copyright (c) 2026, WSO2 LLC. (https://www.wso2.com).
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

import com.google.gson.JsonElement;
import org.apache.axiom.om.OMAbstractFactory;
import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.OMFactory;
import org.apache.axiom.soap.SOAP11Constants;
import org.apache.axiom.soap.SOAP12Constants;
import org.apache.axiom.soap.SOAPEnvelope;
import org.apache.axis2.AxisFault;
import org.apache.synapse.MessageContext;
import org.apache.synapse.commons.json.JsonUtil;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.util.AXIOMUtils;

import javax.xml.namespace.QName;

public class PayloadWriter {

    private static final QName TEXT_ELEMENT = new QName("http://ws.apache.org/commons/ns/payload", "text");
    private static final String XML_CONTENT_TYPE = "application/xml";
    private static final String JSON_CONTENT_TYPE = "application/json";
    private static final String TEXT_CONTENT_TYPE = "text/plain";
    private static final String MESSAGE_TYPE = "messageType";
    private static final String CONTENT_TYPE = "contentType";

    public static void overwriteBody(MessageContext messageContext, Object payload) throws AxisFault {
        org.apache.axis2.context.MessageContext axis2MessageContext = ((Axis2MessageContext) messageContext).getAxis2MessageContext();

        if (payload == null) {
            // Clear the body when result is null (void function)
            JsonUtil.removeJsonPayload(axis2MessageContext);
            SOAPEnvelope envelope = axis2MessageContext.getEnvelope();
            if (envelope != null && envelope.getBody() != null) {
                // Remove all children from the body
                OMElement body = envelope.getBody();
                while (body.getFirstElement() != null) {
                    body.getFirstElement().detach();
                }
            }
            axis2MessageContext.setProperty("NO_ENTITY_BODY", Boolean.TRUE);
            return;
        }
        if (payload instanceof OMElement) {
            OMElement omElement = (OMElement) payload;
            JsonUtil.removeJsonPayload(axis2MessageContext);
            if (!checkAndReplaceEnvelope(omElement, messageContext)) {
                axis2MessageContext.getEnvelope().getBody().addChild(omElement);
            }
            setContentType(axis2MessageContext, XML_CONTENT_TYPE);
        } else if (payload instanceof JsonElement) {
            JsonElement jsonElement = (JsonElement) payload;
            JsonUtil.getNewJsonPayload(axis2MessageContext, jsonElement.toString(), true, true);
            setContentType(axis2MessageContext, JSON_CONTENT_TYPE);
        } else if (payload instanceof String) {
            String s = (String) payload;
            JsonUtil.removeJsonPayload(axis2MessageContext);
            axis2MessageContext.getEnvelope().getBody().addChild(getTextElement(s));
            setContentType(axis2MessageContext, TEXT_CONTENT_TYPE);
        } else {
            throw new AxisFault("Unsupported payload type: " + payload.getClass().getName());
        }
        axis2MessageContext.removeProperty("NO_ENTITY_BODY");
    }

    private static OMElement getTextElement(String content) {
        OMFactory factory = OMAbstractFactory.getOMFactory();
        OMElement textElement = factory.createOMElement(TEXT_ELEMENT);
        if (content == null) {
            content = "";
        }
        textElement.setText(content);
        return textElement;
    }

    private static boolean checkAndReplaceEnvelope(OMElement resultElement, MessageContext synCtx) throws AxisFault {
        OMElement firstChild = resultElement.getFirstElement();

        if (firstChild == null) {
            throw new AxisFault("Generated content is not a valid XML payload");
        }

        QName resultQName = firstChild.getQName();
        if (resultQName.getLocalPart().equals("Envelope") && (
                resultQName.getNamespaceURI().equals(SOAP11Constants.SOAP_ENVELOPE_NAMESPACE_URI) ||
                        resultQName.getNamespaceURI().
                                equals(SOAP12Constants.SOAP_ENVELOPE_NAMESPACE_URI))) {
            SOAPEnvelope soapEnvelope = AXIOMUtils.getSOAPEnvFromOM(resultElement.getFirstElement());
            if (soapEnvelope != null) {
                soapEnvelope.buildWithAttachments();
                synCtx.setEnvelope(soapEnvelope);
            }
        } else {
            return false;
        }
        return true;
    }

    private static void setContentType(org.apache.axis2.context.MessageContext axis2MessageContext, String contentType) {
        axis2MessageContext.setProperty(MESSAGE_TYPE, contentType);
        axis2MessageContext.setProperty(CONTENT_TYPE, contentType);
    }
}
