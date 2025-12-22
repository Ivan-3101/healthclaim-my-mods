package com.DronaPay.frm.HealthClaim.generic.services;

import com.DronaPay.frm.HealthClaim.generic.storage.StoragePathBuilder;
import com.DronaPay.frm.HealthClaim.generic.storage.StorageProvider;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Service for storing and retrieving agent results in MinIO using NEW folder structure
 *
 * Agent results stored in: insurance-claims/{tenantId}/{WorkflowType}/{TicketID}/{stage#}_{TaskName}/task-docs/
 */
@Slf4j
public class AgentResultStorageService {

    /**
     * Store agent result in MinIO using NEW folder structure
     * Path: insurance-claims/{tenantId}/{WorkflowType}/{TicketID}/{stage#}_{TaskName}/task-docs/{filename}.json
     *
     * @param tenantId - Tenant ID
     * @param workflowKey - Workflow key (e.g., "HealthClaim")
     * @param ticketId - Ticket ID
     * @param taskName - BPMN task name (e.g., "IdentifyForgedDocuments", "OCROnDoc")
     * @param filename - Document filename (can be null for non-document agents)
     * @param result - Result object to store
     * @return MinIO storage path
     */
    public static String storeAgentResult(String tenantId, String workflowKey, String ticketId,
                                          String taskName, String filename,
                                          Map<String, Object> result) throws Exception {

        StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);

        // Build filename for agent output
        // For document-based agents: {document_filename}.json
        // For non-document agents: result.json or consolidated.json
        String outputFilename;
        if (filename != null && !filename.isEmpty()) {
            // Document-based agent
            if (filename.endsWith(".json")) {
                outputFilename = filename; // Already has .json extension
            } else {
                outputFilename = filename + ".json";
            }
        } else {
            // Non-document agent - use generic name
            outputFilename = "result.json";
        }

        // Build path using StoragePathBuilder
        String storagePath = StoragePathBuilder.buildTaskDocsPath(
                tenantId, workflowKey, ticketId, taskName, outputFilename
        );

        // Convert result to JSON
        JSONObject resultJson = new JSONObject(result);
        byte[] content = resultJson.toString(2).getBytes(StandardCharsets.UTF_8);

        // Upload to MinIO
        storage.uploadDocument(storagePath, content, "application/json");

        log.info("Stored agent result for task '{}' at: {}", taskName, storagePath);

        return storagePath;
    }

    /**
     * Retrieve agent result from MinIO by path
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

            // Convert JSON to Map
            for (String key : json.keySet()) {
                result.put(key, json.get(key));
            }

            log.debug("Retrieved agent result from: {}", minioPath);
            return result;

        } catch (Exception e) {
            log.error("Failed to retrieve agent result from MinIO: {}", minioPath, e);
            throw new RuntimeException("Could not retrieve agent result from MinIO: " + minioPath, e);
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
        result.put("apiResponse", rawResponse); // Alias for backward compatibility
        result.put("extractedData", extractedData);
        result.put("timestamp", System.currentTimeMillis());

        return result;
    }

    /**
     * Store consolidated FHIR result
     * Used by FHIRConsolidatorDelegate
     *
     * Path: insurance-claims/{tenantId}/{WorkflowType}/{TicketID}/8_FHIRConsolidator/userdoc/processed/consolidated.json
     */
    public static String storeConsolidatedFhir(String tenantId, String workflowKey, String ticketId,
                                               String consolidatedJson) throws Exception {
        StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);

        // Build path for consolidated output
        String storagePath = StoragePathBuilder.buildUserProcessedPath(
                tenantId, workflowKey, ticketId, "FHIRConsolidator", "consolidated.json"
        );

        byte[] content = consolidatedJson.getBytes(StandardCharsets.UTF_8);
        storage.uploadDocument(storagePath, content, "application/json");

        log.info("Stored consolidated FHIR at: {}", storagePath);

        return storagePath;
    }

    /**
     * Retrieve consolidated FHIR result
     */
    public static String retrieveConsolidatedFhir(String tenantId, String workflowKey, String ticketId) throws Exception {
        StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);

        String storagePath = StoragePathBuilder.buildUserProcessedPath(
                tenantId, workflowKey, ticketId, "FHIRConsolidator", "consolidated.json"
        );

        byte[] content = storage.downloadDocument(storagePath).readAllBytes();
        return new String(content, StandardCharsets.UTF_8);
    }

    /**
     * Store edited UI form data
     * Used by SaveEditedFormListener
     *
     * Path: insurance-claims/{tenantId}/{WorkflowType}/{TicketID}/11_UIDisplayer/task-docs/edited.json
     */
    public static String storeEditedForm(String tenantId, String workflowKey, String ticketId,
                                         Map<String, Object> editedData) throws Exception {
        return storeAgentResult(tenantId, workflowKey, ticketId, "UIDisplayer", "edited", editedData);
    }

    /**
     * Store final reviewed form data
     * Used by SaveFinalFormListener
     *
     * Path: insurance-claims/{tenantId}/{WorkflowType}/{TicketID}/17_FinalReviewTask/task-docs/final.json
     */
    public static String storeFinalForm(String tenantId, String workflowKey, String ticketId,
                                        Map<String, Object> finalData) throws Exception {
        return storeAgentResult(tenantId, workflowKey, ticketId, "FinalReviewTask", "final", finalData);
    }
}