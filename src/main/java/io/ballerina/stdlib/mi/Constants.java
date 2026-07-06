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

public class Constants {
    public static final String FUNCTION_NAME = "paramFunctionName";
    public static final String METHOD_NAME = "paramMethodName";
    public static final String SIZE = "paramSize";
    public static final String RESPONSE_VARIABLE = "responseVariable";
    public static final String OVERWRITE_BODY = "overwriteBody";
    public static final String RETURN_TYPE = "returnType";
    public static final String BOOLEAN = "boolean";
    public static final String INT = "int";
    public static final String STRING = "string";
    public static final String FLOAT = "float";
    public static final String DECIMAL = "decimal";
    public static final String JSON = "json";
    public static final String XML = "xml";
    public static final String TEXT = "text";
    public static final String UNION = "union";
    public static final String RECORD = "record";
    public static final String ARRAY = "array";
    public static final String MAP = "map";
    public static final String TYPEDESC = "typedesc";
    public static final String ANYDATA = "anydata";
    public static final String ENUM = "enum";
    public static final String SYNAPSE_FUNCTION_STACK = "_SYNAPSE_FUNCTION_STACK";

    // Resource function constants
    public static final String FUNCTION_TYPE = "functionType";
    public static final String RESOURCE_ACCESSOR = "resourceAccessor";
    public static final String PATH_PARAM_SIZE = "pathParamSize";
    public static final String JVM_METHOD_NAME = "jvmMethodName";
    public static final String FUNCTION_TYPE_RESOURCE = "RESOURCE";
    public static final String FUNCTION_TYPE_REMOTE = "REMOTE";
    public static final String FUNCTION_TYPE_FUNCTION = "FUNCTION";

    public static final String ASB_NATIVE_MESSAGE = "_NATIVE_ASB_MESSAGE";

    // Message-context property holding the CountDownLatch the listener blocks on.
    public static final String ASB_INBOUND_SETTLEMENT_LATCH = "_ASB_INBOUND_SETTLEMENT_LATCH";

    // Message-context property holding an AtomicReference<Map<String,String>> decision holder.
    public static final String ASB_INBOUND_SETTLEMENT_DECISION = "_ASB_INBOUND_SETTLEMENT_DECISION";

    // Keys within the decision map carried by the AtomicReference holder.
    public static final String DECISION_KEY_ACTION = "action";
    public static final String DECISION_KEY_DEAD_LETTER_REASON = "deadLetterReason";
    public static final String DECISION_KEY_DEAD_LETTER_ERROR_DESCRIPTION = "deadLetterErrorDescription";

    // Settlement action values stored under DECISION_KEY_ACTION.
    public static final String SETTLEMENT_ACTION_COMPLETE = "complete";
    public static final String SETTLEMENT_ACTION_ABANDON = "abandon";
    public static final String SETTLEMENT_ACTION_DEFER = "defer";
    public static final String SETTLEMENT_ACTION_DEAD_LETTER = "deadLetter";
}
