package com.DronaPay.frm.HealthClaim.generic.delegates;

import com.DronaPay.frm.HealthClaim.APIServices;
import com.DronaPay.frm.HealthClaim.generic.services.ConfigurationService;
import com.DronaPay.frm.HealthClaim.generic.util.DynamicFieldMapper;
import com.DronaPay.frm.HealthClaim.generic.util.ResponseParser;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.util.EntityUtils;
import org.camunda.bpm.engine.delegate.BpmnError;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.json.JSONArray;
import org.json.JSONObject;

import java.sql.Connection;
import java.util.Properties;

/**
 * Generic Scoring API Delegate
 * Handles FWA Decisioning, Cost Computation, and any other scoring APIs
 * through database configuration
 *
 * Required Input Variables:
 * - scoringType: String (e.g., "fwaDecisioning", "claimCostComputation")
 * - workflowKey: String (e.g., "HealthClaim") - optional, defaults to "HealthClaim"
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

        log.info("Executing scoring - Type: {}, Workflow: {}, Tenant: {}",
                scoringType, workflowKey, tenantId);

        try {
            // 3. Load scoring configuration from database
            ScoringConfig config = loadScoringConfig(workflowKey, scoringType, tenantId, execution);

            if (!config.enabled) {
                log.warn("Scoring type '{}' is disabled in configuration", scoringType);
                return;
            }

            // 4. Build request body dynamically
            JSONObject requestBody = buildRequestBody(config, execution);
            log.debug("Built request body: {}", requestBody.toString(2));

            // 5. Call scoring API
            String apiEndpoint = resolveEndpoint(config.apiEndpoint, tenantId);
            log.info("Calling scoring API: {}", apiEndpoint);

            APIServices apiServices = new APIServices(tenantId);
            CloseableHttpResponse response = callScoringAPI(apiServices, requestBody.toString());

            // 6. Parse response
            String responseBody = EntityUtils.toString(response.getEntity());
            int statusCode = response.getStatusLine().getStatusCode();

            log.info("Scoring API response - Status: {}", statusCode);
            log.debug("Response body: {}", responseBody);

            // 7. Handle response
            if (statusCode == 200) {
                JSONObject responseJson = new JSONObject(responseBody);
                ResponseParser.parseAndSetVariables(
                        config.responseMapping,
                        responseJson,
                        execution
                );
                log.info("Scoring completed successfully - Type: {}", scoringType);
            } else {
                log.error("Scoring API failed with status: {}", statusCode);
                throw new BpmnError("failed" + capitalize(scoringType),
                        "Scoring API returned status: " + statusCode);
            }

        } catch (BpmnError e) {
            throw e; // Re-throw BPMN errors
        } catch (Exception e) {
            log.error("Error in generic scoring delegate", e);
            throw new BpmnError("failed" + capitalize(scoringType),
                    "Scoring execution failed: " + e.getMessage());
        }

        log.info("=== Generic Scoring API Delegate Completed ===");
    }

    /**
     * Load scoring configuration from database
     */
    private ScoringConfig loadScoringConfig(String workflowKey, String scoringType,
                                            String tenantId, DelegateExecution execution)
            throws Exception {

        // Get database connection
        Connection conn = execution.getProcessEngine()
                .getProcessEngineConfiguration()
                .getDataSource()
                .getConnection();

        try {
            // Load workflow configuration
            JSONObject workflowConfig = ConfigurationService.loadWorkflowConfig(
                    workflowKey, tenantId, conn
            );

            // Navigate to scoring configuration
            if (!workflowConfig.has("scoring")) {
                throw new IllegalArgumentException(
                        "No 'scoring' configuration found for workflow: " + workflowKey
                );
            }

            JSONObject scoringConfigs = workflowConfig.getJSONObject("scoring");

            if (!scoringConfigs.has(scoringType)) {
                throw new IllegalArgumentException(
                        "No configuration found for scoring type: " + scoringType
                );
            }

            JSONObject scoringConfig = scoringConfigs.getJSONObject(scoringType);

            // Parse configuration
            ScoringConfig config = new ScoringConfig();
            config.enabled = scoringConfig.optBoolean("enabled", true);
            config.apiEndpoint = scoringConfig.getString("apiEndpoint");
            config.requestTemplate = scoringConfig.getJSONObject("requestTemplate");
            config.responseMapping = scoringConfig.getJSONObject("responseMapping");

            log.info("Loaded scoring configuration for type: {}", scoringType);
            return config;

        } finally {
            conn.close();
        }
    }

    /**
     * Build request body dynamically from configuration
     */
    private JSONObject buildRequestBody(ScoringConfig config, DelegateExecution execution) {
        JSONObject requestBody = new JSONObject();
        JSONObject requestTemplate = config.requestTemplate;

        // 1. Apply static fields
        if (requestTemplate.has("staticFields")) {
            JSONObject staticFields = requestTemplate.getJSONObject("staticFields");
            DynamicFieldMapper.applyStaticFields(requestBody, staticFields);
        }

        // 2. Apply dynamic fields (UUID, timestamp, etc.)
        if (requestTemplate.has("dynamicFields")) {
            JSONObject dynamicFields = requestTemplate.getJSONObject("dynamicFields");
            DynamicFieldMapper.applyDynamicFields(requestBody, dynamicFields);
        }

        // 3. Apply variable mappings (process variables → API fields)
        if (requestTemplate.has("variableMappings")) {
            JSONArray variableMappings = requestTemplate.getJSONArray("variableMappings");
            DynamicFieldMapper.applyVariableMappings(requestBody, variableMappings, execution);
        }

        return requestBody;
    }

    /**
     * Resolve API endpoint by replacing property placeholders
     */
    private String resolveEndpoint(String endpoint, String tenantId) throws Exception {
        Properties props = ConfigurationService.getTenantProperties(tenantId);

        // Replace ${springApiUrl} with actual URL
        if (endpoint.contains("${springApiUrl}")) {
            String springApiUrl = props.getProperty("springapi.url");
            endpoint = endpoint.replace("${springApiUrl}", springApiUrl);
        }

        return endpoint;
    }

    /**
     * Call scoring API (delegates to appropriate method based on endpoint)
     */
    private CloseableHttpResponse callScoringAPI(APIServices apiServices, String requestBody)
            throws Exception {
        // For now, we use the existing methods
        // In future, we can make APIServices more generic
        return apiServices.FWADecisioning(requestBody);
    }

    /**
     * Capitalize first letter (for error codes)
     */
    private String capitalize(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }

    /**
     * Configuration holder class
     */
    private static class ScoringConfig {
        boolean enabled;
        String apiEndpoint;
        JSONObject requestTemplate;
        JSONObject responseMapping;
    }
}