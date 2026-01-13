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
     * Store agent result in MinIO - DOCUMENT-WISE structure (OLD - will be removed later)
     * Path: {tenantId}/HealthClaim/{ticketId}/results/{filename}/{agentId}.json
     *
     * @param tenantId - Tenant ID
     * @param ticketId - Ticket ID
     * @param filename - Document filename (can be null for non-document agents)
     * @param agentId - Agent ID (e.g., "forgeryagent", "openaiVision")
     * @param result - Result object to store
     * @return MinIO storage path
     */
    public static String storeAgentResultDocumentWise(String tenantId, String ticketId,
                                                      String filename, String agentId,
                                                      Map<String, Object> result) throws Exception {

        StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);

        // Handle null filename for non-document agents (like FHIRAnalyser, PolicyComparator)
        String safeFilename = (filename != null && !filename.isEmpty())
                ? filename.replaceAll("[^a-zA-Z0-9.-]", "_")
                : "consolidated";

        // Build storage path: {tenantId}/HealthClaim/{ticketId}/results/{filename}/{agentId}.json
        String pathPattern = "{tenantId}/HealthClaim/{ticketId}/results/{filename}/{agentId}.json";
        String storagePath = pathPattern
                .replace("{tenantId}", tenantId)
                .replace("{ticketId}", ticketId)
                .replace("{filename}", safeFilename)
                .replace("{agentId}", agentId);

        // Convert result to JSON
        JSONObject resultJson = new JSONObject(result);
        byte[] content = resultJson.toString(2).getBytes(StandardCharsets.UTF_8);

        // Upload to MinIO
        storage.uploadDocument(storagePath, content, "application/json");

        log.debug("Stored {} result for {} at (document-wise): {}", agentId, filename, storagePath);

        return storagePath;
    }

    /**
     * Store agent result in MinIO - STAGE-WISE structure (NEW)
     * Path: {tenantId}/HealthClaim/{ticketId}/results/{agentId}/{filename}.json
     *
     * This groups all files processed by the same agent in one folder
     *
     * @param tenantId - Tenant ID
     * @param ticketId - Ticket ID
     * @param filename - Document filename (can be null for non-document agents)
     * @param agentId - Agent ID (e.g., "forgeryagent", "openaiVision")
     * @param result - Result object to store
     * @return MinIO storage path
     */
    public static String storeAgentResultStageWise(String tenantId, String ticketId,
                                                   String filename, String agentId,
                                                   Map<String, Object> result) throws Exception {

        StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);

        // Handle null filename for non-document agents (like FHIRAnalyser, PolicyComparator)
        String safeFilename = (filename != null && !filename.isEmpty())
                ? filename.replaceAll("[^a-zA-Z0-9.-]", "_")
                : "consolidated";

        // Build storage path: {tenantId}/HealthClaim/{ticketId}/results/{agentId}/{filename}.json
        String pathPattern = "{tenantId}/HealthClaim/{ticketId}/results/{agentId}/{filename}.json";
        String storagePath = pathPattern
                .replace("{tenantId}", tenantId)
                .replace("{ticketId}", ticketId)
                .replace("{agentId}", agentId)
                .replace("{filename}", safeFilename);

        // Convert result to JSON
        JSONObject resultJson = new JSONObject(result);
        byte[] content = resultJson.toString(2).getBytes(StandardCharsets.UTF_8);

        // Upload to MinIO
        storage.uploadDocument(storagePath, content, "application/json");

        log.info("Stored {} result for {} at (stage-wise): {}", agentId, filename, storagePath);

        return storagePath;
    }

    /**
     * Store agent result in BOTH locations (document-wise and stage-wise)
     * Returns the stage-wise path as primary
     *
     * @param tenantId - Tenant ID
     * @param ticketId - Ticket ID
     * @param filename - Document filename (can be null for non-document agents)
     * @param agentId - Agent ID
     * @param result - Result object to store
     * @return Primary storage path (stage-wise)
     */
    public static String storeAgentResultBoth(String tenantId, String ticketId,
                                              String filename, String agentId,
                                              Map<String, Object> result) throws Exception {

        // Store in OLD location (document-wise)
        String documentWisePath = storeAgentResultDocumentWise(tenantId, ticketId, filename, agentId, result);

        // Store in NEW location (stage-wise)
        String stageWisePath = storeAgentResultStageWise(tenantId, ticketId, filename, agentId, result);

        log.info("Stored {} result in both locations - primary: {}", agentId, stageWisePath);

        return stageWisePath;
    }

    /**
     * Retrieve agent result from MinIO by path (backward compatibility)
     * Used by existing code that has the full MinIO path
     *
     * @param tenantId - Tenant ID
     * @param minioPath - Full MinIO path to result file
     * @return Result map
     */
    public static Map<String, Object> retrieveAgentResult(String tenantId, String minioPath) throws Exception {
        StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);

        try {
            byte[] content = storage.downloadDocument(minioPath).readAllBytes();
            String jsonString = new String(content, StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(jsonString);

            Map<String, Object> result = new HashMap<>();

            // Handle both old and new result structures
            if (json.has("rawResponse")) {
                // New structure: {agentId, statusCode, rawResponse, extractedData, timestamp}
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
                // Legacy structure: direct JSON conversion
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
     * Retrieve agent result from MinIO (tries stage-wise first, falls back to document-wise)
     *
     * @param tenantId - Tenant ID
     * @param ticketId - Ticket ID
     * @param filename - Document filename (can be null for non-document agents)
     * @param agentId - Agent ID
     * @return Result map
     */
    public static Map<String, Object> retrieveAgentResult(String tenantId, String ticketId,
                                                          String filename, String agentId) throws Exception {

        StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);

        // Handle null filename for non-document agents
        String safeFilename = (filename != null && !filename.isEmpty())
                ? filename.replaceAll("[^a-zA-Z0-9.-]", "_")
                : "consolidated";

        // Try stage-wise first (NEW structure)
        String stageWisePath = "{tenantId}/HealthClaim/{ticketId}/results/{agentId}/{filename}.json"
                .replace("{tenantId}", tenantId)
                .replace("{ticketId}", ticketId)
                .replace("{agentId}", agentId)
                .replace("{filename}", safeFilename);

        try {
            byte[] content = storage.downloadDocument(stageWisePath).readAllBytes();
            String jsonString = new String(content, StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(jsonString);

            Map<String, Object> result = new HashMap<>();
            json.keySet().forEach(key -> result.put(key, json.get(key)));

            log.debug("Retrieved {} result from stage-wise: {}", agentId, stageWisePath);
            return result;
        } catch (Exception e) {
            log.debug("Stage-wise result not found, trying document-wise: {}", e.getMessage());
        }

        // Fall back to document-wise (OLD structure)
        String documentWisePath = "{tenantId}/HealthClaim/{ticketId}/results/{filename}/{agentId}.json"
                .replace("{tenantId}", tenantId)
                .replace("{ticketId}", ticketId)
                .replace("{filename}", safeFilename)
                .replace("{agentId}", agentId);

        try {
            byte[] content = storage.downloadDocument(documentWisePath).readAllBytes();
            String jsonString = new String(content, StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(jsonString);

            Map<String, Object> result = new HashMap<>();
            json.keySet().forEach(key -> result.put(key, json.get(key)));

            log.debug("Retrieved {} result from document-wise: {}", agentId, documentWisePath);
            return result;
        } catch (Exception e) {
            log.error("Failed to retrieve {} result for {} from MinIO", agentId, filename, e);
            throw new RuntimeException(String.format(
                    "Could not retrieve agent result for %s/%s from MinIO", agentId, filename), e);
        }
    }

    /**
     * Build standard result map with common structure
     *
     * @param agentId - Agent ID
     * @param statusCode - HTTP status code from agent API
     * @param rawResponse - Raw response string from agent
     * @param extractedData - Extracted/processed data fields
     * @return Result map
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