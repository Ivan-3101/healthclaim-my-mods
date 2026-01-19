package com.DronaPay.frm.HealthClaim.generic.delegates;

import com.DronaPay.frm.HealthClaim.generic.services.ConfigurationService;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.bpm.engine.variable.value.ObjectValue;
import org.json.JSONObject;

import java.net.URI;
import java.sql.Connection;

@Slf4j
public class GenericMasterDataVerificationDelegate implements JavaDelegate {

    // CONSTANTS
    private static final String IDENTIFIER_VAR = "policy_id";
    private static final String SUCCESS_VAR = "policyFound";

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("=== Generic Master Data Verification Started ===");

        // 1. Get Workflow Key from Process Variable
        String workflowKey = (String) execution.getVariable("workflowKey");

        // STRICT CHECK: Fail if missing (No more fallback to "HealthClaim")
        if (workflowKey == null || workflowKey.trim().isEmpty()) {
            throw new RuntimeException("GenericMasterDataVerification: 'workflowKey' process variable is missing! Ensure the Ticket Generator stage executed correctly.");
        }

        String tenantId = execution.getTenantId();
        log.info("Verifying Master Data for Workflow: {}", workflowKey);

        // 2. Load workflow configuration from DB
        Connection conn = execution.getProcessEngine().getProcessEngineConfiguration().getDataSource().getConnection();
        JSONObject workflowConfig = ConfigurationService.loadWorkflowConfig(workflowKey, tenantId, conn);
        conn.close();

        // 3. Load verification config (URLs, API Keys)
        VerificationConfig config = loadConfiguration(workflowConfig);

        // 4. Get the Policy ID to verify
        String identifierValue = (String) execution.getVariable(IDENTIFIER_VAR);

        if (identifierValue == null || identifierValue.isEmpty()) {
            log.error("Identifier variable '{}' is null or empty", IDENTIFIER_VAR);
            execution.setVariable(SUCCESS_VAR, "no");
            execution.setVariable("statusCode", 400);
            return;
        }

        log.info("Verifying {}: {}", IDENTIFIER_VAR, identifierValue);

        // 5. Build and Call API
        String apiUrl = buildApiUrl(config, identifierValue);

        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpGet httpGet = new HttpGet(new URI(apiUrl));
            httpGet.addHeader("X-API-Key", config.apiKey);

            try (CloseableHttpResponse response = client.execute(httpGet)) {
                int statusCode = response.getStatusLine().getStatusCode();
                String responseBody = EntityUtils.toString(response.getEntity());

                log.info("Verification Status: {}", statusCode);

                ObjectValue respJson = Variables.objectValue(responseBody)
                        .serializationDataFormat("application/json").create();

                execution.setVariable("verifyResponse", respJson);
                execution.setVariable("statusCode", statusCode);

                if (statusCode == 200) {
                    execution.setVariable(SUCCESS_VAR, "yes");
                    log.info("Verification Successful. Set {} = 'yes'", SUCCESS_VAR);
                } else {
                    execution.setVariable(SUCCESS_VAR, "no");
                    log.warn("Verification Failed. Set {} = 'no'", SUCCESS_VAR);
                }
            }
        }
        log.info("=== Generic Master Data Verification Completed ===");
    }

    private VerificationConfig loadConfiguration(JSONObject workflowConfig) {
        if (!workflowConfig.has("externalAPIs")) {
            throw new IllegalArgumentException("External API configuration not found in database.");
        }
        JSONObject externalAPIs = workflowConfig.getJSONObject("externalAPIs");

        if (!externalAPIs.has("springAPI")) {
            throw new IllegalArgumentException("Spring API configuration not found.");
        }
        JSONObject springAPI = externalAPIs.getJSONObject("springAPI");

        VerificationConfig config = new VerificationConfig();
        config.baseUrl = springAPI.getString("baseUrl");
        config.apiKey = springAPI.getString("apiKey");

        if (springAPI.has("endpoints") && springAPI.getJSONObject("endpoints").has("accounts")) {
            config.endpointPattern = springAPI.getJSONObject("endpoints").getString("accounts");
        } else {
            config.endpointPattern = "/accounts/{identifier}";
        }
        return config;
    }

    private String buildApiUrl(VerificationConfig config, String identifier) {
        return config.baseUrl + config.endpointPattern.replace("{identifier}", identifier);
    }

    private static class VerificationConfig {
        String baseUrl;
        String apiKey;
        String endpointPattern;
    }
}