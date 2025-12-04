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
     * Store agent result in MinIO
     *
     * @param tenantId - Tenant ID
     * @param ticketId - Ticket ID
     * @param filename - Document filename
     * @param agentId - Agent ID (e.g., "forgeryagent", "openaiVision")
     * @param result - Result object to store
     * @return MinIO storage path
     */
    public static String storeAgentResult(String tenantId, String ticketId,
                                          String filename, String agentId,
                                          Map<String, Object> result) throws Exception {

        StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);

        // Build storage path: {tenantId}/HealthClaim/{ticketId}/results/{filename}/{agentId}.json
        String pathPattern = "{tenantId}/HealthClaim/{ticketId}/results/{filename}/{agentId}.json";
        String storagePath = pathPattern
                .replace("{tenantId}", tenantId)
                .replace("{ticketId}", ticketId)
                .replace("{filename}", filename.replaceAll("[^a-zA-Z0-9.-]", "_"))
                .replace("{agentId}", agentId);

        // Convert result to JSON
        JSONObject resultJson = new JSONObject(result);
        byte[] content = resultJson.toString(2).getBytes(StandardCharsets.UTF_8);

        // Upload to MinIO
        storage.uploadDocument(storagePath, content, "application/json");

        log.info("Stored {} result for {} at: {}", agentId, filename, storagePath);

        return storagePath;
    }

    /**
     * Retrieve agent result from MinIO
     *
     * @param tenantId - Tenant ID
     * @param storagePath - MinIO storage path
     * @return Result as Map
     */
    public static Map<String, Object> retrieveAgentResult(String tenantId, String storagePath) throws Exception {

        StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);

        // Download from MinIO
        byte[] content = storage.downloadDocument(storagePath).readAllBytes();
        String jsonString = new String(content, StandardCharsets.UTF_8);

        // Parse JSON
        JSONObject resultJson = new JSONObject(jsonString);

        // Convert to Map
        Map<String, Object> result = new HashMap<>();
        for (String key : resultJson.keySet()) {
            result.put(key, resultJson.get(key));
        }

        log.debug("Retrieved agent result from: {}", storagePath);

        return result;
    }

    /**
     * Build result map for storage
     */
    public static Map<String, Object> buildResultMap(String agentId, int statusCode,
                                                     String response, Map<String, Object> extractedData) {
        Map<String, Object> result = new HashMap<>();
        result.put("agentId", agentId);
        result.put("statusCode", statusCode);
        result.put("apiCall", statusCode == 200 ? "success" : "failed");
        result.put("apiResponse", response);
        result.put("timestamp", System.currentTimeMillis());

        // Add extracted data
        if (extractedData != null && !extractedData.isEmpty()) {
            result.putAll(extractedData);
        }

        return result;
    }
}