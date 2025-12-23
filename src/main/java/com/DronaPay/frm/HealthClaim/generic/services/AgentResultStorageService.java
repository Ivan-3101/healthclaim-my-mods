package com.DronaPay.frm.HealthClaim.generic.services;

import com.DronaPay.frm.HealthClaim.generic.storage.StorageProvider;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

@Slf4j
public class AgentResultStorageService {

    public static String storeAgentResultStageWise(String tenantId, String ticketId,
                                                   String filename, String agentId,
                                                   Map<String, Object> result) throws Exception {

        StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);
        Properties props = ConfigurationService.getTenantProperties(tenantId);
        String rootFolder = props.getProperty("storage.minio.bucketName", "insurance-claims");

        String safeFilename = (filename != null && !filename.isEmpty())
                ? filename.replaceAll("[^a-zA-Z0-9.-]", "_")
                : "consolidated";

        String pathPattern = "{rootFolder}/{tenantId}/HealthClaim/{ticketId}/results/{agentId}/{filename}.json";
        String storagePath = pathPattern
                .replace("{rootFolder}", rootFolder)
                .replace("{tenantId}", tenantId)
                .replace("{ticketId}", ticketId)
                .replace("{agentId}", agentId)
                .replace("{filename}", safeFilename);

        JSONObject resultJson = new JSONObject(result);
        byte[] content = resultJson.toString(2).getBytes(StandardCharsets.UTF_8);

        storage.uploadDocument(storagePath, content, "application/json");

        log.info("Stored {} result for {} at (stage-wise): {}", agentId, filename, storagePath);

        return storagePath;
    }

    public static String storeAgentResultDocumentWise(String tenantId, String ticketId,
                                                      String filename, String agentId,
                                                      Map<String, Object> result) throws Exception {

        StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);
        Properties props = ConfigurationService.getTenantProperties(tenantId);
        String rootFolder = props.getProperty("storage.minio.bucketName", "insurance-claims");

        String safeFilename = (filename != null && !filename.isEmpty())
                ? filename.replaceAll("[^a-zA-Z0-9.-]", "_")
                : "consolidated";

        String pathPattern = "{rootFolder}/{tenantId}/HealthClaim/{ticketId}/results/{filename}/{agentId}.json";
        String storagePath = pathPattern
                .replace("{rootFolder}", rootFolder)
                .replace("{tenantId}", tenantId)
                .replace("{ticketId}", ticketId)
                .replace("{filename}", safeFilename)
                .replace("{agentId}", agentId);

        JSONObject resultJson = new JSONObject(result);
        byte[] content = resultJson.toString(2).getBytes(StandardCharsets.UTF_8);

        storage.uploadDocument(storagePath, content, "application/json");

        log.debug("Stored {} result for {} at (document-wise): {}", agentId, filename, storagePath);

        return storagePath;
    }

    public static String storeAgentResultBoth(String tenantId, String ticketId,
                                              String filename, String agentId,
                                              Map<String, Object> result) throws Exception {

        String documentWisePath = storeAgentResultDocumentWise(tenantId, ticketId, filename, agentId, result);
        String stageWisePath = storeAgentResultStageWise(tenantId, ticketId, filename, agentId, result);

        log.info("Stored {} result in both locations - primary: {}", agentId, stageWisePath);

        return stageWisePath;
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

                if (json.has("extractedData")) {
                    result.put("extractedData", json.get("extractedData"));
                }
            } else if (json.has("apiResponse")) {
                result.put("apiResponse", json.getString("apiResponse"));
                result.put("statusCode", json.optInt("statusCode", 200));
            } else {
                result.put("apiResponse", jsonString);
                result.put("statusCode", 200);
            }

            log.debug("Retrieved agent result from: {}", minioPath);
            return result;
        } catch (Exception e) {
            log.error("Failed to retrieve agent result from MinIO: {}", minioPath, e);
            throw new RuntimeException("Could not retrieve agent result from MinIO", e);
        }
    }

    public static Map<String, Object> buildResultMap(String agentId, int statusCode,
                                                     String rawResponse, Map<String, Object> extractedData) {
        Map<String, Object> result = new HashMap<>();
        result.put("agentId", agentId);
        result.put("statusCode", statusCode);
        result.put("success", statusCode == 200);
        result.put("rawResponse", rawResponse);
        result.put("extractedData", extractedData);
        result.put("timestamp", System.currentTimeMillis());

        return result;
    }
}