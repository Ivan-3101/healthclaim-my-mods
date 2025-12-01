package com.DronaPay.frm.HealthClaim.generic.delegates;

import com.DronaPay.frm.HealthClaim.generic.services.ConfigurationService;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.camunda.bpm.engine.variable.Variables;
import org.camunda.bpm.engine.variable.value.ObjectValue;
import org.json.JSONObject;

import java.net.URI;
import java.sql.Connection;

@Slf4j
public class GenericMasterDataVerificationDelegate implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("=== Generic Master Data Verification Started ===");
        log.info("TicketID: {}", execution.getVariable("TicketID"));

        String tenantId = execution.getTenantId();
        String workflowKey = "HealthClaim"; // TODO: Make this configurable

        // 1. Load workflow configuration from database
        Connection conn = execution.getProcessEngine()
                .getProcessEngineConfiguration()
                .getDataSource()
                .getConnection();

        JSONObject workflowConfig = ConfigurationService.loadWorkflowConfig(workflowKey, tenantId, conn);
        conn.close();

        // 2. Load verification configuration from database (NO FALLBACK)
        VerificationConfig config = loadConfiguration(workflowConfig);

        // 3. Get the identifier to verify (e.g., policy_id)
        String identifier = (String) execution.getVariable(config.identifierVariable);
        if (identifier == null || identifier.isEmpty()) {
            log.error("Identifier variable '{}' is null or empty", config.identifierVariable);
            execution.setVariable("policyFound", "no");
            execution.setVariable("statusCode", 400);
            return;
        }

        log.info("Verifying {} with value: {}", config.identifierVariable, identifier);

        // 4. Build API URL
        String apiUrl = buildApiUrl(config, identifier);
        log.info("API URL: {}", apiUrl);

        // 5. Make API call
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpGet httpGet = new HttpGet(new URI(apiUrl));
            httpGet.addHeader("X-API-Key", config.apiKey);

            try (CloseableHttpResponse response = client.execute(httpGet)) {
                int statusCode = response.getStatusLine().getStatusCode();
                String responseBody = EntityUtils.toString(response.getEntity());

                log.info("Master Data Verification Status: {}", statusCode);
                log.debug("Response: {}", responseBody);

                // 6. Set process variables
                ObjectValue respJson = Variables
                        .objectValue(responseBody)
                        .serializationDataFormat("application/json")
                        .create();

                execution.setVariable("verifyResponse", respJson);
                execution.setVariable("statusCode", statusCode);

                if (statusCode == 200) {
                    execution.setVariable("policyFound", "yes");
                    log.info("Master data verification successful");
                } else {
                    execution.setVariable("policyFound", "no");
                    log.warn("Master data verification failed with status: {}", statusCode);
                }
            }
        }

        log.info("=== Generic Master Data Verification Completed ===");
    }

    /**
     * Load verification configuration from database - NO FALLBACK
     * Throws exception if configuration not found
     */
    private VerificationConfig loadConfiguration(JSONObject workflowConfig) {
        if (!workflowConfig.has("externalAPIs")) {
            throw new IllegalArgumentException(
                    "External API configuration not found in database. " +
                            "Please ensure 'externalAPIs' section exists in ui.workflowmasters.filterparams"
            );
        }

        JSONObject externalAPIs = workflowConfig.getJSONObject("externalAPIs");

        if (!externalAPIs.has("springAPI")) {
            throw new IllegalArgumentException(
                    "Spring API configuration not found in externalAPIs. " +
                            "Please add 'springAPI' section to database configuration"
            );
        }

        JSONObject springAPI = externalAPIs.getJSONObject("springAPI");

        if (!springAPI.has("baseUrl") || !springAPI.has("apiKey")) {
            throw new IllegalArgumentException(
                    "Spring API configuration incomplete. Required fields: baseUrl, apiKey"
            );
        }

        VerificationConfig config = new VerificationConfig();
        config.baseUrl = springAPI.getString("baseUrl");
        config.apiKey = springAPI.getString("apiKey");

        // Get endpoint pattern from config
        if (springAPI.has("endpoints") && springAPI.getJSONObject("endpoints").has("accounts")) {
            config.endpointPattern = springAPI.getJSONObject("endpoints").getString("accounts");
        } else {
            config.endpointPattern = "/accounts/{identifier}";
        }

        // Identifier variable name (can be made configurable)
        config.identifierVariable = "policy_id";

        log.info("Loaded master data verification config from database - URL: {}", config.baseUrl);
        return config;
    }

    /**
     * Build complete API URL by replacing placeholders
     */
    private String buildApiUrl(VerificationConfig config, String identifier) {
        return config.baseUrl + config.endpointPattern.replace("{identifier}", identifier);
    }

    /**
     * Configuration holder class
     */
    private static class VerificationConfig {
        String baseUrl;
        String apiKey;
        String endpointPattern;
        String identifierVariable;
    }
}