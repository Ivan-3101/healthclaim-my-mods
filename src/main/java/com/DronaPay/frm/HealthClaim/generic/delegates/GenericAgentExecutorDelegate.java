package com.DronaPay.frm.HealthClaim.generic.delegates;

import com.DronaPay.frm.HealthClaim.APIServices;
import com.DronaPay.frm.HealthClaim.generic.services.AgentResultStorageService;
import com.DronaPay.frm.HealthClaim.generic.services.ConfigurationService;
import com.DronaPay.frm.HealthClaim.generic.services.ObjectStorageService;
import com.DronaPay.frm.HealthClaim.generic.storage.StorageProvider;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.util.EntityUtils;
import org.cibseven.bpm.engine.delegate.BpmnError;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.json.JSONObject;

import java.io.InputStream;
import java.sql.Connection;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class GenericAgentExecutorDelegate implements JavaDelegate {

    private static final int MAX_PROCESS_VAR_SIZE = 3500;

    @Override
    public void execute(DelegateExecution execution) throws Exception {

        Object configObj = execution.getVariable("currentAgentConfig");
        if (configObj == null) {
            throw new IllegalStateException("currentAgentConfig variable not set");
        }

        JSONObject agentConfig = (JSONObject) configObj;

        String agentId = agentConfig.getString("agentId");
        String displayName = agentConfig.getString("displayName");
        boolean enabled = agentConfig.optBoolean("enabled", true);
        boolean critical = agentConfig.optBoolean("critical", false);

        log.info("=== Generic Agent Executor Started for {} ({}) ===", displayName, agentId);

        if (!enabled) {
            log.info("Agent '{}' is disabled, skipping execution", displayName);
            return;
        }

        String filename = (String) execution.getVariable("attachment");
        String ticketId = String.valueOf(execution.getVariable("TicketID"));
        String tenantId = execution.getTenantId();

        // Get stage info
        Integer stageCounter = (Integer) execution.getVariable("stageCounter");
        if (stageCounter == null) {
            stageCounter = 2; // Default if not set
        }

        String stageName = execution.getCurrentActivityName();
        if (stageName == null || stageName.isEmpty()) {
            stageName = agentId;
        } else {
            stageName = stageName.replaceAll("[^a-zA-Z0-9]+", "_");
        }

        if (filename == null) {
            log.info("Agent '{}' is processing consolidated/non-document data", displayName);
        }

        JSONObject config = agentConfig.getJSONObject("config");

        JSONObject workflowConfig = ConfigurationService.getWorkflowConfiguration(tenantId, "HealthClaim");

        JSONObject requestBody = buildRequest(config, execution, filename, tenantId, agentId);

        log.debug("Calling agent '{}' with request body size: {} bytes", displayName, requestBody.toString().length());

        APIServices apiServices = new APIServices(tenantId, workflowConfig);
        CloseableHttpResponse response = apiServices.callAgent(requestBody.toString());

        String resp = EntityUtils.toString(response.getEntity());
        int statusCode = response.getStatusLine().getStatusCode();

        log.info("Agent '{}' API status: {}", displayName, statusCode);
        log.debug("Agent '{}' response: {}", displayName, resp);

        processAndStoreResponse(agentId, displayName, statusCode, resp, config,
                execution, filename, critical, tenantId, ticketId, stageCounter, stageName);

        // Increment stage counter for next agent
        execution.setVariable("stageCounter", stageCounter + 1);

        log.info("=== Generic Agent Executor Completed for {} ===", displayName);
    }

    @SuppressWarnings("unchecked")
    private JSONObject buildRequest(JSONObject config, DelegateExecution execution,
                                    String filename, String tenantId, String agentId) throws Exception {

        JSONObject requestBody = new JSONObject();
        JSONObject data = new JSONObject();

        JSONObject inputMapping = config.getJSONObject("inputMapping");
        String source = inputMapping.getString("source");
        String transformation = inputMapping.optString("transformation", "none");

        if (source.equals("documentVariable")) {
            Map<String, String> documentPaths = (Map<String, String>) execution.getVariable("documentPaths");

            if (filename == null) {
                throw new IllegalStateException("filename is null but agent requires documentVariable source");
            }

            String storagePath = documentPaths.get(filename);

            StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);
            InputStream fileContent = storage.downloadDocument(storagePath);
            byte[] bytes = IOUtils.toByteArray(fileContent);

            if (transformation.equals("toBase64")) {
                String base64 = Base64.getEncoder().encodeToString(bytes);
                data.put("base64_img", base64);
            }

        } else if (source.equals("processVariable")) {
            String variableName = inputMapping.getString("variableName");
            Object variableValue = execution.getVariable(variableName);
            data.put("data", variableValue);

        } else if (source.equals("chainedOutput")) {
            String chainFrom = inputMapping.getString("chainFrom");
            Map<String, Map<String, Object>> fileProcessMap =
                    (Map<String, Map<String, Object>>) execution.getVariable("fileProcessMap");

            if (filename != null && fileProcessMap != null && fileProcessMap.containsKey(filename)) {
                Map<String, Object> fileResults = fileProcessMap.get(filename);
                if (fileResults.containsKey(chainFrom)) {
                    Object chainedData = fileResults.get(chainFrom);
                    if (transformation.equals("wrapAnswerInData")) {
                        JSONObject wrappedData = new JSONObject();
                        wrappedData.put("answer", chainedData);
                        data.put("data", wrappedData.toString());
                    } else {
                        data.put("data", chainedData);
                    }
                }
            }
        }

        requestBody.put("data", data);
        requestBody.put("agentid", agentId);

        log.debug("Built request for agent '{}', size: {} bytes", agentId, requestBody.toString().length());

        return requestBody;
    }

    @SuppressWarnings("unchecked")
    private void processAndStoreResponse(String agentId, String displayName, int statusCode, String resp,
                                         JSONObject config, DelegateExecution execution,
                                         String filename, boolean critical, String tenantId, String ticketId,
                                         int stageNumber, String stageName) throws Exception {

        Map<String, Object> extractedData = new HashMap<>();

        if (statusCode == 200) {
            JSONObject outputMapping = config.optJSONObject("outputMapping");
            if (outputMapping != null && outputMapping.has("variablesToSet")) {
                JSONObject variablesToSet = outputMapping.getJSONObject("variablesToSet");
                JSONObject responseJson = new JSONObject(resp);

                for (String variableName : variablesToSet.keySet()) {
                    JSONObject mapping = variablesToSet.getJSONObject(variableName);
                    String jsonPath = mapping.getString("jsonPath");
                    String dataType = mapping.optString("dataType", "string");
                    String transformationFn = mapping.optString("transformation", "none");

                    try {
                        Object value = responseJson.optQuery(jsonPath);

                        if (value != null) {
                            if (transformationFn.equals("mapSuspiciousToBoolean")) {
                                value = value.toString().toLowerCase().contains("suspicious");
                            }

                            Object convertedValue = convertValue(value, dataType);

                            if (convertedValue.toString().length() <= MAX_PROCESS_VAR_SIZE) {
                                execution.setVariable(variableName, convertedValue);
                                log.debug("Set process variable '{}': {}",
                                        variableName,
                                        convertedValue.toString().length() > 100 ?
                                                convertedValue.toString().substring(0, 100) + "..." : convertedValue);
                            } else {
                                log.warn("Skipping process variable '{}' - size {} exceeds limit. Available in MinIO.",
                                        variableName, convertedValue.toString().length());
                            }

                            extractedData.put(variableName, convertedValue);

                        } else if (mapping.has("defaultValue")) {
                            Object defaultValue = convertValue(mapping.get("defaultValue"), dataType);
                            execution.setVariable(variableName, defaultValue);
                            extractedData.put(variableName, defaultValue);
                        }
                    } catch (Exception e) {
                        log.error("Error extracting variable '{}' from path '{}'", variableName, jsonPath, e);
                    }
                }
            }
        } else {
            log.error("Agent '{}' failed with status: {}", displayName, statusCode);

            if (critical) {
                String errorCode = "agentFailure";
                if (config.has("errorHandling")) {
                    JSONObject errorHandling = config.getJSONObject("errorHandling");
                    errorCode = errorHandling.optString("errorCode", "agentFailure");
                    String onFailure = errorHandling.optString("onFailure", "throwError");

                    if (onFailure.equals("throwError")) {
                        throw new BpmnError(errorCode, String.format("Critical agent '%s' failed", displayName));
                    }
                }
            }
        }

        Map<String, Object> fullResult = AgentResultStorageService.buildResultMap(
                agentId, statusCode, resp, extractedData);

        String minioPath = AgentResultStorageService.storeAgentResultStageWise(
                tenantId, ticketId, filename, agentId, fullResult, stageNumber, stageName);

        log.info("Stored full result for '{}' in MinIO at: {}", agentId, minioPath);

        if (filename != null) {
            Map<String, Map<String, Object>> fileProcessMap =
                    (Map<String, Map<String, Object>>) execution.getVariable("fileProcessMap");

            if (fileProcessMap == null) {
                fileProcessMap = new HashMap<>();
            }

            Map<String, Object> fileResults = fileProcessMap.getOrDefault(filename, new HashMap<>());

            Map<String, Object> agentResult = new HashMap<>();
            agentResult.put("statusCode", statusCode);
            agentResult.put("minioPath", minioPath);
            agentResult.put("apiCall", statusCode == 200 ? "success" : "failed");

            if (statusCode == 200) {
                extractedData.forEach((key, value) -> {
                    if (!fileResults.containsKey(key) || fileResults.get(key) == null || fileResults.get(key).toString().isEmpty()) {
                        fileResults.put(key, value);
                    }
                });
            }

            fileResults.put(agentId, agentResult);
            fileProcessMap.put(filename, fileResults);
            execution.setVariable("fileProcessMap", fileProcessMap);

            log.debug("Updated fileProcessMap for file: {}", filename);
        }
    }

    private Object convertValue(Object value, String dataType) {
        switch (dataType.toLowerCase()) {
            case "long":
                return value instanceof Number ? ((Number) value).longValue() : Long.parseLong(value.toString());
            case "boolean":
                return value instanceof Boolean ? value : Boolean.parseBoolean(value.toString());
            case "string":
            default:
                return value.toString();
        }
    }
}