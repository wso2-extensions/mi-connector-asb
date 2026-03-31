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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import io.ballerina.runtime.api.Module;
import io.ballerina.runtime.api.TypeTags;
import io.ballerina.runtime.api.creators.ValueCreator;
import io.ballerina.runtime.api.types.*;
import io.ballerina.runtime.api.types.MethodType;
import io.ballerina.runtime.api.types.ObjectType;
import io.ballerina.runtime.api.types.Parameter;
import io.ballerina.runtime.api.utils.JsonUtils;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BArray;
import io.ballerina.runtime.api.values.BError;
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BObject;
import io.ballerina.runtime.api.values.BString;
import io.ballerina.runtime.api.values.BTypedesc;
import io.ballerina.stdlib.mi.BalConnectorConfig;
import io.ballerina.stdlib.mi.utils.SynapseUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.MessageContext;
import org.apache.synapse.SynapseException;
import org.ballerinalang.langlib.value.FromJsonStringWithType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.ballerina.stdlib.mi.Constants.*;

public class DataTransformer {

    private static final Log log = LogFactory.getLog(DataTransformer.class);
    private static final Pattern SYNAPSE_EXPRESSION_PATTERN = Pattern.compile("\\$\\{(.+?)\\}");

    public static Object convertValueToType(Object sourceValue, Type targetType) {
        if (sourceValue == null) {
            return null;
        }
        if (targetType.getTag() == TypeTags.RECORD_TYPE_TAG && sourceValue instanceof BMap) {
            return createTypedRecordFromGeneric((BMap<BString, Object>) sourceValue, (StructureType) targetType);
        }
        if (targetType.getTag() == TypeTags.ARRAY_TAG && sourceValue instanceof BArray) {
            return createTypedArrayFromGeneric((BArray) sourceValue, (ArrayType) targetType);
        }
        // Handle decimal type conversion - JSON parsing may produce Long/Double instead of BDecimal
        if (targetType.getTag() == TypeTags.DECIMAL_TAG) {
            if (sourceValue instanceof Number) {
                return ValueCreator.createDecimalValue(new java.math.BigDecimal(sourceValue.toString()));
            }
            if (sourceValue instanceof BString) {
                return ValueCreator.createDecimalValue(new java.math.BigDecimal(((BString) sourceValue).getValue()));
            }
        }
        return sourceValue;
    }

    public static BMap<BString, Object> createTypedRecordFromGeneric(BMap<BString, Object> genericMap, StructureType targetType) {
        BMap<BString, Object> typedRecord = ValueCreator.createRecordValue(targetType.getPackage(), targetType.getName());
        for (Field field : targetType.getFields().values()) {
            String fieldName = field.getFieldName();
            BString bFieldName = StringUtils.fromString(fieldName);
            if (genericMap.containsKey(bFieldName)) {
                Object genericValue = genericMap.get(bFieldName);
                Object convertedValue = convertValueToType(genericValue, field.getFieldType());
                typedRecord.put(bFieldName, convertedValue);
            }
        }
        return typedRecord;
    }

    public static BArray createTypedArrayFromGeneric(BArray genericArray, ArrayType targetType) {
        long size = genericArray.size();
        for (long i = 0; i < size; i++) {
            Object value = genericArray.get(i);
            Object converted = convertValueToType(value, targetType.getElementType());
            if (value != converted) {
                try {
                    genericArray.add(i, converted);
                } catch (Exception e) {
                    log.warn("Failed to update array element at index " + i + ": " + e.getMessage());
                }
            }
        }
        return genericArray;
    }

    /**
     * Gets the expected parameter type for a method from a BObject.
     * This allows us to convert generic BMaps to the correct typed records at runtime.
     */
    public static Type getMethodParameterType(BObject bObject, String methodName, int paramIndex) {
        try {
            Type type = bObject.getOriginalType();
            if (type instanceof ObjectType objectType) {
                for (MethodType method : objectType.getMethods()) {
                    if (method.getName().equals(methodName)) {
                        Parameter[] params = method.getParameters();
                        if (paramIndex < params.length) {
                            return params[paramIndex].type;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to get method parameter type for " + methodName + "[" + paramIndex + "]: " + e.getMessage());
        }
        return null;
    }

    /**
     * Converts a generic BMap to a typed record using the expected type from a method parameter.
     * This is used when we can't create a typed record via ValueCreator (e.g., for external module types).
     */
    public static Object convertToExpectedType(Object value, BObject bObject, String methodName, int paramIndex) {
        if (!(value instanceof BMap)) {
            return value;
        }

        Type expectedType = getMethodParameterType(bObject, methodName, paramIndex);
        if (expectedType == null) {
            log.info("Could not determine expected type for " + methodName + "[" + paramIndex + "], using value as-is");
            return value;
        }

        try {
            // If expected type is a record type, convert using FromJsonStringWithType
            if (expectedType.getTag() == TypeTags.RECORD_TYPE_TAG) {
                if (!(value instanceof BMap)) {
                    return value;
                }
                BMap<?, ?> bMap = (BMap<?, ?>) value;
                String jsonStr;
                try {
                    jsonStr = bMap.stringValue(null);
                } catch (Exception e) {
                    jsonStr = null;
                }
                if (jsonStr == null || jsonStr.isEmpty()) {
                    jsonStr = value.toString();
                }
                BString jsonBString = StringUtils.fromString(jsonStr);
                BTypedesc typedesc = ValueCreator.createTypedescValue(expectedType);
                Object result = FromJsonStringWithType.fromJsonStringWithType(jsonBString, typedesc);
                if (result instanceof BError) {
                    return value;
                }
                return result;
            }
        } catch (Exception e) {
            log.warn("Failed to convert to expected type " + expectedType.getName() + ": " + e.getMessage());
        }

        return value;
    }

    public static Object createRecordValue(String jsonString, String paramName, MessageContext context, int paramIndex) {
        if (jsonString == null) {
            String recordParamName = paramName;
            String connectionType = SynapseUtils.findConnectionTypeForParam(context, recordParamName);
            String propertyPrefix;
            String recordNamePropertyKey;
            String recordOrgPropertyKey;
            String recordModulePropertyKey;
            String recordVersionPropertyKey;
            if (connectionType != null) {
                propertyPrefix = connectionType + "_" + recordParamName;
                recordNamePropertyKey = connectionType + "_param" + paramIndex + "_recordName";
                recordOrgPropertyKey = connectionType + "_param" + paramIndex + "_recordOrg";
                recordModulePropertyKey = connectionType + "_param" + paramIndex + "_recordModule";
                recordVersionPropertyKey = connectionType + "_param" + paramIndex + "_recordVersion";
            } else {
                propertyPrefix = recordParamName;
                recordNamePropertyKey = "param" + paramIndex + "_recordName";
                recordOrgPropertyKey = "param" + paramIndex + "_recordOrg";
                recordModulePropertyKey = "param" + paramIndex + "_recordModule";
                recordVersionPropertyKey = "param" + paramIndex + "_recordVersion";
            }

            Object recordNameObj = context.getProperty(recordNamePropertyKey);
            String recordName = recordNameObj != null ? recordNameObj.toString() : null;
            Module recordModule = getRecordModule(context, recordOrgPropertyKey, recordModulePropertyKey, recordVersionPropertyKey);

            // First, try to create a typed record to see if it's possible
            boolean canCreateTypedRecord = false;
            Type recType = null;
            if (recordName != null) {
                try {
                    BMap<BString, Object> recValue = ValueCreator.createRecordValue(recordModule, recordName);
                    recType = recValue.getType();
                    canCreateTypedRecord = true;
                } catch (Exception e) {
                    // External module type - will use generic BMap
                }
            }

            // Reconstruct the BMap - only set defaults if we can't create a typed record
            Object reconstructedBMap = reconstructRecordFromFields(propertyPrefix, context, !canCreateTypedRecord);

            if (reconstructedBMap instanceof BError bError) {
                throw new SynapseException("Failed to reconstruct record: " + bError.getMessage());
            }

            if (recordNameObj == null) {
                // No record name - return the reconstructed map as-is
                if (reconstructedBMap instanceof BMap) {
                    return reconstructedBMap;
                }
                throw new SynapseException("Record name not found for parameter at index " + paramIndex +
                        ". Ensure '" + recordNamePropertyKey + "' property is set in the synapse template.");
            }

            // If we can create a typed record, populate it
            if (canCreateTypedRecord && recType != null && reconstructedBMap instanceof BMap) {
                BMap<?, ?> bMap = (BMap<?, ?>) reconstructedBMap;
                String jsonStr = bMap.stringValue(null);
                if (jsonStr == null || jsonStr.isEmpty()) {
                    jsonStr = reconstructedBMap.toString();
                }
                BString jsonBString = StringUtils.fromString(jsonStr);
                try {
                    return FromJsonStringWithType.fromJsonStringWithType(jsonBString, ValueCreator.createTypedescValue(recType));
                } catch (Exception e) {
                    return convertValueToType(reconstructedBMap, recType);
                }
            }

            // Return generic BMap with defaults
            if (reconstructedBMap instanceof BMap) {
                return reconstructedBMap;
            }
            throw new SynapseException("Failed to reconstruct record from flattened fields for parameter '" + paramName + "'");
        }

        if (jsonString.startsWith("'") && jsonString.endsWith("'")) {
            jsonString = jsonString.substring(1, jsonString.length() - 1);
        }

        Object recordNameObj = context.getProperty("param" + paramIndex + "_recordName");
        if (recordNameObj != null) {
            String recordName = recordNameObj.toString();
            String recordOrgPropertyKey = "param" + paramIndex + "_recordOrg";
            String recordModulePropertyKey = "param" + paramIndex + "_recordModule";
            String recordVersionPropertyKey = "param" + paramIndex + "_recordVersion";
            Module recordModule = getRecordModule(context, recordOrgPropertyKey, recordModulePropertyKey, recordVersionPropertyKey);

            try {
                BString jsonBString = StringUtils.fromString(jsonString);
                BMap<BString, Object> recValue = ValueCreator.createRecordValue(recordModule, recordName);
                Type recType = recValue.getType();
                return FromJsonStringWithType.fromJsonStringWithType(jsonBString, ValueCreator.createTypedescValue(recType));
            } catch (Exception e) {
                try {
                    Object parseResult = JsonUtils.parse(jsonString);
                    BMap<BString, Object> emptyRecord = ValueCreator.createRecordValue(recordModule, recordName);
                    Type targetType = emptyRecord.getType();
                    return convertValueToType(parseResult, targetType);
                } catch (Exception deepEx) {
                    log.error("Manual deep conversion failed: " + deepEx.getMessage(), deepEx);
                }
            }
        }

        try {
            Object parseResult = JsonUtils.parse(jsonString);
            if (parseResult instanceof BError) {
                throw new SynapseException("Failed to parse JSON for record: " + ((BError) parseResult).getMessage());
            }
            return parseResult;
        } catch (Exception e) {
            throw new SynapseException("Failed to create record value: " + e.getMessage(), e);
        }
    }

    public static Object reconstructRecordFromFields(String propertyPrefix, MessageContext context) {
        return reconstructRecordFromFields(propertyPrefix, context, true);
    }

    public static Object reconstructRecordFromFields(String propertyPrefix, MessageContext context, boolean setDefaultsForMissingFields) {
        JsonObject recordJson = new JsonObject();
        Map<String, String> unionFieldSelectedTypes = new HashMap<>();
        
        int tempIndex = 0;
        while (true) {
            String fieldNameKey = propertyPrefix + "_param" + tempIndex;
            String fieldTypeKey = propertyPrefix + "_paramType" + tempIndex;
            Object fieldNameObj = context.getProperty(fieldNameKey);
            Object fieldTypeObj = context.getProperty(fieldTypeKey);

            if (fieldNameObj == null || fieldTypeObj == null) break;

            if (UNION.equals(fieldTypeObj.toString())) {
                String dataTypeKey = propertyPrefix + "_dataType" + tempIndex;
                Object dataTypeParamNameObj = context.getProperty(dataTypeKey);
                if (dataTypeParamNameObj != null) {
                    Object selectedTypeObj = SynapseUtils.lookupTemplateParameter(context, dataTypeParamNameObj.toString());
                    if (selectedTypeObj != null) unionFieldSelectedTypes.put(fieldNameObj.toString(), selectedTypeObj.toString());
                }
            }
            tempIndex++;
        }

        int fieldIndex = 0;
        while (true) {
            String fieldNameKey = propertyPrefix + "_param" + fieldIndex;
            String fieldTypeKey = propertyPrefix + "_paramType" + fieldIndex;
            String unionMemberKey = propertyPrefix + "_unionMember" + fieldIndex;

            Object fieldNameObj = context.getProperty(fieldNameKey);
            Object fieldTypeObj = context.getProperty(fieldTypeKey);
            Object unionMemberObj = context.getProperty(unionMemberKey);

            if (fieldNameObj == null || fieldTypeObj == null) break;

            String fieldPath = fieldNameObj.toString();
            String fieldType = fieldTypeObj.toString();
            String unionMemberType = unionMemberObj != null ? unionMemberObj.toString() : null;

            if (UNION.equals(fieldType)) {
               if (unionMemberType != null) {
                    String parentUnionPath = SynapseUtils.findParentUnionPath(fieldPath, unionFieldSelectedTypes.keySet());
                    if (parentUnionPath != null) {
                        String selectedType = unionFieldSelectedTypes.get(parentUnionPath);
                        if (selectedType != null && !selectedType.equals(unionMemberType)) {
                            fieldIndex++;
                            continue;
                        }
                    }
                }
                String sanitizedFieldPath = fieldPath.replace(".", "_");
                Object unionValue = SynapseUtils.lookupTemplateParameter(context, sanitizedFieldPath);
                if (unionValue != null) {
                     setNestedField(recordJson, fieldPath, unionValue, fieldType, context, propertyPrefix, fieldIndex);
                     fieldIndex++;
                     continue;
                }
                fieldIndex++;
                continue;
            }

            if (unionMemberType != null) {
                String parentUnionPath = SynapseUtils.findParentUnionPath(fieldPath, unionFieldSelectedTypes.keySet());
                if (parentUnionPath != null) {
                    String selectedType = unionFieldSelectedTypes.get(parentUnionPath);
                    if (selectedType != null && !selectedType.equals(unionMemberType)) {
                        fieldIndex++;
                        continue;
                    }
                }
            }

            String sanitizedFieldPath = fieldPath.replace(".", "_");
            Object fieldValue = SynapseUtils.lookupTemplateParameter(context, sanitizedFieldPath);

            // For arrays with dual mode, check if JSON mode is selected and use JSON field value
            if (ARRAY.equals(fieldType)) {
                String dualModeKey = propertyPrefix + "_param" + fieldIndex + "_dualMode";
                Object dualModeObj = context.getProperty(dualModeKey);
                if ("true".equals(dualModeObj != null ? dualModeObj.toString() : null)) {
                    String inputModeFieldKey = propertyPrefix + "_param" + fieldIndex + "_inputModeField";
                    Object inputModeFieldObj = context.getProperty(inputModeFieldKey);
                    if (inputModeFieldObj != null) {
                        Object inputModeValue = SynapseUtils.lookupTemplateParameter(context, inputModeFieldObj.toString());
                        if ("JSON".equals(inputModeValue)) {
                            // In JSON mode, use the JSON field value instead of table value
                            String jsonFieldKey = propertyPrefix + "_param" + fieldIndex + "_jsonField";
                            Object jsonFieldObj = context.getProperty(jsonFieldKey);
                            if (jsonFieldObj != null) {
                                Object jsonFieldValue = SynapseUtils.lookupTemplateParameter(context, jsonFieldObj.toString());
                                if (jsonFieldValue != null && !jsonFieldValue.toString().isEmpty()) {
                                    fieldValue = jsonFieldValue;
                                }
                            }
                        }
                    }
                }
            }

            if (fieldValue != null) {
                String valueStr = fieldValue.toString();
                if ((MAP.equals(fieldType) || ARRAY.equals(fieldType)) && "[]".equals(valueStr)) {
                    fieldIndex++;
                    continue;
                }
                // Skip enum fields with invalid numeric placeholder values (e.g., "2", "3", ...)
                // But allow "0" and "1" which are valid for ZERO_OR_ONE type
                if (ENUM.equals(fieldType) && valueStr.matches("\\d+") && !valueStr.equals("0") && !valueStr.equals("1")) {
                    fieldIndex++;
                    continue;
                }
                setNestedField(recordJson, fieldPath, fieldValue, fieldType, context, propertyPrefix, fieldIndex);
            } else if (setDefaultsForMissingFields && (DECIMAL.equals(fieldType) || INT.equals(fieldType))) {
                // Set default value of 0 for numeric fields that aren't provided
                // This prevents null pointer exceptions when Ballerina code accesses these fields in generic BMaps
                setNestedField(recordJson, fieldPath, "0", fieldType, context, propertyPrefix, fieldIndex);
            }
            fieldIndex++;
        }

        String jsonStr = recordJson.toString();

        Object parseResult = JsonUtils.parse(jsonStr);
        if (parseResult instanceof BError bError) {
            throw new SynapseException("Failed to parse reconstructed record JSON: " + bError.getMessage());
        }

        // Post-process to convert numeric values to correct Ballerina types (especially decimal)
        if (parseResult instanceof BMap) {
            @SuppressWarnings("unchecked")
            BMap<BString, Object> resultMap = (BMap<BString, Object>) parseResult;
            convertFieldTypes(resultMap, propertyPrefix, context);
        }

        return parseResult;
    }

    /**
     * Converts field values to correct Ballerina types based on the field type info from properties.
     * This is needed because JsonUtils.parse() may produce Long/Double for numeric values,
     * but Ballerina expects specific types like BDecimal for decimal fields.
     */
    private static void convertFieldTypes(BMap<BString, Object> map, String propertyPrefix, MessageContext context) {
        int fieldIndex = 0;
        while (true) {
            String fieldNameKey = propertyPrefix + "_param" + fieldIndex;
            String fieldTypeKey = propertyPrefix + "_paramType" + fieldIndex;

            Object fieldNameObj = context.getProperty(fieldNameKey);
            Object fieldTypeObj = context.getProperty(fieldTypeKey);

            if (fieldNameObj == null || fieldTypeObj == null) break;

            String fieldPath = fieldNameObj.toString();
            String fieldType = fieldTypeObj.toString();

            // Handle nested fields (e.g., amqpRetryOptions.delay)
            if (fieldPath.contains(".")) {
                convertNestedFieldType(map, fieldPath, fieldType);
            } else {
                // Simple field
                BString bFieldName = StringUtils.fromString(fieldPath);
                if (map.containsKey(bFieldName)) {
                    Object currentValue = map.get(bFieldName);
                    if (DECIMAL.equals(fieldType) && currentValue != null && !(currentValue instanceof io.ballerina.runtime.api.values.BDecimal)) {
                        Object newValue = ValueCreator.createDecimalValue(new java.math.BigDecimal(currentValue.toString()));
                        map.put(bFieldName, newValue);
                    }
                }
            }
            fieldIndex++;
        }
    }

    /**
     * Converts a nested field value to the correct Ballerina type.
     * For example, for fieldPath "amqpRetryOptions.delay" with type "decimal",
     * navigates to the amqpRetryOptions BMap and converts the delay field.
     */
    @SuppressWarnings("unchecked")
    private static void convertNestedFieldType(BMap<BString, Object> rootMap, String fieldPath, String fieldType) {
        String[] parts = fieldPath.split("\\.");
        BMap<BString, Object> currentMap = rootMap;

        // Navigate to the parent object
        for (int i = 0; i < parts.length - 1; i++) {
            BString key = StringUtils.fromString(parts[i]);
            Object nested = currentMap.get(key);
            if (nested instanceof BMap) {
                currentMap = (BMap<BString, Object>) nested;
            } else {
                // Parent doesn't exist or isn't a map - nothing to convert
                return;
            }
        }

        // Now convert the final field
        String finalFieldName = parts[parts.length - 1];
        BString bFieldName = StringUtils.fromString(finalFieldName);

        if (currentMap.containsKey(bFieldName)) {
            Object currentValue = currentMap.get(bFieldName);
            if (DECIMAL.equals(fieldType) && currentValue != null && !(currentValue instanceof io.ballerina.runtime.api.values.BDecimal)) {
                Object newValue = ValueCreator.createDecimalValue(new java.math.BigDecimal(currentValue.toString()));
                currentMap.put(bFieldName, newValue);
                log.info("Converted nested " + fieldPath + " to BDecimal: " + newValue);
            }
        }
    }

    private static void setNestedField(JsonObject jsonObject, String fieldPath, Object value, String fieldType,
                                        MessageContext context, String propertyPrefix, int fieldIndex) {
        String[] parts = fieldPath.split("\\.");
        for (int i = 0; i < parts.length - 1; i++) {
            String part = parts[i];
            if (!jsonObject.has(part)) jsonObject.add(part, new JsonObject());
            jsonObject = jsonObject.getAsJsonObject(part);
        }

        String finalField = parts[parts.length - 1];
        String valueStr = value.toString();

        if (SYNAPSE_EXPRESSION_PATTERN.matcher(valueStr).find()) {
            valueStr = SynapseUtils.resolveSynapseExpressions(valueStr, context);
        }

        switch (fieldType) {
            case BOOLEAN:
                jsonObject.addProperty(finalField, Boolean.parseBoolean(valueStr));
                break;
            case INT:
                jsonObject.addProperty(finalField, Long.parseLong(valueStr));
                break;
            case FLOAT:
                jsonObject.addProperty(finalField, Double.parseDouble(valueStr));
                break;
            case DECIMAL:
                // Use double to ensure decimal point in JSON serialization
                // This ensures Ballerina JsonUtils parses it as a decimal, not integer
                java.math.BigDecimal bd = new java.math.BigDecimal(valueStr);
                jsonObject.addProperty(finalField, bd.doubleValue());
                break;
            case ENUM:
                // Handle ZERO_OR_ONE type (0|1) as integers, other enums as strings
                if (valueStr.equals("0") || valueStr.equals("1")) {
                    jsonObject.addProperty(finalField, Long.parseLong(valueStr));
                } else {
                    jsonObject.addProperty(finalField, valueStr);
                }
                break;
            case JSON:
            case RECORD:
            case UNION:
                try {
                    String cleanedJson = SynapseUtils.cleanupJsonString(valueStr);
                    JsonElement jsonElement = JsonParser.parseString(cleanedJson);
                    jsonObject.add(finalField, jsonElement);
                } catch (JsonSyntaxException e) {
                    jsonObject.addProperty(finalField, valueStr);
                }
                break;
            case ARRAY:
                try {
                    String cleanedJson = SynapseUtils.cleanupJsonString(valueStr);

                    // Check if this is an array of records with dual input mode (Table/JSON selector)
                    String dualModeKey = propertyPrefix + "_param" + fieldIndex + "_dualMode";
                    String inputModeFieldKey = propertyPrefix + "_param" + fieldIndex + "_inputModeField";
                    String jsonFieldKey = propertyPrefix + "_param" + fieldIndex + "_jsonField";
                    String elementTypeKey = propertyPrefix + "_arrayElementType" + fieldIndex;
                    String recordFieldsKey = propertyPrefix + "_arrayRecordFields" + fieldIndex;

                    Object dualModeObj = context.getProperty(dualModeKey);
                    Object elementTypeObj = context.getProperty(elementTypeKey);
                    Object recordFieldsObj = context.getProperty(recordFieldsKey);

                    // Check if user selected JSON mode for this nested array
                    if ("true".equals(dualModeObj != null ? dualModeObj.toString() : null)) {
                        Object inputModeFieldObj = context.getProperty(inputModeFieldKey);
                        if (inputModeFieldObj != null) {
                            Object inputModeValue = SynapseUtils.lookupTemplateParameter(context, inputModeFieldObj.toString());
                            if ("JSON".equals(inputModeValue)) {
                                // User selected JSON mode - read from JSON field instead of table
                                Object jsonFieldObj = context.getProperty(jsonFieldKey);
                                if (jsonFieldObj != null) {
                                    Object jsonFieldValue = SynapseUtils.lookupTemplateParameter(context, jsonFieldObj.toString());
                                    if (jsonFieldValue != null && !jsonFieldValue.toString().isEmpty()) {
                                        cleanedJson = SynapseUtils.cleanupJsonString(jsonFieldValue.toString());
                                    }
                                }
                            }
                        }
                    }

                    // Convert table data to JSON objects if needed (for Table mode)
                    if ("record".equals(elementTypeObj != null ? elementTypeObj.toString() : null)
                            && recordFieldsObj != null && cleanedJson.startsWith("[[")) {
                        // Table data is 2D array format: [["val1","val2",...],...]
                        // Convert to JSON objects: [{"field1":"val1","field2":"val2",...},...]
                        cleanedJson = convertNestedTableToJsonObjects(cleanedJson, recordFieldsObj.toString());
                    }

                    JsonElement jsonElement = JsonParser.parseString(cleanedJson);
                    if (jsonElement.isJsonArray() && jsonElement.getAsJsonArray().size() > 0 && jsonElement.getAsJsonArray().get(0).isJsonArray()) {
                        // Generic 2D array flattening (for non-record arrays)
                        com.google.gson.JsonArray flatArr = new com.google.gson.JsonArray();
                        for(JsonElement e : jsonElement.getAsJsonArray()) {
                            if(e.isJsonArray()) {
                                for(JsonElement inner : e.getAsJsonArray()) if(!inner.isJsonNull()) flatArr.add(inner);
                            }
                        }
                        jsonObject.add(finalField, flatArr);
                    } else {
                        jsonObject.add(finalField, jsonElement);
                    }
                } catch (JsonSyntaxException e) {
                    jsonObject.addProperty(finalField, valueStr);
                }
                break;
            case MAP:
                 try {
                    String cleanedJson = SynapseUtils.cleanupJsonString(valueStr);
                    JsonElement jsonElement = JsonParser.parseString(cleanedJson);
                    if (jsonElement.isJsonArray() && jsonElement.getAsJsonArray().size() > 0 && jsonElement.getAsJsonArray().get(0).isJsonArray()) {
                        JsonObject mapObj = new JsonObject();
                        for(JsonElement e : jsonElement.getAsJsonArray()) {
                            if(e.isJsonArray()) {
                                com.google.gson.JsonArray pair = e.getAsJsonArray();
                                if(pair.size() >= 2) mapObj.add(pair.get(0).getAsString(), pair.get(1));
                            }
                        }
                        jsonObject.add(finalField, mapObj);
                    } else {
                        jsonObject.add(finalField, jsonElement);
                    }
                } catch (JsonSyntaxException e) {
                    jsonObject.addProperty(finalField, valueStr);
                }
                break;
            default:
                jsonObject.addProperty(finalField, valueStr);
                break;
        }
    }

    /**
     * Converts MI table 2D array format to JSON objects format for nested arrays in records.
     * Input: [["val1","val2",...],["val3","val4",...],...] (2D array from table)
     * Output: [{"field1":"val1","field2":"val2",...},{"field1":"val3","field2":"val4",...},...] (JSON objects)
     *
     * Empty string values are skipped to allow optional fields to use their defaults.
     */
    private static String convertNestedTableToJsonObjects(String tableJson, String fieldNamesStr) {
        String[] fieldNames = fieldNamesStr.split(",");
        try {
            JsonElement parsed = JsonParser.parseString(tableJson);
            if (!parsed.isJsonArray()) {
                return tableJson;
            }
            com.google.gson.JsonArray outerArray = parsed.getAsJsonArray();
            com.google.gson.JsonArray result = new com.google.gson.JsonArray();

            for (int i = 0; i < outerArray.size(); i++) {
                JsonElement row = outerArray.get(i);
                if (row.isJsonArray()) {
                    com.google.gson.JsonArray rowArray = row.getAsJsonArray();
                    JsonObject obj = new JsonObject();
                    for (int j = 0; j < Math.min(fieldNames.length, rowArray.size()); j++) {
                        String fieldName = fieldNames[j].trim();
                        JsonElement value = rowArray.get(j);
                        if (value.isJsonNull()) {
                            // Skip null values - let Ballerina use default
                            continue;
                        }
                        // Skip empty string values - they represent unset optional fields
                        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                                && value.getAsString().isEmpty()) {
                            continue;
                        }
                        obj.add(fieldName, value);
                    }
                    result.add(obj);
                }
            }
            return result.toString();
        } catch (Exception e) {
            log.error("Failed to convert nested table to JSON objects: " + e.getMessage(), e);
            return tableJson;
        }
    }
    
    public static Object getJsonParameter(Object param) {
        String strParam;
        if (param instanceof String) {
            strParam = (String) param;
            if (strParam.startsWith("'") && strParam.endsWith("'")) {
                strParam = strParam.substring(1, strParam.length() - 1);
            }
        } else {
            strParam = param.toString();
        }

        // Try parsing as JSON - handles int, float, boolean, objects, arrays, and quoted strings
        try {
            Object parsed = JsonUtils.parse(strParam);
            if (parsed instanceof BError) {
                // JSON parsing returned error - treat as plain string
                return StringUtils.fromString(strParam);
            }
            return parsed;
        } catch (Exception e) {
            // JSON parsing threw exception - treat as plain string (e.g., unquoted text for anydata type)
            return StringUtils.fromString(strParam);
        }
    }
    
    public static BMap getMapParameter(Object param, MessageContext context, String valueKey) {
        String jsonString;
        if (param instanceof String strParam) {
            if (strParam.startsWith("'") && strParam.endsWith("'")) {
                strParam = strParam.substring(1, strParam.length() - 1);
            }
            jsonString = strParam;
        } else {
            jsonString = param.toString();
        }
        jsonString = SynapseUtils.cleanupJsonString(jsonString);
        Object parsed = JsonUtils.parse(jsonString);

        if (parsed instanceof BMap) return (BMap) parsed;

        if (parsed instanceof BArray array) {
            if (array.size() == 0) return ValueCreator.createMapValue();
            Object firstElement = array.get(0);
            if (firstElement instanceof BMap firstRow && firstRow.containsKey(StringUtils.fromString("key"))) {
                return transformTableToMap(array);
            }
            if (firstElement instanceof BArray) {
                int paramIndex = -1;
                String indexStr = valueKey.replaceAll("\\D+", "");
                if (!indexStr.isEmpty()) {
                    try { paramIndex = Integer.parseInt(indexStr); } catch (NumberFormatException ignored) {}
                }
                String[] fieldNames = null;
                if (paramIndex >= 0) {
                    Object fieldNamesObj = context.getProperty("mapRecordFields" + paramIndex);
                    if (fieldNamesObj != null) fieldNames = fieldNamesObj.toString().split(",");
                }
                return transform2DArrayToMap(array, fieldNames);
            }
        }
        throw new SynapseException("Map parameter must be a JSON object or table array");
    }

    public static String transformNestedTableTo2DArray(BArray outerArray) {
        BString innerArrayKey = StringUtils.fromString("innerArray");
        BString valueKey = StringUtils.fromString("value");
        StringBuilder jsonBuilder = new StringBuilder("[");

        for (int i = 0; i < outerArray.size(); i++) {
            if (i > 0) jsonBuilder.append(",");
            Object outerElement = outerArray.get(i);
            if (outerElement instanceof BMap outerRow) {
                Object innerValue = outerRow.get(innerArrayKey);
                if (innerValue instanceof BArray innerArray) {
                    jsonBuilder.append("[");
                    for (int j = 0; j < innerArray.size(); j++) {
                        if (j > 0) jsonBuilder.append(",");
                        Object innerElement = innerArray.get(j);
                        if (innerElement instanceof BMap innerRow) {
                            if (innerRow.containsKey(valueKey) && innerRow.size() == 1) {
                                appendJsonValue(jsonBuilder, innerRow.get(valueKey));
                            } else {
                                jsonBuilder.append(innerElement.toString());
                            }
                        } else {
                            appendJsonValue(jsonBuilder, innerElement);
                        }
                    }
                    jsonBuilder.append("]");
                } else {
                    jsonBuilder.append("[]");
                }
            } else {
                jsonBuilder.append("[]");
            }
        }
        jsonBuilder.append("]");
        return jsonBuilder.toString();
    }

    public static String transformMIStudioNestedTableTo2DArray(BArray outerArray, MessageContext context) {
        StringBuilder jsonBuilder = new StringBuilder("[");
        for (int i = 0; i < outerArray.size(); i++) {
            if (i > 0) jsonBuilder.append(",");
            Object outerElement = outerArray.get(i);
            if (outerElement instanceof BArray innerRow && innerRow.size() >= 2) {
                String innerTableStr = innerRow.get(1).toString();
                String innerArrayJson = parseInnerTableValues(innerTableStr, context);
                jsonBuilder.append(innerArrayJson);
            } else {
                jsonBuilder.append("[]");
            }
        }
        jsonBuilder.append("]");
        return jsonBuilder.toString();
    }

    static String parseInnerTableValues(String innerTableStr, MessageContext context) {
        if (innerTableStr == null || innerTableStr.trim().isEmpty() || "[]".equals(innerTableStr.trim())) return "[]";

        String resolvedStr = SynapseUtils.resolveSynapseExpressions(innerTableStr, context);
        StringBuilder result = new StringBuilder("[");
        List<String> values = new ArrayList<>();

        Pattern resolvedExprPattern = Pattern.compile(",\\s*value=([^{}]+?)\\}\\}");
        Matcher resolvedMatcher = resolvedExprPattern.matcher(resolvedStr);
        while (resolvedMatcher.find()) values.add(resolvedMatcher.group(1).trim());

        if (values.isEmpty()) {
            Pattern plainPattern = Pattern.compile("\\{value=([^{}]+)\\}");
            Matcher plainMatcher = plainPattern.matcher(resolvedStr);
            while (plainMatcher.find()) values.add(plainMatcher.group(1).trim());
        }

        for (int i = 0; i < values.size(); i++) {
            if (i > 0) result.append(",");
            appendJsonValue(result, values.get(i));
        }
        result.append("]");
        return result.toString();
    }

    static void appendJsonValue(StringBuilder builder, Object value) {
        if (value == null) {
            builder.append("null");
        } else {
            String str = value.toString();
            try { Long.parseLong(str); builder.append(str); return; } catch (NumberFormatException ignored) {}
            try { Double.parseDouble(str); builder.append(str); return; } catch (NumberFormatException ignored) {}
            if ("true".equals(str) || "false".equals(str)) { builder.append(str); return; }
            builder.append("\"").append(escapeJsonString(str)).append("\"");
        }
    }

    static String escapeJsonString(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    public static String transformTableArrayToSimpleArray(BArray tableArray) {
        BString valueFieldName = StringUtils.fromString("value");
        StringBuilder jsonBuilder = new StringBuilder("[");
        for (int i = 0; i < tableArray.size(); i++) {
            if (i > 0) jsonBuilder.append(",");
            Object element = tableArray.get(i);
            if (element instanceof BMap row) {
                Object value = row.get(valueFieldName);
                if (value == null) throw new SynapseException("Table row missing 'value' field at index " + i);
                if (value instanceof BString) {
                    String strValue = ((BString) value).getValue();
                    jsonBuilder.append("\"").append(strValue.replace("\"", "\\\"")).append("\"");
                } else if (value instanceof Boolean || value instanceof Number) {
                    jsonBuilder.append(value.toString());
                } else {
                    jsonBuilder.append("\"").append(value.toString().replace("\"", "\\\"")).append("\"");
                }
            }
        }
        jsonBuilder.append("]");
        return jsonBuilder.toString();
    }
    
    // Helper methods for map transformation
    private static BMap transformTableToMap(BArray tableArray) {
        BMap resultMap = ValueCreator.createMapValue();
        BString keyFieldName = StringUtils.fromString("key");
        for (int i = 0; i < tableArray.size(); i++) {
            Object element = tableArray.get(i);
            if (element instanceof BMap row) {
                Object keyObj = row.get(keyFieldName);
                if (keyObj == null) throw new SynapseException("Table row missing 'key' field at index " + i);
                String keyStr = keyObj instanceof BString ? ((BString) keyObj).getValue() : keyObj.toString();
                BString key = StringUtils.fromString(keyStr);

                if (row.size() == 2 && row.containsKey(StringUtils.fromString("value"))) {
                    resultMap.put(key, row.get(StringUtils.fromString("value")));
                } else {
                    BMap recordValue = ValueCreator.createMapValue();
                    for (Object rowKey : row.getKeys()) {
                        BString rowKeyStr = (BString) rowKey;
                        if (!rowKeyStr.getValue().equals("key")) recordValue.put(rowKeyStr, row.get(rowKeyStr));
                    }
                    resultMap.put(key, recordValue);
                }
            }
        }
        return resultMap;
    }

    private static BMap transform2DArrayToMap(BArray array2D, String[] fieldNames) {
        BMap resultMap = ValueCreator.createMapValue();
        for (int i = 0; i < array2D.size(); i++) {
            Object element = array2D.get(i);
            if (element instanceof BArray row) {
                if (row.size() == 0) continue;
                Object keyObj = row.get(0);
                String keyStr = keyObj instanceof BString ? ((BString) keyObj).getValue() : keyObj.toString();
                BString key = StringUtils.fromString(keyStr);

                if (row.size() == 2) {
                    resultMap.put(key, row.get(1));
                } else if (row.size() > 2) {
                    BMap recordValue = ValueCreator.createMapValue();
                    for (int j = 1; j < row.size(); j++) {
                        String fieldName;
                        if (fieldNames != null && (j - 1) < fieldNames.length) fieldName = fieldNames[j - 1];
                        else fieldName = "field" + (j - 1);
                        recordValue.put(StringUtils.fromString(fieldName), row.get(j));
                    }
                    resultMap.put(key, recordValue);
                }
            }
        }
        return resultMap;
    }

    /**
     * Gets the appropriate Module for creating a record value.
     * If recordOrg and recordModule properties are set, creates a Module from them.
     * Otherwise, falls back to the connector's module.
     */
    private static Module getRecordModule(MessageContext context, String recordOrgPropertyKey,
            String recordModulePropertyKey, String recordVersionPropertyKey) {
        Object recordOrgObj = context.getProperty(recordOrgPropertyKey);
        Object recordModuleObj = context.getProperty(recordModulePropertyKey);
        Object recordVersionObj = recordVersionPropertyKey != null ? context.getProperty(recordVersionPropertyKey) : null;

        Module connectorModule = BalConnectorConfig.getModule();

        if (recordOrgObj != null && recordModuleObj != null) {
            String recordOrg = recordOrgObj.toString();
            String recordModuleName = recordModuleObj.toString();

            if (!recordOrg.isEmpty() && !recordModuleName.isEmpty()) {
                // If the record belongs to the same connector module, use the properly initialized module
                // This ensures ValueCreator can find the value creator for this module
                if (connectorModule != null &&
                        recordOrg.equals(connectorModule.getOrg()) &&
                        recordModuleName.equals(connectorModule.getName())) {
                    return connectorModule;
                }

                // For external modules (like ballerina/time), create a new Module
                String recordVersion = recordVersionObj != null ? recordVersionObj.toString() : null;
                return new Module(recordOrg, recordModuleName, recordVersion);
            }
        }
        // Fall back to connector module
        return connectorModule;
    }
}
