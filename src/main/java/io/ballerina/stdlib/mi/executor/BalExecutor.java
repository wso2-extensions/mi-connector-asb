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

package io.ballerina.stdlib.mi.executor;

import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.apache.axiom.om.OMAbstractFactory;
import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.OMFactory;
import io.ballerina.lib.asb.util.ASBConstants;
import io.ballerina.runtime.api.Module;
import io.ballerina.runtime.api.PredefinedTypes;
import io.ballerina.runtime.api.Runtime;
import io.ballerina.runtime.api.async.Callback;
import io.ballerina.runtime.api.async.StrandMetadata;
import io.ballerina.runtime.api.creators.TypeCreator;
import io.ballerina.runtime.api.creators.ValueCreator;
import io.ballerina.runtime.api.flags.SymbolFlags;
import io.ballerina.runtime.api.types.Field;
import io.ballerina.runtime.api.types.Type;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.*;
import io.ballerina.stdlib.mi.*;
import io.ballerina.stdlib.mi.utils.SynapseUtils;
import org.apache.axis2.AxisFault;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.MessageContext;
import org.apache.synapse.SynapseException;
import org.apache.synapse.data.connector.ConnectorResponse;
import org.apache.synapse.data.connector.DefaultConnectorResponse;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.ballerina.stdlib.mi.Constants.FUNCTION_NAME;

public class BalExecutor {

    private static final List<String> PERSIST_NEEDED_FUNCTIONS = List.of("receive", "receiveBatch", "receiveDeferred");
    private static final List<String> MESSAGE_SETTLEMENT_FUNCTIONS = List.of("abandon","complete", "renewLock", "defer", "deadLetter");
    protected Log log = LogFactory.getLog(BalExecutor.class);
    private final ParamHandler paramHandler = new ParamHandler();
    private static final long TIMEOUT_SECONDS = 300; // 5 minutes timeout

    /**
     * Synchronous callback implementation that blocks until completion.
     */
    private static class SyncCallback implements Callback {
        private final CountDownLatch latch = new CountDownLatch(1);
        private final AtomicReference<Object> result = new AtomicReference<>();
        private final AtomicReference<BError> error = new AtomicReference<>();

        @Override
        public void notifySuccess(Object result) {
            this.result.set(result);
            latch.countDown();
        }

        @Override
        public void notifyFailure(BError error) {
            this.error.set(error);
            latch.countDown();
        }

        public Object waitForResult(long timeoutSeconds) throws BallerinaExecutionException {
            try {
                if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
                    throw new BallerinaExecutionException("Function invocation timed out after " + timeoutSeconds + " seconds",
                            new Exception("Timeout"));
                }
                BError err = error.get();
                if (err != null) {
                    throw new BallerinaExecutionException(err.getMessage(), err);
                }
                return result.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BallerinaExecutionException("Function invocation interrupted", e);
            }
        }
    }

    /**
     * Converts args to interleaved format required by Ballerina runtime.
     * Format: [arg0, true, arg1, true, ...] where true indicates argument is provided.
     */
    private Object[] toInterleavedArgs(Object[] args) {
        Object[] interleaved = new Object[args.length * 2];
        for (int i = 0; i < args.length; i++) {
            interleaved[i * 2] = args[i];
            interleaved[i * 2 + 1] = true;
        }
        return interleaved;
    }

    /**
     * Invokes a method on a BObject using the async API and waits for completion.
     * If returnType is non-null, it is passed to the runtime for type-guided binding.
     */
    private Object invokeMethodSync(Runtime rt, BObject bObject, String methodName, Object[] args, Type returnType)
            throws BallerinaExecutionException {
        SyncCallback callback = new SyncCallback();
        StrandMetadata metadata = new StrandMetadata(
                BalConnectorConfig.getModule().getOrg(),
                BalConnectorConfig.getModule().getName(),
                BalConnectorConfig.getModule().getMajorVersion(),
                methodName
        );

        Object[] interleavedArgs = toInterleavedArgs(args);
        if (returnType != null) {
            rt.invokeMethodAsync(bObject, methodName, null, metadata, callback, null, returnType, interleavedArgs);
        } else {
            rt.invokeMethodAsync(bObject, methodName, null, metadata, callback, interleavedArgs);
        }
        return callback.waitForResult(TIMEOUT_SECONDS);
    }

    private Type buildMessageReturnType(String returnTypeName) {
        Module module = BalConnectorConfig.getModule();

        Type bodyType = switch (returnTypeName) {
            case Constants.JSON -> PredefinedTypes.TYPE_JSON;
            case Constants.TEXT -> PredefinedTypes.TYPE_STRING;
            case Constants.XML -> PredefinedTypes.TYPE_XML;
            default -> PredefinedTypes.TYPE_ANYDATA;
        };

        Map<String, Field> appPropFields = new LinkedHashMap<>();
        appPropFields.put(ASBConstants.PROPERTIES, TypeCreator.createField(
                TypeCreator.createMapType(PredefinedTypes.TYPE_ANYDATA),
                ASBConstants.PROPERTIES,
                SymbolFlags.PUBLIC | SymbolFlags.OPTIONAL));
        Type appPropType = TypeCreator.createRecordType(
                ASBConstants.APPLICATION_PROPERTY_TYPE, module, SymbolFlags.PUBLIC, appPropFields, null, true, 0);

        long pub = SymbolFlags.PUBLIC;
        long pubOpt = SymbolFlags.PUBLIC | SymbolFlags.OPTIONAL;
        long pubOptRo = SymbolFlags.PUBLIC | SymbolFlags.OPTIONAL | SymbolFlags.READONLY;

        Map<String, Field> fields = new LinkedHashMap<>();
        fields.put(ASBConstants.BODY, TypeCreator.createField(bodyType, ASBConstants.BODY, pub));
        fields.put(ASBConstants.CONTENT_TYPE, TypeCreator.createField(PredefinedTypes.TYPE_STRING, ASBConstants.CONTENT_TYPE, pub));
        fields.put(ASBConstants.MESSAGE_ID, TypeCreator.createField(PredefinedTypes.TYPE_STRING, ASBConstants.MESSAGE_ID, pubOpt));
        fields.put(ASBConstants.TO, TypeCreator.createField(PredefinedTypes.TYPE_STRING, ASBConstants.TO, pubOpt));
        fields.put(ASBConstants.REPLY_TO, TypeCreator.createField(PredefinedTypes.TYPE_STRING, ASBConstants.REPLY_TO, pubOpt));
        fields.put(ASBConstants.REPLY_TO_SESSION_ID, TypeCreator.createField(PredefinedTypes.TYPE_STRING, ASBConstants.REPLY_TO_SESSION_ID, pubOpt));
        fields.put(ASBConstants.LABEL, TypeCreator.createField(PredefinedTypes.TYPE_STRING, ASBConstants.LABEL, pubOpt));
        fields.put(ASBConstants.SESSION_ID, TypeCreator.createField(PredefinedTypes.TYPE_STRING, ASBConstants.SESSION_ID, pubOpt));
        fields.put(ASBConstants.CORRELATION_ID, TypeCreator.createField(PredefinedTypes.TYPE_STRING, ASBConstants.CORRELATION_ID, pubOpt));
        fields.put(ASBConstants.PARTITION_KEY, TypeCreator.createField(PredefinedTypes.TYPE_STRING, ASBConstants.PARTITION_KEY, pubOpt));
        fields.put(ASBConstants.TIME_TO_LIVE, TypeCreator.createField(PredefinedTypes.TYPE_INT, ASBConstants.TIME_TO_LIVE, pubOpt));
        fields.put(ASBConstants.SEQUENCE_NUMBER, TypeCreator.createField(PredefinedTypes.TYPE_INT, ASBConstants.SEQUENCE_NUMBER, pubOptRo));
        fields.put(ASBConstants.LOCK_TOKEN, TypeCreator.createField(PredefinedTypes.TYPE_STRING, ASBConstants.LOCK_TOKEN, pubOptRo));
        fields.put(ASBConstants.APPLICATION_PROPERTY_KEY, TypeCreator.createField(appPropType, ASBConstants.APPLICATION_PROPERTY_KEY, pubOpt));
        fields.put(ASBConstants.DELIVERY_COUNT, TypeCreator.createField(PredefinedTypes.TYPE_INT, ASBConstants.DELIVERY_COUNT, pubOpt));
        fields.put(ASBConstants.ENQUEUED_TIME, TypeCreator.createField(PredefinedTypes.TYPE_STRING, ASBConstants.ENQUEUED_TIME, pubOpt));
        fields.put(ASBConstants.ENQUEUED_SEQUENCE_NUMBER, TypeCreator.createField(PredefinedTypes.TYPE_INT, ASBConstants.ENQUEUED_SEQUENCE_NUMBER, pubOpt));
        fields.put(ASBConstants.DEAD_LETTER_ERROR_DESCRIPTION, TypeCreator.createField(PredefinedTypes.TYPE_STRING, ASBConstants.DEAD_LETTER_ERROR_DESCRIPTION, pubOpt));
        fields.put(ASBConstants.DEAD_LETTER_REASON, TypeCreator.createField(PredefinedTypes.TYPE_STRING, ASBConstants.DEAD_LETTER_REASON, pubOpt));
        fields.put(ASBConstants.DEAD_LETTER_SOURCE, TypeCreator.createField(PredefinedTypes.TYPE_STRING, ASBConstants.DEAD_LETTER_SOURCE, pubOpt));
        fields.put(ASBConstants.STATE, TypeCreator.createField(PredefinedTypes.TYPE_STRING, ASBConstants.STATE, pubOpt));

        return TypeCreator.createRecordType(
                ASBConstants.MESSAGE_RECORD, module, SymbolFlags.PUBLIC, fields, null, true, 0);
    }

    /**
     * Invokes a module-level function using the async API and waits for completion.
     * Note: Module-level functions don't need interleaved args, only object methods do.
     */
    private Object invokeFunctionSync(Runtime rt, String functionName, Object[] args)
            throws BallerinaExecutionException {
        SyncCallback callback = new SyncCallback();
        rt.invokeMethodAsync(functionName, callback, args);
        return callback.waitForResult(TIMEOUT_SECONDS);
    }

    public boolean execute(Runtime rt, Object callable, MessageContext context) throws AxisFault, BallerinaExecutionException {
        String paramSize = SynapseUtils.getPropertyAsString(context, Constants.SIZE);
        int size = 0;
        if (paramSize != null && !paramSize.isEmpty()) {
            try {
                size = Integer.parseInt(paramSize);
            } catch (NumberFormatException e) {
                throw new SynapseException("Invalid value for property '" + Constants.SIZE + "': " + paramSize, e);
            }
        }
        Object[] args = new Object[size];
        paramHandler.setParameters(args, context, callable);

        try {
            Object result;
            String responseBodyType = null;
            if (callable instanceof Module) {
                String functionName = SynapseUtils.getPropertyAsString(context, Constants.FUNCTION_NAME);
                result = invokeFunctionSync(rt, functionName, args);
            } else if (callable instanceof BObject bObject) {
                String functionType = SynapseUtils.getPropertyAsString(context, Constants.FUNCTION_TYPE);
                if (Constants.FUNCTION_TYPE_RESOURCE.equals(functionType)) {
                    String jvmMethodName = SynapseUtils.getPropertyAsString(context, Constants.JVM_METHOD_NAME);
                    if (jvmMethodName != null) {
                        jvmMethodName = jvmMethodName.replace("$$", "$^");
                    }
                    if (jvmMethodName == null || jvmMethodName.isEmpty()) {
                        jvmMethodName = SynapseUtils.getPropertyAsString(context, FUNCTION_NAME);
                    }
                    if (jvmMethodName == null || jvmMethodName.isEmpty()) {
                        throw new SynapseException("Neither jvmMethodName nor paramFunctionName is available for resource function invocation");
                    }
                    Object[] argsWithPathParams = paramHandler.prependPathParams(args, context);
                    result = invokeMethodSync(rt, bObject, jvmMethodName, argsWithPathParams, null);
                } else {
                    String functionName = SynapseUtils.getPropertyAsString(context, Constants.FUNCTION_NAME);
                    if (MESSAGE_SETTLEMENT_FUNCTIONS.contains(functionName)) {
                        restoreNativeMessage(context, args);
                    }

                    Object returnTypeParam = SynapseUtils.lookupTemplateParameter(context, Constants.RETURN_TYPE);
                    if (returnTypeParam != null && !returnTypeParam.toString().isEmpty()) {
                        responseBodyType = returnTypeParam.toString();
                    }
                    Type returnType = responseBodyType != null ? buildMessageReturnType(responseBodyType) : null;
                    if (returnType != null) {
                        for (int i = 0; i < args.length; i++) {
                            if (Constants.TYPEDESC.equals(SynapseUtils.getPropertyAsString(context, "paramType" + i))
                                    && "T".equals(SynapseUtils.getPropertyAsString(context, "param" + i))) {
                                args[i] = ValueCreator.createTypedescValue(returnType);
                                break;
                            }
                        }
                    }
                    result = invokeMethodSync(rt, bObject, functionName, args, returnType);
                    if (PERSIST_NEEDED_FUNCTIONS.contains(functionName)){
                        persistNativeMessage(context, result);
                    }
                }
            } else {
                throw new SynapseException("Unsupported callable type: " + callable.getClass().getName());
            }

            if (result instanceof BError bError) {
                throw new BallerinaExecutionException(bError.getMessage(), bError.fillInStackTrace());
            }
            Object processedResult = processResponse(result, responseBodyType);

            ConnectorResponse connectorResponse = new DefaultConnectorResponse();
            String resultProperty = getResultProperty(context);
            boolean overwriteBody = isOverwriteBody(context);

            if (overwriteBody) {
                PayloadWriter.overwriteBody(context, processedResult);
            } else {
                connectorResponse.setPayload(processedResult);
            }
            context.setVariable(resultProperty, connectorResponse);
        } catch (BError bError) {
            log.error("BError caught during execution: " + bError.getMessage(), bError);
            throw new BallerinaExecutionException(bError.getMessage(), bError.fillInStackTrace());
        } catch (BallerinaExecutionException e) {
            log.error("Ballerina function execution failed: " + e.getMessage(), e);
            throw e;
        } catch (AxisFault | SynapseException e) {
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error during execution: " + e.getMessage(), e);
            throw new SynapseException("Error during Ballerina function execution", e);
        }
        return true;
    }

    private void restoreNativeMessage(MessageContext context, Object[] args) {
        Object stored = context.getProperty(Constants.ASB_NATIVE_MESSAGE);
        if (!(stored instanceof Map<?, ?> nativeMessageMap)) {
            throw new SynapseException("Settlement failed: no received message available in the current context. " +
                    "Ensure a receive operation was performed before attempting settlement.");
        }

        Object sequenceNumber = SynapseUtils.lookupTemplateParameter(context, "sequenceNumber");
        String key = sequenceNumber != null ? sequenceNumber.toString() : "";
        if (key.isEmpty()) {
            throw new SynapseException("Settlement failed: 'sequenceNumber' is missing or empty. " +
                    "Provide the sequence number of the received message.");
        }
        Object nativeMessage = nativeMessageMap.get(key);
        if (!(nativeMessage instanceof ServiceBusReceivedMessage receivedMessage)) {
            throw new SynapseException("Settlement failed: no active message found for sequenceNumber '" + key + "'. " +
                    "The message may have already been settled or the lock may have expired.");
        }
        BMap<BString, Object> messageRecord = constructMessageRecord(receivedMessage);
        messageRecord.addNativeData(ASBConstants.NATIVE_MESSAGE, receivedMessage);
        args[0] = messageRecord;
    }

    private BMap<BString, Object> constructMessageRecord(ServiceBusReceivedMessage message) {
        Map<String, Object> map = new HashMap<>();
        addFieldIfPresent(map, ASBConstants.CONTENT_TYPE, message.getContentType());
        addFieldIfPresent(map, ASBConstants.MESSAGE_ID, message.getMessageId());
        addFieldIfPresent(map, ASBConstants.TO, message.getTo());
        addFieldIfPresent(map, ASBConstants.REPLY_TO, message.getReplyTo());
        addFieldIfPresent(map, ASBConstants.REPLY_TO_SESSION_ID, message.getReplyToSessionId());
        addFieldIfPresent(map, ASBConstants.LABEL, message.getSubject());
        addFieldIfPresent(map, ASBConstants.SESSION_ID, message.getSessionId());
        addFieldIfPresent(map, ASBConstants.CORRELATION_ID, message.getCorrelationId());
        addFieldIfPresent(map, ASBConstants.PARTITION_KEY, message.getPartitionKey());
        addFieldIfPresent(map, ASBConstants.TIME_TO_LIVE, message.getTimeToLive().getSeconds());
        addFieldIfPresent(map, ASBConstants.SEQUENCE_NUMBER, message.getSequenceNumber());
        addFieldIfPresent(map, ASBConstants.LOCK_TOKEN, message.getLockToken());
        addFieldIfPresent(map, ASBConstants.DELIVERY_COUNT, message.getDeliveryCount());
        addFieldIfPresent(map, ASBConstants.ENQUEUED_TIME, message.getEnqueuedTime().toString());
        addFieldIfPresent(map, ASBConstants.ENQUEUED_SEQUENCE_NUMBER, message.getEnqueuedSequenceNumber());
        addFieldIfPresent(map, ASBConstants.DEAD_LETTER_ERROR_DESCRIPTION, message.getDeadLetterErrorDescription());
        addFieldIfPresent(map, ASBConstants.DEAD_LETTER_REASON, message.getDeadLetterReason());
        addFieldIfPresent(map, ASBConstants.DEAD_LETTER_SOURCE, message.getDeadLetterSource());
        addFieldIfPresent(map, ASBConstants.STATE, message.getState().toString());
        map.put(ASBConstants.BODY, StringUtils.fromString(""));
        return ValueCreator.createRecordValue(BalConnectorConfig.getModule(), ASBConstants.MESSAGE_RECORD, map);
    }

    private static void addFieldIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    @SuppressWarnings("unchecked")
    private void persistNativeMessage(MessageContext context, Object result) {
        if (result instanceof BMap<?,?>) {
            if (context.getProperty(Constants.ASB_NATIVE_MESSAGE) == null) {
                context.setProperty(Constants.ASB_NATIVE_MESSAGE, new HashMap<String, ServiceBusReceivedMessage>());
            }
            Object stored = context.getProperty(Constants.ASB_NATIVE_MESSAGE);
            if (!(stored instanceof Map<?, ?>)) {
                log.error("ASB native message store in context is of unexpected type: " +
                        (stored != null ? stored.getClass().getName() : "null") +
                        ". Cannot persist native message.");
                return;
            }
            Map<String, ServiceBusReceivedMessage> nativeMessageMap = (Map<String, ServiceBusReceivedMessage>) stored;
            BMap<BString, Object> resultMap = (BMap<BString, Object>) result;
            String typeName = resultMap.getType().getName();
            if (ASBConstants.MESSAGE_BATCH_RECORD.equals(typeName)) {
                BArray messages = (BArray) resultMap.get(ASBConstants.MESSAGES_CONTENT);
                for (int i = 0; i < messages.size(); i++) {
                    BMap<BString, Object> msg = (BMap<BString, Object>) messages.get(i);
                    ServiceBusReceivedMessage nativeMsg = getNativeMessage(msg);
                    if (nativeMsg != null) {
                        nativeMessageMap.put(String.valueOf(nativeMsg.getSequenceNumber()), nativeMsg);
                    } else {
                        log.warn("Message at index " + i + " in the received batch does not contain a native " +
                                "ServiceBusReceivedMessage. It will not be available for settlement operations.");
                    }
                }
            } else {
                ServiceBusReceivedMessage nativeMessage = getNativeMessage(resultMap);
                if (nativeMessage != null) {
                    nativeMessageMap.put(String.valueOf(nativeMessage.getSequenceNumber()), nativeMessage);
                } else {
                    log.warn("The received message does not contain a native ServiceBusReceivedMessage. " +
                            "It will not be available for settlement operations.");
                }
            }
        }
    }

    private static ServiceBusReceivedMessage getNativeMessage(BMap<BString, Object> message) {
        return (ServiceBusReceivedMessage) message.getNativeData(ASBConstants.NATIVE_MESSAGE);
    }

    private Object processResponse(Object result, String responseBodyType) {
        if (result == null) return null;
        if (result instanceof BXml) return BXmlConverter.toOMElement((BXml) result);
        if (result instanceof BDecimal) return ((BDecimal) result).value().toString();
        if (result instanceof BString) return ((BString) result).getValue();
        if (result instanceof BArray) return JsonParser.parseString(TypeConverter.arrayToJsonString((BArray) result));
        if (result instanceof BMap) {
            if (Constants.XML.equals(responseBodyType)) {
                return bMapToOMElement((BMap<?, ?>) result);
            }
            return bMapToJsonObject((BMap<?, ?>) result);
        }
        if (result instanceof Long || result instanceof Integer || result instanceof Boolean || result instanceof Double || result instanceof Float) {
            return JsonParser.parseString(result.toString());
        }
        log.warn("Unhandled result type: " + result.getClass().getSimpleName() + ", returning as-is");
        return result;
    }

    private OMElement bMapToOMElement(BMap<?, ?> bMap) {
        OMFactory factory = OMAbstractFactory.getOMFactory();
        String typeName = bMap.getType().getName();
        OMElement root = factory.createOMElement(typeName.isEmpty() ? "Message" : typeName, null);
        for (Map.Entry<?, ?> entry : bMap.entrySet()) {
            String key = entry.getKey().toString();
            Object value = entry.getValue();
            OMElement child = factory.createOMElement(key, null);
            if (value instanceof BXml bXml) {
                OMElement xmlElement = BXmlConverter.toOMElement(bXml);
                if (xmlElement != null) {
                    child.addChild(BXmlConverter.toOMElement(bXml));
                }
            } else if (value instanceof BMap) {
                child.addChild(bMapToOMElement((BMap<?, ?>) value));
            } else if (value != null) {
                child.setText(value.toString());
            }
            root.addChild(child);
        }
        return root;
    }

    private JsonObject bMapToJsonObject(BMap<?, ?> bMap) {
        JsonObject jsonObject = new JsonObject();
        for (Map.Entry<?, ?> entry : bMap.entrySet()) {
            jsonObject.add(entry.getKey().toString(), bValueToJsonElement(entry.getValue()));
        }
        return jsonObject;
    }

    private JsonElement bValueToJsonElement(Object value) {
        if (value == null) return JsonNull.INSTANCE;
        if (value instanceof BString) return new JsonPrimitive(((BString) value).getValue());
        if (value instanceof Long) return new JsonPrimitive((Long) value);
        if (value instanceof Double) return new JsonPrimitive((Double) value);
        if (value instanceof Boolean) return new JsonPrimitive((Boolean) value);
        if (value instanceof BDecimal) return new JsonPrimitive(((BDecimal) value).value());
        if (value instanceof BMap) return bMapToJsonObject((BMap<?, ?>) value);
        if (value instanceof BArray) return JsonParser.parseString(TypeConverter.arrayToJsonString((BArray) value));
        if (value instanceof BXml) return new JsonPrimitive(((BXml) value).stringValue(null));
        return new JsonPrimitive(value.toString());
    }

    private static String getResultProperty(MessageContext context) {
        return SynapseUtils.lookupTemplateParameter(context, Constants.RESPONSE_VARIABLE).toString();
    }

    private static boolean isOverwriteBody(MessageContext context) {
        return Boolean.parseBoolean((String) SynapseUtils.lookupTemplateParameter(context, Constants.OVERWRITE_BODY));
    }
}
