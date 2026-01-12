package com.DronaPay.frm.HealthClaim.generic.delegates;

import com.DronaPay.frm.HealthClaim.APIServices;
import com.DronaPay.frm.HealthClaim.generic.services.AgentResultStorageService;
import com.DronaPay.frm.HealthClaim.generic.services.ConfigurationService;
import com.DronaPay.frm.HealthClaim.generic.services.ObjectStorageService;
import com.DronaPay.frm.HealthClaim.generic.storage.StorageProvider;
import com.DronaPay.frm.HealthClaim.generic.utils.StageHelper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.util.EntityUtils;
import org.cibseven.bpm.engine.delegate.BpmnError;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Generic Agent Executor Delegate with Stage Support
 * Executes any AI agent based on configuration passed via currentAgentConfig variable
 */
@Slf4j
public class GenericAgentExecutorDelegate implements JavaDelegate {

    // Maximum size for process variables to prevent VARCHAR overflow
    private static final int MAX_PROCESS_VAR_SIZE = 3500; // Leave buffer below 4000 limit

    @Override
    public void execute(DelegateExecution execution) throws Exception {

        // Get stage number and task name FIRST
        int stageNumber = StageHelper.getOrIncrementStage(execution);
        String taskName = execution.getCurrentActivityName();
        log.info("=== Agent Execution at Stage {}: {} ===", stageNumber, taskName);

        // 1. Get agent configuration from process variable
        Object configObj = execution.getVariable("currentAgentConfig");
        if (configObj == null) {
            throw new IllegalStateException("currentAgentConfig variable not set");
        }

        JSONObject agentConfig = (JSONObject) configObj;

        String agentId = agentConfig.getString("agentId");
        String displayName = agentConfig.getString("displayName");
        boolean enabled = agentConfig.optBoolean("enabled", true);
        boolean critical = agentConfig.optBoolean("critical", false);

        log.info("Executing agent: {} ({})", displayName, agentId);

        if (!enabled) {
            log.info("Agent '{}' is disabled, skipping execution", displayName);
            return;
        }

        // 2. Get current filename and ticket info
        String filename = (String) execution.getVariable("attachment");
        String ticketId = String.valueOf(execution.getVariable("TicketID"));
        String tenantId = execution.getTenantId();

        if (filename == null) {
            log.info("Agent '{}' is processing consolidated/non-document data", displayName);
        }

        JSONObject config = agentConfig.getJSONObject("config");

        // 3. Load workflow configuration
        JSONObject workflowConfig = ConfigurationService.getWorkflowConfiguration(tenantId, "HealthClaim");

        // 4. Build request
        JSONObject requestBody = buildRequest(config, execution, filename, tenantId, agentId);

        // 5. Call agent API
        APIServices apiServices = new APIServices(tenantId, workflowConfig);
        CloseableHttpResponse response = apiServices.callAgent(requestBody.toString());

        String resp = EntityUtils.toString(response.getEntity());
        int statusCode = response.getStatusLine().getStatusCode();

        log.info("Agent '{}' API status: {}", displayName, statusCode);
        log.debug("Agent '{}' response: {}", displayName, resp);

        // 6. Process response, extract data, and store in MinIO with stage info
        processAndStoreResponse(agentId, displayName, statusCode, resp, config,
                execution, filename, critical, tenantId, ticketId, stageNumber, taskName);

        log.info("=== Agent Execution Completed at Stage {} ===", stageNumber);
    }

    /**
     * Build request payload based on input mapping configuration
     */
    @SuppressWarnings("unchecked")
    private JSONObject buildRequest(JSONObject config, DelegateExecution execution,
                                    String filename, String tenantId, String agentId) throws Exception {

        JSONObject requestBody = new JSONObject();
        JSONObject data = new JSONObject();

        // Get input mapping
        JSONObject inputMapping = config.getJSONObject("inputMapping");
        String source = inputMapping.getString("source");
        String transformation = inputMapping.optString("transformation", "none");

        // Handle different source types
        if (source.equals("documentVariable")) {
            // Get document from storage and convert to base64
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
            // Get data from process variable
            String variableName = inputMapping.getString("variableName");
            Object value = execution.getVariable(variableName);

            if (value != null) {
                // Add to data based on variable name convention
                if (variableName.equals("ocr_text")) {
                    data.put("ocr_text", value);
                } else if (variableName.equals("fhir_json")) {
                    data.put("doc_fhir", new JSONObject(value.toString()));
                } else if (variableName.equals("consolidatedFhir") || variableName.equals("fhirConsolidatedRequest")) {
                    data.put("consolidated_fhir", new JSONObject(value.toString()));
                } else {
                    data.put(variableName, value);
                }
            }

            // Handle additional inputs
            if (inputMapping.has("additionalInputs")) {
                JSONObject additionalInputs = inputMapping.getJSONObject("additionalInputs");
                for (String key : additionalInputs.keySet()) {
                    String varName = additionalInputs.getString(key);
                    Object additionalValue = execution.getVariable(varName);
                    if (additionalValue != null) {
                        data.put(key, additionalValue);
                    }
                }
            }
        } else if (source.equals("chainedOutput")) {
            // Chained from previous agent
            String chainFrom = inputMapping.getString("chainFrom");
            String transformationType = inputMapping.optString("transformation", "none");

            // Get previous agent output from fileProcessMap
            Map<String, Map<String, Object>> fileProcessMap =
                    (Map<String, Map<String, Object>>) execution.getVariable("fileProcessMap");

            if (filename != null && fileProcessMap.containsKey(filename)) {
                Map<String, Object> fileResults = fileProcessMap.get(filename);
                Object previousOutput = fileResults.get(chainFrom);

                if (previousOutput != null) {
                    if (transformationType.equals("wrapAnswerInData")) {
                        data.put("data", new JSONObject(previousOutput.toString()));
                    } else {
                        data.put("chainedData", previousOutput);
                    }
                }
            }
        }

        requestBody.put("data", data);
        requestBody.put("agentid", agentId);

        log.debug("Built request for agent '{}' with {} bytes of data", agentId, requestBody.toString().length());
        return requestBody;
    }

    /**
     * Process agent response and store results with stage information
     */
    @SuppressWarnings("unchecked")
    private void processAndStoreResponse(String agentId, String displayName, int statusCode,
                                         String resp, JSONObject config, DelegateExecution execution,
                                         String filename, boolean critical, String tenantId,
                                         String ticketId, int stageNumber, String taskName) throws Exception {

        Map<String, Object> extractedData = new HashMap<>();

        if (statusCode == 200) {
            // Success - extract variables based on output mapping
            JSONObject outputMapping = config.optJSONObject("outputMapping");

            if (outputMapping != null && outputMapping.has("variablesToSet")) {
                JSONObject variablesToSet = outputMapping.getJSONObject("variablesToSet");
                JSONObject responseJson = new JSONObject(resp);

                for (String variableName : variablesToSet.keySet()) {
                    try {
                        JSONObject mapping = variablesToSet.getJSONObject(variableName);
                        String jsonPath = mapping.getString("jsonPath");
                        String dataType = mapping.optString("dataType", "string");

                        Object value = extractValueFromJsonPath(responseJson, jsonPath);

                        if (value != null) {
                            Object convertedValue = convertValue(value, dataType);

                            // Only set as process variable if size is manageable
                            if (convertedValue.toString().length() < MAX_PROCESS_VAR_SIZE) {
                                execution.setVariable(variableName, convertedValue);
                                log.debug("Set process variable '{}': {}", variableName,
                                        convertedValue.toString().length() > 100 ?
                                                convertedValue.toString().substring(0, 100) + "..." : convertedValue);
                            } else {
                                log.warn("Skipping process variable '{}' - size {} exceeds limit. Available in MinIO.",
                                        variableName, convertedValue.toString().length());
                            }

                            // Always add to extracted data for MinIO storage
                            extractedData.put(variableName, convertedValue);

                        } else if (mapping.has("defaultValue")) {
                            Object defaultValue = convertValue(mapping.get("defaultValue"), dataType);
                            execution.setVariable(variableName, defaultValue);
                            extractedData.put(variableName, defaultValue);
                        }
                    } catch (Exception e) {
                        log.error("Error extracting variable '{}' from response", variableName, e);
                    }
                }
            }
        } else {
            log.error("Agent '{}' failed with status: {}", displayName, statusCode);

            // Handle critical agents
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

        // Store full result in MinIO with stage information
        Map<String, Object> fullResult = AgentResultStorageService.buildResultMap(
                agentId, statusCode, resp, extractedData);

        // Store with stage-based path
        String minioPath = AgentResultStorageService.storeAgentResultStageWise(
                tenantId, ticketId, filename, agentId, fullResult, stageNumber, taskName);

        log.info("Stored agent result at stage {}: {}", stageNumber, minioPath);

        // Update fileProcessMap ONLY if filename is not null (document-based agents)
        if (filename != null) {
            Map<String, Map<String, Object>> fileProcessMap =
                    (Map<String, Map<String, Object>>) execution.getVariable("fileProcessMap");

            if (fileProcessMap == null) {
                fileProcessMap = new HashMap<>();
            }

            Map<String, Object> fileResults = fileProcessMap.getOrDefault(filename, new HashMap<>());

            // Add agent result to file results
            Map<String, Object> agentResult = new HashMap<>();
            agentResult.put("statusCode", statusCode);
            agentResult.put("minioPath", minioPath);
            agentResult.put("stageNumber", stageNumber);
            agentResult.put("apiCall", statusCode == 200 ? "SUCCESS" : "FAILED");

            // Store extracted data for chaining
            if (!extractedData.isEmpty()) {
                agentResult.putAll(extractedData);
            }

            fileResults.put(agentId, agentResult);
            fileProcessMap.put(filename, fileResults);
            execution.setVariable("fileProcessMap", fileProcessMap);
        }
    }

    /**
     * Extract value from JSON using path notation
     */
    private Object extractValueFromJsonPath(JSONObject json, String path) {
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

    /**
     * Convert value to specified data type
     */
    private Object convertValue(Object value, String dataType) {
        if (value == null) return null;

        switch (dataType.toLowerCase()) {
            case "string":
                return value.toString();
            case "boolean":
                if (value instanceof Boolean) return value;
                return Boolean.parseBoolean(value.toString());
            case "long":
                if (value instanceof Number) return ((Number) value).longValue();
                return Long.parseLong(value.toString());
            case "integer":
            case "int":
                if (value instanceof Number) return ((Number) value).intValue();
                return Integer.parseInt(value.toString());
            case "double":
                if (value instanceof Number) return ((Number) value).doubleValue();
                return Double.parseDouble(value.toString());
            default:
                return value;
        }
    }
}