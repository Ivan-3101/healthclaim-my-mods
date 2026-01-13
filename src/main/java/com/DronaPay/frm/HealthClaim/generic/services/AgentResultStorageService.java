package com.DronaPay.frm.HealthClaim.generic.services;

import com.DronaPay.frm.HealthClaim.generic.storage.StorageProvider;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Service for storing and retrieving agent processing results in MinIO
 */
@Slf4j
public class AgentResultStorageService {

    /**
     * Store agent result in MinIO - STAGE-WISE structure (NEW)
     * Path: {tenantId}/HealthClaim/{ticketId}/{stageName}/{filename}.json
     *
     * @param tenantId  - Tenant ID
     * @param ticketId  - Ticket ID
     * @param filename  - Document filename (can be null for non-document agents)
     * @param stageName - The specific Stage Folder Name (e.g., "3_Identify_forged_documents")
     * @param result    - Result object to store
     * @return MinIO storage path
     */
    public static String storeAgentResultInStage(String tenantId, String ticketId,
                                                 String filename, String stageName,
                                                 Map<String, Object> result) throws Exception {

        StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);

        // Handle null filename for non-document agents
        String safeFilename = (filename != null && !filename.isEmpty())
                ? filename.replaceAll("[^a-zA-Z0-9.-]", "_")
                : "consolidated";

        // Build storage path: {tenantId}/HealthClaim/{ticketId}/{stageName}/{filename}.json
        String pathPattern = "{tenantId}/HealthClaim/{ticketId}/{stageName}/{filename}.json";
        String storagePath = pathPattern
                .replace("{tenantId}", tenantId)
                .replace("{ticketId}", ticketId)
                .replace("{stageName}", stageName)
                .replace("{filename}", safeFilename);

        // Convert result to JSON
        JSONObject resultJson = new JSONObject(result);
        byte[] content = resultJson.toString(2).getBytes(StandardCharsets.UTF_8);

        // Upload to MinIO
        storage.uploadDocument(storagePath, content, "application/json");

        log.info("Stored result in stage '{}' at: {}", stageName, storagePath);

        return storagePath;
    }

    /**
     * Store agent result in MinIO - STAGE-WISE structure (Legacy/Standard AgentId mapping)
     * Path: {tenantId}/HealthClaim/{ticketId}/results/{agentId}/{filename}.json
     */
    public static String storeAgentResultStageWise(String tenantId, String ticketId,
                                                   String filename, String agentId,
                                                   Map<String, Object> result) throws Exception {
        // This is kept for backward compatibility if any code still calls it without the new logic.
        // It maps to the old "results/{agentId}" structure.
        String folderName = "results/" + agentId;
        return storeAgentResultInStage(tenantId, ticketId, filename, folderName, result);
    }

    /**
     * Retrieve agent result from MinIO by path (backward compatibility)
     */
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
            throw new RuntimeException(String.format(
                    "Could not retrieve agent result from MinIO: %s", minioPath), e);
        }
    }

    /**
     * Build standard result map with common structure
     */
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