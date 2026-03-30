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

import io.ballerina.runtime.api.creators.ValueCreator;
import io.ballerina.runtime.api.utils.JsonUtils;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BArray;
import io.ballerina.runtime.api.values.BString;

import static io.ballerina.stdlib.mi.Constants.*;

/**
 * Shared type conversion utilities for converting between MI and Ballerina types.
 * This class centralizes all type conversion logic to avoid duplication between
 * Mediator and BalConnectorFunction.
 *
 * <p>Supported conversions:
 * <ul>
 *   <li>Primitives: boolean, int, string, float, decimal</li>
 *   <li>Complex: xml, json, record</li>
 *   <li>Arrays: string[], int[], boolean[], float[], decimal[]</li>
 * </ul>
 */
public class TypeConverter {

    /**
     * Convert a JSON array string to a Ballerina array based on element type.
     *
     * @param jsonArrayString JSON array string (e.g., "[\"a\", \"b\", \"c\"]")
     * @param elementType     The Ballerina element type (e.g., "string", "int")
     * @return Ballerina array (BArray)
     * @throws IllegalArgumentException if element type is not supported
     */
    public static BArray convertToArray(String jsonArrayString, String elementType) {
        // Parse JSON array using Ballerina's JSON utils
        BArray jsonArray = (BArray) JsonUtils.parse(jsonArrayString);

        // Convert based on element type
        return switch (elementType) {
            case STRING -> convertToStringArray(jsonArray);
            case INT -> convertToIntArray(jsonArray);
            case BOOLEAN -> convertToBooleanArray(jsonArray);
            case FLOAT -> convertToFloatArray(jsonArray);
            case DECIMAL -> convertToDecimalArray(jsonArray);
            default -> throw new IllegalArgumentException("Unsupported array element type: " + elementType);
        };
    }

    /**
     * Convert a JSON array to a Ballerina string array.
     *
     * @param jsonArray JSON array
     * @return Ballerina string array
     */
    public static BArray convertToStringArray(BArray jsonArray) {
        BString[] strings = new BString[(int) jsonArray.size()];
        for (int i = 0; i < jsonArray.size(); i++) {
            strings[i] = StringUtils.fromString(jsonArray.get(i).toString());
        }
        return ValueCreator.createArrayValue(strings);
    }

    /**
     * Convert a JSON array to a Ballerina int array.
     *
     * @param jsonArray JSON array
     * @return Ballerina int array
     */
    public static BArray convertToIntArray(BArray jsonArray) {
        long[] ints = new long[(int) jsonArray.size()];
        for (int i = 0; i < jsonArray.size(); i++) {
            Object element = jsonArray.get(i);
            if (element instanceof Long) {
                ints[i] = (Long) element;
            } else {
                try {
                    ints[i] = Long.parseLong(element.toString());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                            "Invalid int value at index " + i + ": '" + element + "'", e);
                }
            }
        }
        return ValueCreator.createArrayValue(ints);
    }

    /**
     * Convert a JSON array to a Ballerina boolean array.
     *
     * @param jsonArray JSON array
     * @return Ballerina boolean array
     */
    public static BArray convertToBooleanArray(BArray jsonArray) {
        boolean[] booleans = new boolean[(int) jsonArray.size()];
        for (int i = 0; i < jsonArray.size(); i++) {
            booleans[i] = (Boolean) jsonArray.get(i);
        }
        return ValueCreator.createArrayValue(booleans);
    }

    /**
     * Convert a JSON array to a Ballerina float array.
     *
     * @param jsonArray JSON array
     * @return Ballerina float array
     */
    public static BArray convertToFloatArray(BArray jsonArray) {
        double[] floats = new double[(int) jsonArray.size()];
        for (int i = 0; i < jsonArray.size(); i++) {
            Object element = jsonArray.get(i);
            if (element instanceof Double) {
                floats[i] = (Double) element;
            } else {
                try {
                    floats[i] = Double.parseDouble(element.toString());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Element at index " + i + " ('" + element + "') cannot be parsed as a double.", e);
                }
            }
        }
        return ValueCreator.createArrayValue(floats);
    }

    /**
     * Convert a JSON array to a Ballerina decimal array.
     * Note: Returns the JSON array as-is since Ballerina can handle it directly.
     *
     * @param jsonArray JSON array
     * @return Ballerina array (JSON array representation)
     */
    public static BArray convertToDecimalArray(BArray jsonArray) {
        // For decimals, return the JSON array as-is since Ballerina can handle it
        return jsonArray;
    }

    /**
     * Convert a string value to the specified Ballerina type.
     *
     * @param value      String value to convert
     * @param targetType Target Ballerina type
     * @return Converted value
     * @throws IllegalArgumentException if conversion is not supported
     */
    public static Object convertValue(String value, String targetType) {
        return switch (targetType) {
            case BOOLEAN -> Boolean.parseBoolean(value);
            case INT -> Long.parseLong(value);
            case STRING -> StringUtils.fromString(value);
            case FLOAT -> Double.parseDouble(value);
            case DECIMAL -> ValueCreator.createDecimalValue(value);
            default -> throw new IllegalArgumentException("Unsupported type conversion: " + targetType);
        };
    }

    /**
     * Check if a type is an array type.
     *
     * @param type Type string
     * @return true if it's an array type
     */
    public static boolean isArrayType(String type) {
        return ARRAY.equals(type);
    }

    /**
     * Check if a type is a primitive type.
     *
     * @param type Type string
     * @return true if it's a primitive type
     */
    public static boolean isPrimitiveType(String type) {
        return BOOLEAN.equals(type) || INT.equals(type) || STRING.equals(type) ||
               FLOAT.equals(type) || DECIMAL.equals(type);
    }

    /**
     * Convert a Ballerina array (BArray) to JSON string format.
     * This is used for array return types to convert them to a format
     * that MI can consume.
     *
     * @param bArray Ballerina array
     * @return JSON string representation (e.g., "[1, 2, 3]" or "[\"a\", \"b\"]")
     */
    public static String arrayToJsonString(BArray bArray) {
        if (bArray == null) {
            return "[]";
        }
        return bArray.toString();
    }
}
