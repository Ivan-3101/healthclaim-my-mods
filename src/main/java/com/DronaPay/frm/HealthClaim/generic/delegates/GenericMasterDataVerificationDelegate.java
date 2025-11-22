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

import java.net.URI;
import java.util.Properties;

@Slf4j
public class GenericMasterDataVerificationDelegate implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("=== Generic Master Data Verification Started ===");
        log.info("TicketID: {}", execution.getVariable("TicketID"));

        String tenantId = execution.getTenantId();

        // 1. Load configuration
        VerificationConfig config = loadConfiguration(tenantId);

        // 2. Get the identifier to verify (e.g., policy_id)
        String identifier = (String) execution.getVariable(config.identifierVariable);
        if (identifier == null || identifier.isEmpty()) {
            log.error("Identifier variable '{}' is null or empty", config.identifierVariable);
            execution.setVariable("policyFound", "no");
            execution.setVariable("statusCode", 400);
            return;
        }

        log.info("Verifying {} with value: {}", config.identifierVariable, identifier);

        // 3. Build API URL
        String apiUrl = buildApiUrl(config, identifier);
        log.info("API URL: {}", apiUrl);

        // 4. Make API call
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpGet httpGet = new HttpGet(new URI(apiUrl));
            httpGet.addHeader("X-API-Key", config.apiKey);

            try (CloseableHttpResponse response = client.execute(httpGet)) {
                int statusCode = response.getStatusLine().getStatusCode();
                String responseBody = EntityUtils.toString(response.getEntity());

                log.info("Master Data Verification Status: {}", statusCode);
                log.debug("Response: {}", responseBody);

                // 5. Set process variables
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
     * Load verification configuration from tenant properties
     */
    private VerificationConfig loadConfiguration(String tenantId) throws Exception {
        Properties props = ConfigurationService.getTenantProperties(tenantId);

        VerificationConfig config = new VerificationConfig();
        config.baseUrl = props.getProperty("springapi.url");
        config.apiKey = props.getProperty("springapi.api.key");

        // For now, endpoint pattern is hardcoded, but can be made configurable
        // In future: load from database configuration
        config.endpointPattern = "/accounts/{identifier}";
        config.identifierVariable = "policy_id";

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