package com.DronaPay.frm.HealthClaim.generic.services;

import com.DronaPay.frm.HealthClaim.generic.storage.StorageProvider;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class AgentResultStorageService {

    public static String storeAgentResultStageWise(String tenantId, String ticketId,
                                                   String filename, String agentId,
                                                   Map<String, Object> result,
                                                   int stageNumber, String stageName) throws Exception {

        StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);

        String safeFilename = (filename != null && !filename.isEmpty())
                ? filename.replaceAll("[^a-zA-Z0-9.-]", "_")
                : "consolidated";

        String storagePath = String.format("%s/HealthClaim/%s/%d_%s/%s_%s.json",
                tenantId, ticketId, stageNumber, stageName, agentId, safeFilename);

        JSONObject resultJson = new JSONObject(result);
        byte[] content = resultJson.toString(2).getBytes(StandardCharsets.UTF_8);

        storage.uploadDocument(storagePath, content, "application/json");

        log.info("Stored {} result at stage {}: {}", agentId, stageNumber, storagePath);

        return storagePath;
    }

    public static String storeAgentResultStageWise(String tenantId, String ticketId,
                                                   String filename, String agentId,
                                                   Map<String, Object> result) throws Exception {
        return storeAgentResultStageWise(tenantId, ticketId, filename, agentId, result, 99, agentId);
    }

    public static Map<String, Object> buildResultMap(String agentId, int statusCode, String rawResponse, Map<String, Object> extractedData) {
        Map<String, Object> result = new HashMap<>();
        result.put("agentId", agentId);
        result.put("statusCode", statusCode);
        result.put("success", statusCode == 200);
        result.put("rawResponse", rawResponse);
        result.put("extractedData", extractedData);
        result.put("timestamp", System.currentTimeMillis());
        return result;
    }

    public static Map<String, Object> retrieveAgentResult(String tenantId, String minioPath) throws Exception {
        StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);

        try {
            byte[] content = storage.downloadDocument(minioPath).readAllBytes();
            String jsonString = new String(content, StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(jsonString);

            Map<String, Object> result = new HashMap<>();

            if (json.has("rawResponse")) {
                result.put("agentId", json.optString("agentId"));
                result.put("statusCode", json.optInt("statusCode"));
                result.put("success", json.optBoolean("success"));
                result.put("apiResponse", json.optString("rawResponse"));
                result.put("timestamp", json.optLong("timestamp"));

                if (json.has("extractedData")) {
                    JSONObject extractedData = json.getJSONObject("extractedData");
                    extractedData.keySet().forEach(key -> result.put(key, extractedData.get(key)));
                }
            } else {
                json.keySet().forEach(key -> result.put(key, json.get(key)));
            }

            log.debug("Retrieved agent result from: {}", minioPath);
            return result;
        } catch (Exception e) {
            log.error("Failed to retrieve agent result from MinIO path: {}", minioPath, e);
            throw new RuntimeException(String.format("Could not retrieve agent result from MinIO: %s", minioPath), e);
        }
    }
}