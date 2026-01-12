package com.DronaPay.frm.HealthClaim.generic.delegates;

import com.DronaPay.frm.HealthClaim.generic.services.AgentResultStorageService;
import com.DronaPay.frm.HealthClaim.generic.services.ConfigurationService;
import com.DronaPay.frm.HealthClaim.generic.services.ObjectStorageService;
import com.DronaPay.frm.HealthClaim.generic.storage.StorageProvider;
import com.DronaPay.frm.HealthClaim.generic.utils.StageHelper;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class GenericAgentExecutorDelegate implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) throws Exception {

        // Get stage info
        int stageNumber = StageHelper.getOrIncrementStage(execution);
        String taskName = execution.getCurrentActivityName();

        log.info("=== Agent Execution at Stage {}: {} ===", stageNumber, taskName);

        String tenantId = (String) execution.getVariable("tenantId");
        String ticketId = (String) execution.getVariable("ticketId");
        String agentId = (String) execution.getVariable("agentId");
        String filename = (String) execution.getVariable("filename");

        // Load config
        JSONObject config = ConfigurationService.getWorkflowConfiguration(tenantId, "HealthClaim");
        JSONObject agentAPIConfig = config.getJSONObject("externalAPIs").getJSONObject("agentAPI");

        String baseUrl = agentAPIConfig.getString("baseUrl");
        String username = agentAPIConfig.getString("username");
        String password = agentAPIConfig.getString("password");
        String endpoint = agentAPIConfig.getJSONObject("endpoints").getString("agent");

        // Find agent config
        JSONObject agentConfig = null;
        for (Object obj : config.getJSONArray("agents")) {
            JSONObject agent = (JSONObject) obj;
            if (agent.getString("agentId").equals(agentId)) {
                agentConfig = agent;
                break;
            }
        }

        if (agentConfig == null) {
            throw new RuntimeException("Agent config not found: " + agentId);
        }

        log.info("Executing agent: {} ({})", agentConfig.getString("displayName"), agentId);

        // Get document from MinIO
        @SuppressWarnings("unchecked")
        Map<String, String> docPaths = (Map<String, String>) execution.getVariable("documentPaths");
        String docPath = docPaths.get(filename);

        StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);
        byte[] docBytes;
        try (InputStream stream = storage.downloadDocument(docPath)) {
            docBytes = stream.readAllBytes();
        }

        // Build agent request
        String base64Doc = Base64.getEncoder().encodeToString(docBytes);

        JSONObject requestBody = new JSONObject();
        requestBody.put("agent_id", agentId);
        requestBody.put("data", base64Doc);

        log.debug("Built request for agent '{}' with {} bytes of data", agentId, base64Doc.length());

        // Call agent API
        String apiUrl = baseUrl + endpoint;
        String authHeader = "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));

        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost httpPost = new HttpPost(apiUrl);
            httpPost.setHeader("Authorization", authHeader);
            httpPost.setHeader("Content-Type", "application/json");
            httpPost.setEntity(new StringEntity(requestBody.toString(), ContentType.APPLICATION_JSON));

            try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                int statusCode = response.getCode();
                String responseBody = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);

                log.info("Agent '{}' response: {}", agentId, statusCode);

                // Parse response
                JSONObject responseJson = new JSONObject(responseBody);

                // Build result map
                Map<String, Object> fullResult = new HashMap<>();
                fullResult.put("agentId", agentId);
                fullResult.put("statusCode", statusCode);
                fullResult.put("timestamp", System.currentTimeMillis());
                fullResult.put("response", responseJson.toMap());

                // Store in MinIO
                String storagePath = AgentResultStorageService.storeAgentResultStageWise(
                        tenantId, ticketId, filename, agentId, fullResult, stageNumber, taskName
                );

                // Set process variable with path
                String varName = filename.replaceAll("[^a-zA-Z0-9]", "_") + "_" + agentId + "_path";
                execution.setVariable(varName, storagePath);

                // Handle output mapping if configured
                if (agentConfig.has("config")) {
                    JSONObject agentCfg = agentConfig.getJSONObject("config");
                    if (agentCfg.has("outputMapping")) {
                        JSONObject outputMapping = agentCfg.getJSONObject("outputMapping");
                        if (outputMapping.has("variablesToSet")) {
                            JSONObject varsToSet = outputMapping.getJSONObject("variablesToSet");

                            for (String key : varsToSet.keySet()) {
                                JSONObject varConfig = varsToSet.getJSONObject(key);
                                String jsonPath = varConfig.optString("jsonPath", "");

                                // Simple JSON path extraction
                                Object value = extractFromJsonPath(responseJson, jsonPath);
                                if (value != null) {
                                    execution.setVariable(key, value);
                                    log.debug("Set variable '{}' = {}", key, value);
                                }
                            }
                        }
                    }
                }

                log.info("Agent '{}' completed successfully", agentId);

            }
        }
    }

    private Object extractFromJsonPath(JSONObject json, String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }

        // Simple path extraction: /key1/key2
        String[] parts = path.split("/");
        Object current = json;

        for (String part : parts) {
            if (part.isEmpty()) continue;

            if (current instanceof JSONObject) {
                JSONObject obj = (JSONObject) current;
                if (obj.has(part)) {
                    current = obj.get(part);
                } else {
                    return null;
                }
            } else {
                return null;
            }
        }

        return current;
    }
}