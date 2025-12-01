package com.DronaPay.frm.HealthClaim.generic.delegates;

import com.DronaPay.frm.HealthClaim.APIServices;
import com.DronaPay.frm.HealthClaim.generic.services.ConfigurationService;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.delegate.BpmnError;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.json.JSONArray;
import org.json.JSONObject;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.util.EntityUtils;

import java.sql.Connection;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Generic Scoring API Delegate - Handles FWA Decisioning and Cost Computation
 *
 * Input Variables Required:
 * - scoringType: String (e.g., "fwaDecisioning", "claimCostComputation")
 * - workflowKey: String (optional, defaults to "HealthClaim")
 *
 * Configuration loaded from ui.workflowmasters.filterparams:
 * {
 *   "scoring": {
 *     "fwaDecisioning": {
 *       "enabled": true,
 *       "requestTemplate": {
 *         "staticFields": {"org": "EPIFI", "class": "health_claim"},
 *         "variableMappings": [
 *           {"apiField": "txn.attribs.patient_name", "processVariable": "patientName"}
 *         ]
 *       },
 *       "responseMapping": {
 *         "RiskScore": {"jsonPath": "/score/score", "dataType": "long"}
 *       },
 *       "errorHandling": {"errorCode": "failedFWA"}
 *     }
 *   },
 *   "externalAPIs": {
 *     "springAPI": {
 *       "baseUrl": "https://main.dev.dronapay.net/springapi",
 *       "apiKey": "...",
 *       "endpoints": { "score": "/score" }
 *     }
 *   }
 * }
 */
@Slf4j
public class GenericScoringAPIDelegate implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("=== Generic Scoring API Delegate Started ===");
        log.info("TicketID: {}", execution.getVariable("TicketID"));

        String tenantId = execution.getTenantId();

        // 1. Get scoring type from input parameter
        String scoringType = (String) execution.getVariable("scoringType");
        if (scoringType == null || scoringType.isEmpty()) {
            throw new IllegalArgumentException("scoringType parameter is required");
        }

        // 2. Get workflow key (optional, defaults to HealthClaim)
        String workflowKey = (String) execution.getVariable("workflowKey");
        if (workflowKey == null || workflowKey.isEmpty()) {
            workflowKey = "HealthClaim";
        }

        log.info("Scoring Type: {}, Workflow: {}, Tenant: {}", scoringType, workflowKey, tenantId);

        // 3. Load scoring configuration from database
        Connection conn = execution.getProcessEngine()
                .getProcessEngineConfiguration()
                .getDataSource()
                .getConnection();

        JSONObject workflowConfig = ConfigurationService.loadWorkflowConfig(workflowKey, tenantId, conn);
        conn.close();

        JSONObject scoringConfig = extractScoringConfig(workflowConfig, scoringType);

        // 4. Check if scoring is enabled
        boolean enabled = scoringConfig.optBoolean("enabled", true);
        if (!enabled) {
            log.info("Scoring type '{}' is disabled, skipping", scoringType);
            return;
        }

        // 5. Build request body
        JSONObject requestBody = buildRequestBody(scoringConfig, execution);

        // 6. Initialize APIServices with workflow configuration (NEW!)
        APIServices apiServices = new APIServices(tenantId, workflowConfig);

        // 7. Call API
        CloseableHttpResponse response = null;

        if (scoringType.equals("fwaDecisioning")) {
            response = apiServices.FWADecisioning(requestBody.toString());
        } else if (scoringType.equals("claimCostComputation")) {
            response = apiServices.claimCost(requestBody.toString());
        } else {
            throw new IllegalArgumentException("Unknown scoring type: " + scoringType);
        }

        // 8. Process response
        processResponse(response, scoringConfig, execution, scoringType);

        log.info("=== Generic Scoring API Delegate Completed ===");
    }

    /**
     * Extract scoring configuration for specific type from workflow config
     */
    private JSONObject extractScoringConfig(JSONObject workflowConfig, String scoringType) {
        if (!workflowConfig.has("scoring")) {
            throw new IllegalArgumentException("No 'scoring' configuration found in workflow config");
        }

        JSONObject scoringSection = workflowConfig.getJSONObject("scoring");

        if (!scoringSection.has(scoringType)) {
            throw new IllegalArgumentException(
                    "No configuration found for scoring type: " + scoringType
            );
        }

        return scoringSection.getJSONObject(scoringType);
    }

    /**
     * Build request body dynamically from configuration
     */
    private JSONObject buildRequestBody(JSONObject scoringConfig, DelegateExecution execution) {
        JSONObject reqBody = new JSONObject();

        // Get request template from config
        JSONObject requestTemplate = scoringConfig.optJSONObject("requestTemplate");
        if (requestTemplate == null) {
            throw new IllegalArgumentException("requestTemplate not found in scoring configuration");
        }

        // Generate dynamic fields
        reqBody.put("reqid", UUID.randomUUID());
        String timeStamp = Instant.now().toString();
        reqBody.put("ts", timeStamp);

        // Add static fields from config
        JSONObject staticFields = requestTemplate.optJSONObject("staticFields");
        if (staticFields != null) {
            reqBody.put("org", staticFields.optString("org", "EPIFI"));
        } else {
            reqBody.put("org", "EPIFI");
        }

        // Build txn object
        JSONObject txnObject = new JSONObject();
        txnObject.put("ts", timeStamp);

        Random random = new Random();
        String id = (char) ('A' + random.nextInt(26)) + Long.toString(Instant.now().toEpochMilli());
        txnObject.put("id", id);
        txnObject.put("org_txn_id", "");
        txnObject.put("note", "");
        txnObject.put("type", "PAY");

        // Get class from static fields
        String classValue = staticFields != null ? staticFields.optString("class", "health_claim") : "health_claim";
        txnObject.put("class", classValue);

        // Build attributes from variable mappings
        JSONObject attribs = new JSONObject();

        JSONArray variableMappings = requestTemplate.optJSONArray("variableMappings");
        if (variableMappings != null) {
            for (int i = 0; i < variableMappings.length(); i++) {
                JSONObject mapping = variableMappings.getJSONObject(i);
                String apiField = mapping.getString("apiField");
                String processVariable = mapping.getString("processVariable");

                // Extract field name from apiField (e.g., "txn.attribs.patient_name" -> "patient_name")
                String fieldName = apiField;
                if (apiField.contains(".")) {
                    String[] parts = apiField.split("\\.");
                    fieldName = parts[parts.length - 1];
                }

                // Get value from process variable
                Object value = execution.getVariable(processVariable);
                if (value != null) {
                    attribs.put(fieldName, value);
                }
            }
        }

        txnObject.put("attribs", attribs);
        reqBody.put("txn", txnObject);

        // Build payer object (can be made configurable if needed)
        JSONObject payer = new JSONObject();
        payer.put("addr", "helliiooiiooohooo@1");
        payer.put("mcc", 0);
        payer.put("type", "PERSON");
        payer.put("amount", 500000);
        payer.put("currency", "INR");
        payer.put("attribs", new JSONObject().put("mid", "EPIFI101"));
        reqBody.put("payer", payer);

        // Build payee object (can be made configurable if needed)
        JSONObject payee = new JSONObject();
        payee.put("addr", "b5ZbTaCQzpgmlrtCB8DI_cZQPfRVNsC1C1XrzVfGgzWUiHbF6cCKO6-lyYFQ09Vf");
        payee.put("type", "PERSON");
        payee.put("amount", 500000);
        payee.put("currency", "INR");
        payee.put("attribs", new JSONObject());
        reqBody.put("payee", payee);

        log.debug("Built request body from configuration");
        log.debug("Request body: {}", reqBody.toString());

        return reqBody;
    }

    /**
     * Process API response and set process variables based on configuration
     */
    private void processResponse(CloseableHttpResponse response, JSONObject scoringConfig,
                                 DelegateExecution execution, String scoringType) throws Exception {
        String resp = EntityUtils.toString(response.getEntity());
        int statusCode = response.getStatusLine().getStatusCode();

        log.info("Scoring API status: {}", statusCode);
        log.debug("Scoring API response: {}", resp);

        // Store response for debugging
        execution.setVariable(scoringType + "Response", resp);

        if (statusCode != 200) {
            log.error("Scoring API failed with status: {}", statusCode);

            // Get error code from config
            String errorCode = "scoringError";
            if (scoringConfig.has("errorHandling")) {
                JSONObject errorHandling = scoringConfig.getJSONObject("errorHandling");
                errorCode = errorHandling.optString("errorCode", "scoringError");
            }

            throw new BpmnError(errorCode, "Scoring API returned status: " + statusCode);
        }

        // Parse response and extract values based on response mapping
        JSONObject resObj = new JSONObject(resp);
        JSONObject responseMapping = scoringConfig.optJSONObject("responseMapping");

        if (responseMapping == null) {
            log.warn("No responseMapping found in configuration for {}", scoringType);
            return;
        }

        // Iterate through response mapping
        for (String variableName : responseMapping.keySet()) {
            JSONObject mapping = responseMapping.getJSONObject(variableName);
            String jsonPath = mapping.getString("jsonPath");
            String dataType = mapping.optString("dataType", "string");

            try {
                Object value = resObj.optQuery(jsonPath);

                if (value != null && !JSONObject.NULL.equals(value)) {
                    // Convert to appropriate type
                    Object convertedValue = convertValue(value, dataType);
                    execution.setVariable(variableName, convertedValue);
                    log.info("Set variable '{}' = {} (from path: {})", variableName, convertedValue, jsonPath);
                } else {
                    // Use default value if specified
                    if (mapping.has("defaultValue")) {
                        Object defaultValue = mapping.get("defaultValue");
                        Object convertedDefault = convertValue(defaultValue, dataType);
                        execution.setVariable(variableName, convertedDefault);
                        log.warn("Using default value {} for variable '{}' (path '{}' returned null)",
                                convertedDefault, variableName, jsonPath);
                    } else {
                        // Fallback default based on variable name
                        Object fallbackDefault = getFallbackDefault(variableName, dataType, execution);
                        execution.setVariable(variableName, fallbackDefault);
                        log.warn("Using fallback default {} for variable '{}'", fallbackDefault, variableName);
                    }
                }
            } catch (Exception e) {
                log.error("Error extracting variable '{}' from path '{}'", variableName, jsonPath, e);

                // Use default or fallback
                Object defaultValue = getFallbackDefault(variableName, dataType, execution);
                execution.setVariable(variableName, defaultValue);
            }
        }
    }

    /**
     * Convert value to specified data type
     */
    private Object convertValue(Object value, String dataType) {
        if (value == null) {
            return getDefaultForType(dataType);
        }

        switch (dataType.toLowerCase()) {
            case "long":
            case "integer":
                return convertToLong(value);
            case "double":
            case "float":
                return convertToDouble(value);
            case "boolean":
                return convertToBoolean(value);
            case "string":
            default:
                return value.toString();
        }
    }

    /**
     * Convert value to Long (handles Double, Integer, String)
     */
    private Long convertToLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Double) {
            return ((Double) value).longValue();
        } else if (value instanceof Integer) {
            return ((Integer) value).longValue();
        } else if (value instanceof Long) {
            return (Long) value;
        } else {
            try {
                return Math.round(Double.parseDouble(value.toString()));
            } catch (NumberFormatException e) {
                log.error("Failed to convert value to Long: {}", value);
                return 0L;
            }
        }
    }

    /**
     * Convert value to Double
     */
    private Double convertToDouble(Object value) {
        if (value == null) {
            return 0.0;
        }
        if (value instanceof Double) {
            return (Double) value;
        } else if (value instanceof Number) {
            return ((Number) value).doubleValue();
        } else {
            try {
                return Double.parseDouble(value.toString());
            } catch (NumberFormatException e) {
                log.error("Failed to convert value to Double: {}", value);
                return 0.0;
            }
        }
    }

    /**
     * Convert value to Boolean
     */
    private Boolean convertToBoolean(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.parseBoolean(value.toString());
    }

    /**
     * Get default value for data type
     */
    private Object getDefaultForType(String dataType) {
        switch (dataType.toLowerCase()) {
            case "long":
            case "integer":
                return 0L;
            case "double":
            case "float":
                return 0.0;
            case "boolean":
                return false;
            case "string":
            default:
                return "";
        }
    }

    /**
     * Get fallback default value for a variable
     */
    private Object getFallbackDefault(String variableName, String dataType, DelegateExecution execution) {
        if (variableName.equals("RiskScore")) {
            return 100L; // Default high risk
        } else if (variableName.contains("Amount")) {
            // Try to get claimAmount as fallback
            try {
                Object claimAmount = execution.getVariable("claimAmount");
                if (claimAmount != null) {
                    return convertToLong(claimAmount);
                }
            } catch (Exception e) {
                log.debug("Could not retrieve claimAmount for default", e);
            }
        }

        return getDefaultForType(dataType);
    }
}