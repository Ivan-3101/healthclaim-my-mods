package com.DronaPay.frm.HealthClaim.generic.util;

import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.json.JSONObject;

/**
 * Utility for parsing JSON responses and extracting values using JSON paths
 */
@Slf4j
public class ResponseParser {

    /**
     * Parse response and set process variables according to configuration
     */
    public static void parseAndSetVariables(JSONObject responseMapping,
                                            JSONObject apiResponse,
                                            DelegateExecution execution) {
        for (String variableName : responseMapping.keySet()) {
            JSONObject mapping = responseMapping.getJSONObject(variableName);

            String jsonPath = mapping.getString("jsonPath");
            String dataType = mapping.optString("dataType", "string");
            Object defaultValue = mapping.opt("defaultValue");

            // Extract value from response
            Object value = extractValueFromPath(apiResponse, jsonPath);

            // Handle null values
            if (value == null || JSONObject.NULL.equals(value)) {
                value = resolveDefaultValue(defaultValue, execution);
                log.debug("Using default value for '{}': {}", variableName, value);
            }

            // Convert data type
            if (value != null) {
                value = convertDataType(value, dataType);
            }

            // Set process variable
            execution.setVariable(variableName, value);
            log.debug("Set process variable '{}' = {} (type: {})",
                    variableName, value, dataType);
        }
    }

    /**
     * Extract value from JSON using path notation
     * Supports: /score/score, /score/decisiondetails/0/approved_amount
     */
    private static Object extractValueFromPath(JSONObject json, String path) {
        try {
            // Remove leading slash if present
            if (path.startsWith("/")) {
                path = path.substring(1);
            }

            String[] parts = path.split("/");
            Object current = json;

            for (String part : parts) {
                if (current == null || JSONObject.NULL.equals(current)) {
                    return null;
                }

                // Check if part is an array index
                if (part.matches("\\d+")) {
                    int index = Integer.parseInt(part);
                    if (current instanceof org.json.JSONArray) {
                        current = ((org.json.JSONArray) current).get(index);
                    } else {
                        log.warn("Expected array at path part '{}', got: {}",
                                part, current.getClass().getSimpleName());
                        return null;
                    }
                } else {
                    // Regular object property
                    if (current instanceof JSONObject) {
                        current = ((JSONObject) current).opt(part);
                    } else {
                        log.warn("Expected object at path part '{}', got: {}",
                                part, current.getClass().getSimpleName());
                        return null;
                    }
                }
            }

            return current;

        } catch (Exception e) {
            log.error("Error extracting value from path '{}': {}", path, e.getMessage());
            return null;
        }
    }

    /**
     * Resolve default value (can be static value or process variable reference)
     */
    private static Object resolveDefaultValue(Object defaultValue, DelegateExecution execution) {
        if (defaultValue == null) {
            return null;
        }

        String defaultStr = defaultValue.toString();

        // Check if it's a process variable reference: {{variableName}}
        if (defaultStr.startsWith("{{") && defaultStr.endsWith("}}")) {
            String varName = defaultStr.substring(2, defaultStr.length() - 2);
            Object value = execution.getVariable(varName);
            log.debug("Resolved default from process variable '{}': {}", varName, value);
            return value;
        }

        // Static default value
        return defaultValue;
    }

    /**
     * Convert value to specified data type
     */
    private static Object convertDataType(Object value, String dataType) {
        if (value == null || JSONObject.NULL.equals(value)) {
            return null;
        }

        try {
            switch (dataType.toLowerCase()) {
                case "long":
                case "integer":
                    if (value instanceof Long) return value;
                    if (value instanceof Integer) return ((Integer) value).longValue();
                    if (value instanceof Double) return ((Double) value).longValue();
                    return Long.parseLong(value.toString());

                case "double":
                    if (value instanceof Double) return value;
                    if (value instanceof Float) return ((Float) value).doubleValue();
                    return Double.parseDouble(value.toString());

                case "boolean":
                    if (value instanceof Boolean) return value;
                    return Boolean.parseBoolean(value.toString());

                case "string":
                    return value.toString();

                case "json":
                    if (value instanceof JSONObject) return value.toString();
                    return value.toString();

                default:
                    return value;
            }
        } catch (Exception e) {
            log.error("Failed to convert value '{}' to type '{}': {}",
                    value, dataType, e.getMessage());
            return value; // Return original value on conversion failure
        }
    }
}