package com.DronaPay.frm.HealthClaim;

import com.DronaPay.frm.HealthClaim.generic.services.AgentResultStorageService;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Consolidates OCR to FHIR outputs from all documents into a single merged structure
 *
 * Input: fileProcessMap with ocrToFhirOutput for each document
 * Output: consolidatedFhir variable containing merged document types
 */
@Slf4j
public class FHIRConsolidationDelegate implements JavaDelegate {

    @SuppressWarnings("unchecked")
    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("=== FHIR Consolidation Started ===");

        String tenantId = execution.getTenantId();
        String ticketId = String.valueOf(execution.getVariable("TicketID"));

        // Get fileProcessMap with all agent outputs
        Map<String, Map<String, Object>> fileProcessMap =
                (Map<String, Map<String, Object>>) execution.getVariable("fileProcessMap");

        if (fileProcessMap == null || fileProcessMap.isEmpty()) {
            log.warn("No fileProcessMap found, skipping consolidation");
            execution.setVariable("consolidatedFhir", new JSONObject().toString());
            return;
        }

        // Initialize consolidated structure
        Map<String, Object> consolidated = new HashMap<>();

        // Track document processing statistics
        int totalDocuments = 0;
        int successfulDocuments = 0;
        int failedDocuments = 0;

        // Iterate through all documents
        for (Map.Entry<String, Map<String, Object>> entry : fileProcessMap.entrySet()) {
            String filename = entry.getKey();
            Map<String, Object> fileResults = entry.getValue();

            totalDocuments++;

            // Check if OCR to FHIR output exists
            if (!fileResults.containsKey("ocrToFhirOutput")) {
                log.warn("No ocrToFhir output for document: {}", filename);
                failedDocuments++;
                continue;
            }

            Map<String, Object> ocrToFhirOutput =
                    (Map<String, Object>) fileResults.get("ocrToFhirOutput");

            String apiCall = (String) ocrToFhirOutput.get("apiCall");
            if (!"success".equals(apiCall)) {
                log.warn("OCR to FHIR failed for document: {}", filename);
                failedDocuments++;
                continue;
            }

            String minioPath = (String) ocrToFhirOutput.get("minioPath");
            if (minioPath == null) {
                log.error("No MinIO path for document: {}", filename);
                failedDocuments++;
                continue;
            }

            try {
                // Retrieve full FHIR result from MinIO
                Map<String, Object> result =
                        AgentResultStorageService.retrieveAgentResult(tenantId, minioPath);

                String apiResponseStr = (String) result.get("apiResponse");
                JSONObject apiResponse = new JSONObject(apiResponseStr);

                // Extract the answer object
                if (!apiResponse.has("answer")) {
                    log.warn("No 'answer' field in FHIR response for: {}", filename);
                    failedDocuments++;
                    continue;
                }

                Object answerObj = apiResponse.get("answer");
                JSONObject fhirData;

                if (answerObj instanceof String) {
                    fhirData = new JSONObject((String) answerObj);
                } else if (answerObj instanceof JSONObject) {
                    fhirData = (JSONObject) answerObj;
                } else {
                    log.error("Unexpected answer type for {}: {}", filename, answerObj.getClass());
                    failedDocuments++;
                    continue;
                }

                // Merge document types into consolidated structure
                mergeDocumentTypes(consolidated, fhirData, filename);

                successfulDocuments++;
                log.info("Successfully consolidated FHIR data from: {}", filename);

            } catch (Exception e) {
                log.error("Error processing FHIR data for document: {}", filename, e);
                failedDocuments++;
            }
        }

        log.info("Consolidation complete: {} total, {} successful, {} failed",
                totalDocuments, successfulDocuments, failedDocuments);

        // Convert consolidated map to JSON string
        JSONObject consolidatedJson = new JSONObject(consolidated);
        String consolidatedFhir = consolidatedJson.toString(2);

        // Set process variable
        execution.setVariable("consolidatedFhir", consolidatedFhir);

        // Store consolidated output in MinIO for reference
        storeConsolidatedOutput(tenantId, ticketId, consolidatedJson);

        log.info("=== FHIR Consolidation Completed ===");
    }

    /**
     * Merge document types from a single document into the consolidated structure
     * If a document type already exists, keep the most complete data
     */
    @SuppressWarnings("unchecked")
    private void mergeDocumentTypes(Map<String, Object> consolidated,
                                    JSONObject fhirData,
                                    String filename) {

        for (String documentType : fhirData.keySet()) {
            Object typeData = fhirData.get(documentType);

            if (typeData instanceof JSONObject) {
                JSONObject typeJson = (JSONObject) typeData;
                Map<String, Object> typeMap = typeJson.toMap();

                if (consolidated.containsKey(documentType)) {
                    // Document type already exists, merge the data
                    Map<String, Object> existing =
                            (Map<String, Object>) consolidated.get(documentType);
                    mergeNestedData(existing, typeMap);
                    log.debug("Merged {} from {} into existing data", documentType, filename);
                } else {
                    // New document type, add it
                    consolidated.put(documentType, typeMap);
                    log.debug("Added new document type {} from {}", documentType, filename);
                }
            }
        }
    }

    /**
     * Recursively merge nested data structures
     * Prefers non-null values over null values
     */
    @SuppressWarnings("unchecked")
    private void mergeNestedData(Map<String, Object> existing, Map<String, Object> newData) {
        for (Map.Entry<String, Object> entry : newData.entrySet()) {
            String key = entry.getKey();
            Object newValue = entry.getValue();

            if (!existing.containsKey(key) || existing.get(key) == null) {
                // Key doesn't exist or is null, use new value
                existing.put(key, newValue);
            } else if (newValue instanceof Map && existing.get(key) instanceof Map) {
                // Both are maps, merge recursively
                mergeNestedData(
                        (Map<String, Object>) existing.get(key),
                        (Map<String, Object>) newValue
                );
            } else if (newValue != null && existing.get(key) != null) {
                // Both non-null, prefer the one with more content
                String existingStr = existing.get(key).toString();
                String newStr = newValue.toString();

                // Keep the longer/more detailed value
                if (newStr.length() > existingStr.length() &&
                        !newStr.equals("null") && !newStr.isEmpty()) {
                    existing.put(key, newValue);
                }
            }
        }
    }

    /**
     * Store consolidated FHIR output in MinIO for reference
     */
    private void storeConsolidatedOutput(String tenantId, String ticketId,
                                         JSONObject consolidatedJson) {
        try {
            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("agentId", "fhirConsolidation");
            resultMap.put("statusCode", 200);
            resultMap.put("apiCall", "success");
            resultMap.put("consolidatedOutput", consolidatedJson.toString());
            resultMap.put("timestamp", System.currentTimeMillis());

            // Store in stage-wise structure
            String minioPath = AgentResultStorageService.storeAgentResultStageWise(
                    tenantId, ticketId, "consolidated", "fhirConsolidation", resultMap
            );

            log.info("Stored consolidated FHIR at: {}", minioPath);

        } catch (Exception e) {
            log.error("Failed to store consolidated FHIR in MinIO", e);
            // Don't throw - this is just for reference
        }
    }
}