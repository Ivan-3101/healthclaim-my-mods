package com.DronaPay.frm.HealthClaim.generic.delegates;

import com.DronaPay.frm.HealthClaim.APIServices;
import com.DronaPay.frm.HealthClaim.generic.config.WorkflowStageMapping;
import com.DronaPay.frm.HealthClaim.generic.services.ConfigurationService;
import com.DronaPay.frm.HealthClaim.generic.services.ObjectStorageService;
import com.DronaPay.frm.HealthClaim.generic.storage.StorageProvider;
import com.DronaPay.frm.HealthClaim.generic.utils.StoragePathBuilder;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.util.EntityUtils;
import org.cibseven.bpm.engine.delegate.BpmnError;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

@Slf4j
public class GenericAgentExecutorDelegate implements JavaDelegate {

    private static final int MAX_PROCESS_VAR_SIZE = 3500;

    @Override
    public void execute(DelegateExecution execution) throws Exception {

        // 1. Get agent configuration
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

        // 2. Get context information
        String filename = (String) execution.getVariable("attachment");
        String ticketId = String.valueOf(execution.getVariable("TicketID"));
        String tenantId = execution.getTenantId();
        String workflowKey = StoragePathBuilder.getWorkflowType(execution);
        String taskName = StoragePathBuilder.getTaskName(execution);
        int stageNumber = WorkflowStageMapping.getStageNumber(workflowKey, taskName);

        if (stageNumber == -1) {
            log.warn("Stage number not found for task '{}', using previous stageNumber", taskName);
            stageNumber = StoragePathBuilder.getStageNumber(execution);
        }

        log.info("Stage {}: {} - Agent: {}", stageNumber, taskName, displayName);

        if (filename == null) {
            log.info("Agent '{}' is processing consolidated/non-document data", displayName);
        }

        JSONObject config = agentConfig.getJSONObject("config");

        // 3. Load workflow config from database
        Connection conn = execution.getProcessEngine()
                .getProcessEngineConfiguration()
                .getDataSource()
                .getConnection();

        JSONObject workflowConfig = ConfigurationService.loadWorkflowConfig(workflowKey, tenantId, conn);
        conn.close();

        // 4. Build request
        JSONObject requestBody = buildRequest(config, execution, filename, tenantId, agentId);

        // 5. Call agent API
        APIServices apiServices = new APIServices(tenantId, workflowConfig);
        CloseableHttpResponse response = apiServices.callAgent(requestBody.toString());

        String resp = EntityUtils.toString(response.getEntity());
        int statusCode = response.getStatusLine().getStatusCode();

        log.info("Agent '{}' API status: {}", displayName, statusCode);
        log.debug("Agent '{}' response: {}", displayName, resp);

        // 6. Process response and store in new MinIO structure
        processAndStoreResponse(agentId, displayName, statusCode, resp, config,
                execution, filename, critical, tenantId, ticketId, workflowKey, stageNumber, taskName);

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
            String varName = inputMapping.getString("variableName");
            Object varValue = execution.getVariable(varName);

            if (varValue instanceof String) {
                String strValue = (String) varValue;
                if (transformation.equals("wrapInData")) {
                    data.put("data", strValue);
                } else {
                    data.put(varName, strValue);
                }
            }

        } else if (source.equals("chainedOutput")) {
            String chainFrom = inputMapping.getString("chainFrom");
            Map<String, Map<String, Object>> fileProcessMap =
                    (Map<String, Map<String, Object>>) execution.getVariable("fileProcessMap");

            Map<String, Object> fileResults = fileProcessMap.get(filename);
            String chainedOutput = (String) fileResults.get(chainFrom + "_output");

            if (transformation.equals("wrapAnswerInData")) {
                data.put("data", chainedOutput);
            }
        }

        requestBody.put("data", data);
        requestBody.put("agentid", agentId);

        return requestBody;
    }

    @SuppressWarnings("unchecked")
    private void processAndStoreResponse(String agentId, String displayName, int statusCode, String resp,
                                         JSONObject config, DelegateExecution execution, String filename,
                                         boolean critical, String tenantId, String ticketId,
                                         String workflowKey, int stageNumber, String taskName) throws Exception {

        Map<String, Object> extractedData = new HashMap<>();

        if (statusCode == 200) {
            log.info("Agent '{}' completed successfully", displayName);

            JSONObject apiResponse = new JSONObject(resp);
            JSONObject outputMapping = config.optJSONObject("outputMapping");

            if (outputMapping != null && outputMapping.has("variablesToSet")) {
                JSONObject variablesToSet = outputMapping.getJSONObject("variablesToSet");

                for (String variableName : variablesToSet.keySet()) {
                    try {
                        JSONObject mapping = variablesToSet.getJSONObject(variableName);
                        String jsonPath = mapping.getString("jsonPath");
                        String dataType = mapping.optString("dataType", "string");

                        Object extractedValue = extractFromJsonPath(apiResponse, jsonPath);

                        if (extractedValue != null) {
                            Object convertedValue = convertValue(extractedValue, dataType);

                            if (convertedValue.toString().length() < MAX_PROCESS_VAR_SIZE) {
                                execution.setVariable(variableName, convertedValue);
                                log.debug("Set process variable '{}' = {}", variableName,
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
                        log.error("Error extracting variable '{}' from path '{}'", variableName, e);
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

        // Store full result in MinIO using NEW folder structure
        storeAgentResultNewStructure(tenantId, ticketId, workflowKey, stageNumber, taskName,
                filename, agentId, statusCode, resp, extractedData);

        // Update fileProcessMap ONLY if filename is not null
        if (filename != null) {
            Map<String, Map<String, Object>> fileProcessMap =
                    (Map<String, Map<String, Object>>) execution.getVariable("fileProcessMap");

            if (fileProcessMap == null) {
                fileProcessMap = new HashMap<>();
            }

            Map<String, Object> fileResults = fileProcessMap.getOrDefault(filename, new HashMap<>());

            Map<String, Object> agentResult = new HashMap<>();
            agentResult.put("statusCode", statusCode);
            agentResult.put("apiCall", statusCode == 200 ? "Success" : "Failure");
            agentResult.put("extractedData", extractedData);

            fileResults.put(agentId, agentResult);
            fileProcessMap.put(filename, fileResults);
            execution.setVariable("fileProcessMap", fileProcessMap);
        }
    }

    /**
     * Store agent result in NEW MinIO folder structure
     *
     * Pattern: {rootFolder}/{tenantId}/{workflowType}/{ticketId}/{stageNumber}_{taskName}/task-docs/{filename}.json
     */
    private void storeAgentResultNewStructure(String tenantId, String ticketId, String workflowKey,
                                              int stageNumber, String taskName, String filename,
                                              String agentId, int statusCode, String rawResponse,
                                              Map<String, Object> extractedData) throws Exception {

        StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);
        Properties props = ConfigurationService.getTenantProperties(tenantId);
        String rootFolder = props.getProperty("storage.minio.bucketName", "insurance-claims");

        // Build result JSON
        JSONObject result = new JSONObject();
        result.put("agentId", agentId);
        result.put("statusCode", statusCode);
        result.put("success", statusCode == 200);
        result.put("rawResponse", rawResponse);
        result.put("extractedData", extractedData);
        result.put("timestamp", System.currentTimeMillis());

        byte[] content = result.toString(2).getBytes(StandardCharsets.UTF_8);

        // Determine output filename
        String outputFilename;
        if (filename != null && !filename.isEmpty()) {
            outputFilename = filename + ".json";
        } else {
            outputFilename = agentId + "_result.json";
        }

        // Build storage path to task-docs/
        String storagePath = StoragePathBuilder.buildTaskDocsPath(
                rootFolder, tenantId, workflowKey, ticketId,
                stageNumber, taskName, outputFilename
        );

        storage.uploadDocument(storagePath, content, "application/json");

        log.info("Stored agent '{}' result at: {}", agentId, storagePath);
    }

    private Object extractFromJsonPath(JSONObject json, String path) {
        try {
            String[] parts = path.substring(1).split("/");
            Object current = json;

            for (String part : parts) {
                if (current instanceof JSONObject) {
                    current = ((JSONObject) current).opt(part);
                    if (current == null) return null;
                } else {
                    return null;
                }
            }

            return current;
        } catch (Exception e) {
            log.warn("Failed to extract from path: {}", path, e);
            return null;
        }
    }

    private Object convertValue(Object value, String dataType) {
        if (value == null) return null;

        switch (dataType.toLowerCase()) {
            case "boolean":
                if (value instanceof Boolean) return value;
                if (value instanceof String) {
                    String str = ((String) value).toLowerCase();
                    return str.equals("true") || str.equals("yes") || str.equals("1");
                }
                return false;

            case "integer":
            case "int":
                if (value instanceof Number) return ((Number) value).intValue();
                if (value instanceof String) {
                    try {
                        return Integer.parseInt((String) value);
                    } catch (NumberFormatException e) {
                        return 0;
                    }
                }
                return 0;

            case "long":
                if (value instanceof Number) return ((Number) value).longValue();
                if (value instanceof String) {
                    try {
                        return Long.parseLong((String) value);
                    } catch (NumberFormatException e) {
                        return 0L;
                    }
                }
                return 0L;

            case "double":
                if (value instanceof Number) return ((Number) value).doubleValue();
                if (value instanceof String) {
                    try {
                        return Double.parseDouble((String) value);
                    } catch (NumberFormatException e) {
                        return 0.0;
                    }
                }
                return 0.0;

            case "string":
            default:
                return value.toString();
        }
    }
}