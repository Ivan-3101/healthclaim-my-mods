package com.DronaPay.frm.HealthClaim.generic.delegates;

import com.DronaPay.frm.HealthClaim.APIServices;
import com.DronaPay.frm.HealthClaim.generic.services.AgentResultStorageService;
import com.DronaPay.frm.HealthClaim.generic.services.ConfigurationService;
import com.DronaPay.frm.HealthClaim.generic.services.DocumentProcessingService;
import com.DronaPay.frm.HealthClaim.generic.services.ObjectStorageService;
import com.DronaPay.frm.HealthClaim.generic.storage.StoragePathBuilder;
import com.DronaPay.frm.HealthClaim.generic.storage.StorageProvider;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.util.EntityUtils;
import org.cibseven.bpm.engine.delegate.BpmnError;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

@Slf4j
public class GenericAgentExecutorDelegate implements JavaDelegate {

    private static final int MAX_PROCESS_VAR_SIZE = 10000; // 10KB limit for process variables

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("=== Generic Agent Executor Started ===");

        // 1. Get agent configuration from process variable
        Object agentConfigObj = execution.getVariable("currentAgentConfig");
        if (agentConfigObj == null) {
            throw new IllegalStateException("currentAgentConfig not found");
        }

        JSONObject agentConfig = new JSONObject(agentConfigObj.toString());
        String agentId = agentConfig.getString("agentId");
        String displayName = agentConfig.getString("displayName");
        boolean critical = agentConfig.optBoolean("critical", false);

        log.info("Executing agent: {} ({})", displayName, agentId);

        // 2. Get execution context
        String tenantId = execution.getTenantId();
        String ticketId = String.valueOf(execution.getVariable("TicketID"));
        String workflowKey = (String) execution.getVariable("WorkflowKey");
        if (workflowKey == null) {
            workflowKey = "HealthClaim"; // Fallback
        }

        // 3. Get filename if in multi-instance loop (document-based agents)
        String filename = (String) execution.getVariable("attachment");

        // 4. Get current task name for path construction
        String taskName = StoragePathBuilder.getCurrentTaskName(execution);

        // 5. Build request payload
        JSONObject config = agentConfig.getJSONObject("config");
        JSONObject requestBody = buildRequest(config, execution, filename, tenantId, agentId, workflowKey, ticketId);

        log.debug("Request payload for {}: {}", displayName, requestBody.toString(2));

        // 6. Get workflow configuration
        Properties workflowConfig = ConfigurationService.getWorkflowConfiguration(
                execution.getProcessEngineServices(), tenantId, workflowKey);

        // 7. Call agent API
        APIServices apiServices = new APIServices(tenantId, workflowConfig);
        CloseableHttpResponse response = apiServices.callAgent(requestBody.toString());

        String resp = EntityUtils.toString(response.getEntity());
        int statusCode = response.getStatusLine().getStatusCode();

        log.info("Agent '{}' API status: {}", displayName, statusCode);
        log.debug("Agent '{}' response: {}", displayName, resp);

        // 8. Process response and store in MinIO
        processAndStoreResponse(agentId, displayName, statusCode, resp, config,
                execution, filename, critical, tenantId, workflowKey, ticketId, taskName);

        log.info("=== Generic Agent Executor Completed for {} ===", displayName);
    }

    /**
     * Build request payload based on input mapping configuration
     */
    @SuppressWarnings("unchecked")
    private JSONObject buildRequest(JSONObject config, DelegateExecution execution,
                                    String filename, String tenantId, String agentId,
                                    String workflowKey, String ticketId) throws Exception {

        JSONObject requestBody = new JSONObject();
        JSONObject data = new JSONObject();

        // Get input mapping
        JSONObject inputMapping = config.getJSONObject("inputMapping");
        String source = inputMapping.getString("source");
        String transformation = inputMapping.optString("transformation", "none");

        // Handle different source types
        if (source.equals("documentVariable")) {
            // Get document from CURRENT stage's userdoc/uploaded/
            Map<String, String> documentPaths = (Map<String, String>) execution.getVariable("documentPaths");

            if (filename == null) {
                throw new IllegalStateException("filename is null but agent requires documentVariable source");
            }

            String storagePath = documentPaths.get(filename);
            if (storagePath == null) {
                throw new IllegalStateException("No storage path found for: " + filename);
            }

            StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);
            InputStream fileContent = storage.downloadDocument(storagePath);
            byte[] bytes = IOUtils.toByteArray(fileContent);
            fileContent.close();

            if (transformation.equals("toBase64")) {
                String base64 = Base64.getEncoder().encodeToString(bytes);
                data.put("base64_img", base64);
            } else {
                throw new IllegalArgumentException("Unsupported transformation: " + transformation);
            }

        } else if (source.equals("processVariable")) {
            // Get data from process variable
            String variableName = inputMapping.getString("variableName");
            Object variableValue = execution.getVariable(variableName);

            if (variableValue == null) {
                throw new IllegalStateException("Process variable not found: " + variableName);
            }

            // Apply transformation
            if (transformation.equals("parseJson")) {
                JSONObject jsonValue = new JSONObject(variableValue.toString());
                data = jsonValue; // Use entire JSON
            } else {
                data = new JSONObject(variableValue.toString());
            }

        } else if (source.equals("chainedOutput")) {
            // Chain from previous agent's output
            String chainFrom = inputMapping.getString("chainFrom");
            String chainTransformation = inputMapping.optString("transformation", "none");

            // Get previous agent's result from current stage's task-docs
            String previousResultPath = StoragePathBuilder.buildTaskDocsPath(
                    tenantId, workflowKey, ticketId,
                    StoragePathBuilder.getCurrentTaskName(execution),
                    filename + ".json"
            );

            // If not found in current stage, try to get from fileProcessMap
            @SuppressWarnings("unchecked")
            Map<String, Map<String, Object>> fileProcessMap =
                    (Map<String, Map<String, Object>>) execution.getVariable("fileProcessMap");

            if (fileProcessMap != null && filename != null) {
                Map<String, Object> fileResults = fileProcessMap.get(filename);
                if (fileResults != null && fileResults.containsKey(chainFrom + "Output")) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> previousOutput = (Map<String, Object>) fileResults.get(chainFrom + "Output");
                    String minioPath = (String) previousOutput.get("minioPath");

                    if (minioPath != null) {
                        Map<String, Object> result = AgentResultStorageService.retrieveAgentResult(tenantId, minioPath);
                        String rawResponse = (String) result.get("apiResponse");

                        if (chainTransformation.equals("wrapAnswerInData")) {
                            JSONObject responseJson = new JSONObject(rawResponse);
                            if (responseJson.has("answer")) {
                                data.put("data", responseJson.get("answer"));
                            } else {
                                data.put("data", rawResponse);
                            }
                        } else {
                            data = new JSONObject(rawResponse);
                        }
                    }
                }
            }
        }

        requestBody.put("agentid", agentId);
        requestBody.put("data", data);

        return requestBody;
    }

    /**
     * Process API response, extract variables, and store result in MinIO
     */
    @SuppressWarnings("unchecked")
    private void processAndStoreResponse(String agentId, String displayName, int statusCode,
                                         String resp, JSONObject config, DelegateExecution execution,
                                         String filename, boolean critical, String tenantId,
                                         String workflowKey, String ticketId, String taskName) throws Exception {

        Map<String, Object> extractedData = new HashMap<>();

        if (statusCode == 200) {
            log.info("Agent '{}' succeeded", displayName);

            // Extract output mappings
            if (config.has("outputMapping")) {
                JSONObject outputMapping = config.getJSONObject("outputMapping");
                JSONObject variablesToSet = outputMapping.getJSONObject("variablesToSet");

                for (String variableName : variablesToSet.keySet()) {
                    try {
                        JSONObject mapping = variablesToSet.getJSONObject(variableName);
                        String jsonPath = mapping.getString("jsonPath");
                        String dataType = mapping.optString("dataType", "string");

                        // Extract value using jsonPath
                        Object extractedValue = extractValueFromJsonPath(resp, jsonPath);

                        // Apply transformation if specified
                        if (mapping.has("transformation")) {
                            extractedValue = applyTransformation(extractedValue, mapping.getString("transformation"));
                        }

                        // Convert to target data type
                        Object convertedValue = convertValue(extractedValue, dataType);

                        // Set as process variable only if size is reasonable
                        if (shouldSetProcessVariable(variableName, convertedValue)) {
                            execution.setVariable(variableName, convertedValue);
                            log.info("Set process variable '{}' = {}", variableName,
                                    convertedValue.toString().length() > 100 ?
                                            convertedValue.toString().substring(0, 100) + "..." : convertedValue);
                        } else {
                            log.warn("Skipping process variable '{}' - size {} exceeds limit. Available in MinIO.",
                                    variableName, convertedValue.toString().length());
                        }

                        // Always add to extracted data for MinIO storage
                        extractedData.put(variableName, convertedValue);

                    } catch (Exception e) {
                        log.error("Error extracting variable '{}' from path '{}'", variableName,
                                mapping.getString("jsonPath"), e);

                        // Use default value if specified
                        if (mapping.has("defaultValue")) {
                            Object defaultValue = convertValue(mapping.get("defaultValue"),
                                    mapping.optString("dataType", "string"));
                            execution.setVariable(variableName, defaultValue);
                            extractedData.put(variableName, defaultValue);
                        }
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

        // Store full result in MinIO using NEW structure
        Map<String, Object> fullResult = AgentResultStorageService.buildResultMap(
                agentId, statusCode, resp, extractedData);

        String minioPath = AgentResultStorageService.storeAgentResult(
                tenantId, workflowKey, ticketId, taskName, filename, fullResult);

        log.info("Stored full result for '{}' in MinIO at: {}", agentId, minioPath);

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
            agentResult.put("apiCall", statusCode == 200 ? "success" : "failed");

            fileResults.put(agentId + "Output", agentResult);
            fileProcessMap.put(filename, fileResults);

            execution.setVariable("fileProcessMap", fileProcessMap);

            log.info("Updated fileProcessMap with {} result for {}", agentId, filename);
        } else {
            // For non-document agents, store result path directly
            log.info("Agent '{}' is non-document agent, storing result path directly", agentId);
            execution.setVariable(agentId + "MinioPath", minioPath);
            execution.setVariable(agentId + "StatusCode", statusCode);
            execution.setVariable(agentId + "Success", statusCode == 200);
        }
    }

    /**
     * Extract value from JSON using simple path notation
     */
    private Object extractValueFromJsonPath(String jsonString, String jsonPath) throws Exception {
        JSONObject json = new JSONObject(jsonString);

        // Simple path parsing (e.g., "/answer", "/answer/missing_documents")
        String[] parts = jsonPath.split("/");
        Object current = json;

        for (String part : parts) {
            if (part.isEmpty()) continue;

            if (current instanceof JSONObject) {
                current = ((JSONObject) current).get(part);
            } else if (current instanceof JSONArray) {
                int index = Integer.parseInt(part);
                current = ((JSONArray) current).get(index);
            }
        }

        return current;
    }

    /**
     * Apply transformation to extracted value
     */
    private Object applyTransformation(Object value, String transformation) {
        switch (transformation) {
            case "mapSuspiciousToBoolean":
                if (value instanceof String) {
                    String strValue = ((String) value).toLowerCase();
                    return strValue.contains("suspicious") || strValue.contains("forged");
                }
                return false;
            default:
                return value;
        }
    }

    /**
     * Check if a process variable should be set based on size constraints
     */
    private boolean shouldSetProcessVariable(String variableName, Object value) {
        if (value == null) {
            return true;
        }

        String stringValue = value.toString();
        int size = stringValue.length();

        return size <= MAX_PROCESS_VAR_SIZE;
    }

    /**
     * Convert value to specified data type
     */
    private Object convertValue(Object value, String dataType) {
        if (value == null) {
            return null;
        }

        switch (dataType.toLowerCase()) {
            case "long":
            case "integer":
                if (value instanceof Number) {
                    return ((Number) value).longValue();
                }
                return Long.parseLong(value.toString());
            case "double":
            case "float":
                if (value instanceof Number) {
                    return ((Number) value).doubleValue();
                }
                return Double.parseDouble(value.toString());
            case "boolean":
                if (value instanceof Boolean) {
                    return value;
                }
                return Boolean.parseBoolean(value.toString());
            case "json":
                if (value instanceof JSONObject) {
                    return value.toString();
                }
                return new JSONObject(value.toString()).toString();
            case "string":
            default:
                return value.toString();
        }
    }
}