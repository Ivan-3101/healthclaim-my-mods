package com.DronaPay.frm.HealthClaim;

import com.DronaPay.frm.HealthClaim.generic.services.AgentResultStorageService;
import lombok.extern.slf4j.Slf4j;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * FHIR Consolidator Delegate - SIMPLIFIED VERSION
 *
 * Fetches ocrToStatic results directly from MinIO using predictable paths.
 * No need for fileProcessMap.
 */
@Slf4j
public class FHIRConsolidatorDelegate implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("=== FHIR Consolidator Started ===");

        String tenantId = execution.getTenantId();
        String ticketId = String.valueOf(execution.getVariable("TicketID"));

        log.info("TicketID: {}, TenantID: {}", ticketId, tenantId);

        // Get list of processed files
        @SuppressWarnings("unchecked")
        List<String> splitDocumentVars = (List<String>) execution.getVariable("splitDocumentVars");

        if (splitDocumentVars == null || splitDocumentVars.isEmpty()) {
            log.error("No split documents found");
            throw new RuntimeException("No documents to consolidate");
        }

        log.info("Processing {} documents for FHIR consolidation", splitDocumentVars.size());

        // Build consolidated doc_fhir array
        List<Object> docFhirList = new ArrayList<>();
        int successCount = 0;
        int failCount = 0;

        for (String filename : splitDocumentVars) {
            log.debug("Processing file: {}", filename);

            // Construct MinIO path directly (predictable pattern)
            // Pattern: {tenantId}/HealthClaim/{ticketId}/results/ocrToStatic/{filename}.json
            String minioPath = String.format("%s/HealthClaim/%s/results/ocrToStatic/%s.json",
                    tenantId, ticketId, filename);

            try {
                // Retrieve ocrToStatic result from MinIO
                Map<String, Object> result =
                        AgentResultStorageService.retrieveAgentResult(tenantId, minioPath);

                String rawResponse = (String) result.get("apiResponse");  // KEY FIX: It's "apiResponse" not "rawResponse"
                if (rawResponse == null || rawResponse.trim().isEmpty()) {
                    log.error("Empty apiResponse for file: {}, skipping", filename);
                    failCount++;
                    continue;
                }

                JSONObject responseJson = new JSONObject(rawResponse);

                // Extract answer object
                if (!responseJson.has("answer")) {
                    log.warn("No 'answer' field in ocrToStatic response for: {}", filename);
                    failCount++;
                    continue;
                }

                JSONObject answer = responseJson.getJSONObject("answer");

                // Handle both formats: {response: [...]} or {doc_type: "...", ...}
                if (answer.has("response")) {
                    // Format 1: {answer: {response: [...]}}
                    JSONArray responseArray = answer.getJSONArray("response");
                    for (int i = 0; i < responseArray.length(); i++) {
                        docFhirList.add(responseArray.get(i));
                    }
                } else {
                    // Format 2: {answer: {doc_type: "...", fields: {...}}}
                    docFhirList.add(answer);
                }

                successCount++;
                log.info("Successfully processed ocrToStatic output from: {}", filename);

            } catch (Exception e) {
                log.error("Error processing ocrToStatic output for file: {}", filename, e);
                failCount++;
            }
        }

        log.info("FHIR consolidation complete: {} successful, {} failed", successCount, failCount);

        if (docFhirList.isEmpty()) {
            log.error("No valid ocrToStatic outputs found for consolidation");
            throw new RuntimeException("FHIR consolidation produced no results");
        }

        // Build final consolidated request
        JSONObject consolidatedRequest = new JSONObject();
        JSONObject data = new JSONObject();
        data.put("doc_fhir", new JSONArray(docFhirList));
        consolidatedRequest.put("data", data);
        consolidatedRequest.put("agentid", "FHIR_Analyser");

        String consolidatedJson = consolidatedRequest.toString(2);
        log.info("Consolidated FHIR request ({} bytes) ready for FHIR_Analyser",
                consolidatedJson.length());

        // Store consolidated request in MinIO
        String minioPath = storeConsolidatedRequest(tenantId, ticketId, consolidatedRequest);

        // CRITICAL: Don't set the large JSON as process variable (exceeds varchar(4000) limit)
        // Instead, just store the MinIO path
        execution.setVariable("fhirConsolidatorMinioPath", minioPath);

        log.info("=== FHIR Consolidator Completed ===");
    }

    /**
     * Store consolidated FHIR request in MinIO
     * @return MinIO path where the consolidated request was stored
     */
    private String storeConsolidatedRequest(String tenantId, String ticketId,
                                            JSONObject consolidatedRequest) {
        try {
            Map<String, Object> resultMap = new java.util.HashMap<>();
            resultMap.put("agentId", "fhirConsolidator");
            resultMap.put("statusCode", 200);
            resultMap.put("success", true);
            resultMap.put("rawResponse", consolidatedRequest.toString());
            resultMap.put("extractedData", new java.util.HashMap<>());
            resultMap.put("timestamp", System.currentTimeMillis());

            // Store in stage-wise structure
            String minioPath = AgentResultStorageService.storeAgentResultStageWise(
                    tenantId, ticketId, "consolidated", "fhirConsolidator", resultMap
            );

            log.info("Stored consolidated FHIR request at: {}", minioPath);
            return minioPath;

        } catch (Exception e) {
            log.error("Failed to store consolidated FHIR request in MinIO", e);
            throw new RuntimeException("Could not store consolidated FHIR request", e);
        }
    }
}