package com.DronaPay.frm.HealthClaim.generic.util;

import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.util.Random;
import java.util.UUID;

/**
 * Utility for dynamically mapping process variables to JSON API requests
 */
@Slf4j
public class DynamicFieldMapper {

    /**
     * Set a value in a JSON object using dot notation path
     * Example: "txn.attribs.patient_name" → reqBody.txn.attribs.patient_name = value
     */
    public static void setValueByPath(JSONObject root, String path, Object value) {
        String[] parts = path.split("\\.");
        JSONObject current = root;

        // Navigate to the parent object
        for (int i = 0; i < parts.length - 1; i++) {
            String part = parts[i];

            // Create nested object if it doesn't exist
            if (!current.has(part)) {
                current.put(part, new JSONObject());
            }

            current = current.getJSONObject(part);
        }

        // Set the final value
        String finalKey = parts[parts.length - 1];
        current.put(finalKey, value);
    }

    /**
     * Apply static fields from configuration to request body
     */
    public static void applyStaticFields(JSONObject requestBody, JSONObject staticFields) {
        for (String key : staticFields.keySet()) {
            Object value = staticFields.get(key);
            setValueByPath(requestBody, key, value);
        }
        log.debug("Applied {} static fields", staticFields.length());
    }

    /**
     * Apply dynamic fields (UUID, timestamp, etc.) to request body
     */
    public static void applyDynamicFields(JSONObject requestBody, JSONObject dynamicFields) {
        for (String key : dynamicFields.keySet()) {
            String template = dynamicFields.getString(key);
            Object value = generateDynamicValue(template);
            setValueByPath(requestBody, key, value);
        }
        log.debug("Applied {} dynamic fields", dynamicFields.length());
    }

    /**
     * Map process variables to API fields according to configuration
     */
    public static void applyVariableMappings(JSONObject requestBody,
                                             JSONArray variableMappings,
                                             DelegateExecution execution) {
        for (int i = 0; i < variableMappings.length(); i++) {
            JSONObject mapping = variableMappings.getJSONObject(i);

            String apiField = mapping.getString("apiField");
            String processVariable = mapping.getString("processVariable");
            String dataType = mapping.optString("dataType", "string");

            // Get value from process variable
            Object rawValue = execution.getVariable(processVariable);

            if (rawValue == null) {
                log.warn("Process variable '{}' is null, skipping mapping to '{}'",
                        processVariable, apiField);
                continue;
            }

            // Convert to appropriate data type
            Object convertedValue = convertDataType(rawValue, dataType);

            // Set in request body
            setValueByPath(requestBody, apiField, convertedValue);
        }
        log.debug("Applied {} variable mappings", variableMappings.length());
    }

    /**
     * Generate dynamic values based on templates
     */
    private static Object generateDynamicValue(String template) {
        switch (template) {
            case "{{UUID}}":
                return UUID.randomUUID().toString();

            case "{{TIMESTAMP}}":
                return Instant.now().toString();

            case "{{RANDOM_ID}}":
                Random random = new Random();
                char prefix = (char) ('A' + random.nextInt(26));
                return prefix + Long.toString(Instant.now().toEpochMilli());

            default:
                return template; // Return as-is if not a known template
        }
    }

    /**
     * Convert value to specified data type
     */
    private static Object convertDataType(Object value, String dataType) {
        if (value == null) {
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
                    return Double.parseDouble(value.toString());

                case "boolean":
                    if (value instanceof Boolean) return value;
                    return Boolean.parseBoolean(value.toString());

                case "string":
                default:
                    return value.toString();
            }
        } catch (Exception e) {
            log.error("Failed to convert value '{}' to type '{}': {}",
                    value, dataType, e.getMessage());
            return value; // Return original value on conversion failure
        }
    }
}