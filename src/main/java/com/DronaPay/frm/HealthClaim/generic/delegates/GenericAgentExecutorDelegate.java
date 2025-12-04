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
import org.camunda.bpm.engine.delegate.BpmnError;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.json.JSONObject;

import java.io.InputStream;
import java.sql.Connection;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class GenericAgentExecutorDelegate implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("=== Generic Agent Executor Started ===");

        String tenantId = execution.getTenantId();
        String ticketId = String.valueOf(execution.getVariable("TicketID"));
        String filename = (String) execution.getVariable("attachment");

        log.info("Processing: TicketID={}, File={}", ticketId, filename);

        // 1. Get agent configuration
        Object agentConfigObj = execution.getVariable("currentAgentConfig");
        if (agentConfigObj == null) {
            throw new IllegalArgumentException("currentAgentConfig variable is required");
        }

        JSONObject agentConfig;
        if (agentConfigObj instanceof String) {
            agentConfig = new JSONObject((String) agentConfigObj);
        } else if (agentConfigObj instanceof JSONObject) {
            agentConfig = (JSONObject) agentConfigObj;
        } else {
            throw new IllegalArgumentException("currentAgentConfig must be JSONObject or String");
        }

        // 2. Extract agent details
        String agentId = agentConfig.getString("agentId");
        String displayName = agentConfig.optString("displayName", agentId);
        boolean enabled = agentConfig.optBoolean("enabled", true);
        boolean critical = agentConfig.optBoolean("critical", false);

        log.info("Executing agent: {} ({}), Critical: {}", displayName, agentId, critical);

        if (!enabled) {
            log.info("Agent '{}' is disabled, skipping", displayName);
            return;
        }

        JSONObject config = agentConfig.getJSONObject("config");

        // 3. Load workflow configuration
        Connection conn = execution.getProcessEngine()
                .getProcessEngineConfiguration()
                .getDataSource()
                .getConnection();

        JSONObject workflowConfig = ConfigurationService.loadWorkflowConfig("HealthClaim", tenantId, conn);
        conn.close();

        // 4. Build request based on input mapping
        JSONObject requestBody = buildRequest(config, execution, filename, tenantId, agentId);

        // 5. Call agent API
        APIServices apiServices = new APIServices(tenantId, workflowConfig);
        CloseableHttpResponse response = apiServices.callAgent(requestBody.toString());

        String resp = EntityUtils.toString(response.getEntity());
        int statusCode = response.getStatusLine().getStatusCode();

        log.info("Agent '{}' API status: {}", displayName, statusCode);
        log.debug("Agent '{}' response: {}", displayName, resp);

        // 6. Process response, extract data, and store in MinIO
        processAndStoreResponse(agentId, displayName, statusCode, resp, config,
                execution, filename, critical, tenantId, ticketId);

        log.info("=== Generic Agent Executor Completed for {} ===", displayName);
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
        }

        requestBody.put("data", data);
        requestBody.put("agentid", agentId);

        log.debug("Built request for agent '{}' with {} bytes of data", agentId, requestBody.toString().length());

        return requestBody;
    }

    /**
     * Process agent response, extract data, and store in MinIO
     */
    @SuppressWarnings("unchecked")
    private void processAndStoreResponse(String agentId, String displayName, int statusCode, String resp,
                                         JSONObject config, DelegateExecution execution,
                                         String filename, boolean critical, String tenantId, String ticketId) throws Exception {

        // Build result map
        Map<String, Object> extractedData = new HashMap<>();

        if (statusCode == 200) {
            // Parse response and extract variables based on output mapping
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
                            // Apply transformation
                            if (transformationFn.equals("mapSuspiciousToBoolean")) {
                                value = !value.toString().equals("<Not Suspicious>");
                            }

                            // Convert to data type
                            Object convertedValue = convertValue(value, dataType);

                            // Set process variable
                            execution.setVariable(variableName, convertedValue);

                            // Add to extracted data for storage
                            extractedData.put(variableName, convertedValue);

                            log.info("Set variable '{}' = {}", variableName, convertedValue);
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

            // Handle error based on configuration
            JSONObject errorHandling = config.optJSONObject("errorHandling");
            if (errorHandling != null) {
                String onFailure = errorHandling.optString("onFailure", "logAndContinue");
                boolean continueOnError = errorHandling.optBoolean("continueOnError", !critical);

                if (!continueOnError || onFailure.equals("throwError")) {
                    String errorCode = errorHandling.optString("errorCode", "agentFailure");
                    throw new BpmnError(errorCode, "Agent '" + displayName + "' failed with status: " + statusCode);
                }
            } else if (critical) {
                throw new BpmnError("agentFailure", "Critical agent '" + displayName + "' failed");
            }
        }

        // Store result in MinIO
        Map<String, Object> resultToStore = AgentResultStorageService.buildResultMap(
                agentId, statusCode, resp, extractedData
        );

        String storagePath = AgentResultStorageService.storeAgentResult(
                tenantId, ticketId, filename, agentId, resultToStore
        );

        // Update fileProcessMap with storage path
        Map<String, Map<String, Object>> fileProcessMap =
                (Map<String, Map<String, Object>>) execution.getVariable("fileProcessMap");

        if (fileProcessMap == null) {
            fileProcessMap = new HashMap<>();
        }

        Map<String, Object> fileResults = fileProcessMap.getOrDefault(filename, new HashMap<>());

        // Store MinIO path instead of inline data
        Map<String, Object> agentResult = new HashMap<>();
        agentResult.put("storagePath", storagePath);
        agentResult.put("statusCode", statusCode);
        agentResult.put("apiCall", statusCode == 200 ? "success" : "failed");

        fileResults.put(agentId + "Output", agentResult);
        fileProcessMap.put(filename, fileResults);

        execution.setVariable("fileProcessMap", fileProcessMap);

        log.info("Stored {} result in MinIO: {}", agentId, storagePath);
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