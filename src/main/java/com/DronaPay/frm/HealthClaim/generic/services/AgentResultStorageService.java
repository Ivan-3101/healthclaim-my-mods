package com.DronaPay.frm.HealthClaim.generic.services;

import com.DronaPay.frm.HealthClaim.generic.storage.StorageProvider;
import com.DronaPay.frm.HealthClaim.generic.utils.StageHelper;
import lombok.extern.slf4j.Slf4j;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class AgentResultStorageService {

    /**
     * Store agent result with AUTO-DETECTED stage info
     * Call this from ANY delegate - it auto-detects stage number and task name
     */
    public static String storeAgentResultStageWise(DelegateExecution execution,
                                                   String tenantId, String ticketId,
                                                   String filename, String agentId,
                                                   Map<String, Object> result) throws Exception {

        // Auto-detect stage and task name
        int stageNumber = StageHelper.getOrIncrementStage(execution);
        String taskName = execution.getCurrentActivityName();

        return storeAgentResultStageWise(tenantId, ticketId, filename, agentId, result,
                stageNumber, taskName);
    }

    /**
     * Store agent result in MinIO with stage-based structure
     * Path: {tenantId}/{workflowKey}/{ticketId}/{stage#}_{TaskName}/task-docs/{filename}.json
     */
    public static String storeAgentResultStageWise(String tenantId, String ticketId,
                                                   String filename, String agentId,
                                                   Map<String, Object> result,
                                                   int stageNumber, String taskName) throws Exception {

        StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);

        String safeFilename = (filename != null && !filename.isEmpty())
                ? filename.replaceAll("[^a-zA-Z0-9.-]", "_")
                : "consolidated";

        String stageFolderName = StageHelper.buildStageFolderName(stageNumber, taskName);

        String storagePath = String.format("%s/HealthClaim/%s/%s/task-docs/%s.json",
                tenantId, ticketId, stageFolderName, safeFilename);

        JSONObject resultJson = new JSONObject(result);
        byte[] content = resultJson.toString(2).getBytes(StandardCharsets.UTF_8);

        storage.uploadDocument(storagePath, content, "application/json");

        log.info("Stored {} result at stage {}: {}", agentId, stageNumber, storagePath);

        return storagePath;
    }

    /**
     * Retrieve agent result with AUTO-DETECTED stage info
     * Call this from ANY delegate - it auto-detects stage number and task name
     */
    public static Map<String, Object> retrieveAgentResult(DelegateExecution execution,
                                                          String tenantId, String ticketId,
                                                          String filename) throws Exception {

        // Auto-detect current stage
        int stageNumber = StageHelper.getCurrentStage(execution);
        String taskName = execution.getCurrentActivityName();

        return retrieveAgentResult(tenantId, ticketId, filename, stageNumber, taskName);
    }

    /**
     * Retrieve agent result from MinIO using stage-based path
     */
    public static Map<String, Object> retrieveAgentResult(String tenantId, String ticketId,
                                                          String filename, int stageNumber,
                                                          String taskName) throws Exception {

        StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);

        String safeFilename = (filename != null && !filename.isEmpty())
                ? filename.replaceAll("[^a-zA-Z0-9.-]", "_")
                : "consolidated";

        String stageFolderName = StageHelper.buildStageFolderName(stageNumber, taskName);

        String storagePath = String.format("%s/HealthClaim/%s/%s/task-docs/%s.json",
                tenantId, ticketId, stageFolderName, safeFilename);

        try (InputStream stream = storage.downloadDocument(storagePath)) {
            byte[] content = stream.readAllBytes();
            String jsonString = new String(content, StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(jsonString);

            Map<String, Object> result = new HashMap<>();

            if (json.has("response")) {
                result.put("statusCode", json.optInt("statusCode", 200));
                result.put("agentId", json.optString("agentId"));
                result.put("timestamp", json.optLong("timestamp"));

                Object responseObj = json.get("response");
                if (responseObj instanceof JSONObject) {
                    JSONObject responseJson = (JSONObject) responseObj;
                    responseJson.keySet().forEach(key -> result.put(key, responseJson.get(key)));
                }

                if (json.has("extractedData")) {
                    JSONObject extractedData = json.getJSONObject("extractedData");
                    extractedData.keySet().forEach(key -> result.put(key, extractedData.get(key)));
                }
            } else {
                json.keySet().forEach(key -> result.put(key, json.get(key)));
            }

            log.debug("Retrieved agent result from stage {}: {}", stageNumber, storagePath);
            return result;

        } catch (Exception e) {
            log.error("Failed to retrieve from stage {}: {}", stageNumber, storagePath, e);
            throw new RuntimeException("Could not retrieve from stage " + stageNumber + ": " + storagePath, e);
        }
    }

    /**
     * Retrieve agent result by MinIO path directly
     * Use when you have the exact path stored in fileProcessMap
     */
    public static Map<String, Object> retrieveAgentResultByPath(String tenantId, String minioPath) throws Exception {
        StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);

        try (InputStream stream = storage.downloadDocument(minioPath)) {
            byte[] content = stream.readAllBytes();
            String jsonString = new String(content, StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(jsonString);

            Map<String, Object> result = new HashMap<>();
            json.keySet().forEach(key -> result.put(key, json.get(key)));

            log.debug("Retrieved agent result from path: {}", minioPath);
            return result;

        } catch (Exception e) {
            log.error("Failed to retrieve from path: {}", minioPath, e);
            throw new RuntimeException("Could not retrieve from: " + minioPath, e);
        }
    }

    /**
     * Build comprehensive result map from agent response
     */
    public static Map<String, Object> buildResultMap(String agentId, int statusCode,
                                                     String response, Map<String, Object> extractedData) {
        Map<String, Object> result = new HashMap<>();
        result.put("agentId", agentId);
        result.put("statusCode", statusCode);
        result.put("timestamp", System.currentTimeMillis());

        if (statusCode == 200) {
            try {
                JSONObject responseJson = new JSONObject(response);
                result.put("response", responseJson.toMap());

                if (extractedData != null && !extractedData.isEmpty()) {
                    result.put("extractedData", extractedData);
                }
            } catch (Exception e) {
                log.warn("Could not parse response as JSON, storing as string", e);
                result.put("response", response);
            }
        } else {
            result.put("error", response);
        }

        return result;
    }
}