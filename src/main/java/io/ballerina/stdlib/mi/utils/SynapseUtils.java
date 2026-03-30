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

package io.ballerina.stdlib.mi.utils;

import io.ballerina.stdlib.mi.Constants;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.MessageContext;
import org.apache.synapse.mediators.template.TemplateContext;
import org.apache.synapse.util.xpath.SynapseExpression;

import java.util.Stack;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SynapseUtils {

    private static final Log log = LogFactory.getLog(SynapseUtils.class);
    private static final Pattern SYNAPSE_EXPRESSION_PATTERN = Pattern.compile("\\$\\{(.+?)\\}");

    /**
     * Get a property value as a String from the MessageContext.
     * Returns null if the property doesn't exist.
     */
    public static String getPropertyAsString(MessageContext context, String propertyName) {
        Object value = context.getProperty(propertyName);
        return value != null ? value.toString() : null;
    }

    public static Object lookupTemplateParameter(MessageContext ctx, String paramName) {
        Stack funcStack = (Stack) ctx.getProperty(Constants.SYNAPSE_FUNCTION_STACK);
        if (funcStack == null || funcStack.isEmpty()) {
            return ctx.getProperty(paramName);
        }
        TemplateContext currentFuncHolder = (TemplateContext) funcStack.peek();
        Object value = currentFuncHolder.getParameterValue(paramName);

        if (value == null && paramName.contains(".")) {
            java.util.Map<String, Object> params = currentFuncHolder.getMappedValues();
        }

        return value;
    }

    /**
     * Finds the connection type prefix for a given record parameter name.
     * Gets the connectionType directly from the function stack template parameters.
     */
    public static String findConnectionTypeForParam(MessageContext context, String recordParamName) {
        Object connectionType = lookupTemplateParameter(context, "connectionType");
        if (connectionType != null) {
            return connectionType.toString();
        }
        return null;
    }

    /**
     * Finds the parent union field path for a given field path.
     */
    public static String findParentUnionPath(String fieldPath, java.util.Set<String> unionPaths) {
        for (String unionPath : unionPaths) {
            if (fieldPath.startsWith(unionPath + ".")) {
                return unionPath;
            }
        }
        return null;
    }

    /**
     * Resolve all ${expression} patterns in a string using MI's SynapseExpression evaluator.
     */
    public static String resolveSynapseExpressions(String text, MessageContext context) {
        Matcher matcher = SYNAPSE_EXPRESSION_PATTERN.matcher(text);
        StringBuffer resolved = new StringBuffer();

        while (matcher.find()) {
            String expressionBody = matcher.group(1);
            String replacement;
            try {
                SynapseExpression expression = new SynapseExpression(expressionBody);
                replacement = expression.stringValueOf(context);
            } catch (Exception e) {
                log.warn("Failed to evaluate expression '${" + expressionBody + "}': " + e.getMessage()
                        + ". Using expression body as fallback.");
                replacement = expressionBody;
            }
            matcher.appendReplacement(resolved, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(resolved);

        return resolved.toString();
    }

    public static String cleanupJsonString(String json) {
        if (json == null) {
            return null;
        }
        return json.replaceAll(",\\s*([\\]\\}])", "$1");
    }
}
