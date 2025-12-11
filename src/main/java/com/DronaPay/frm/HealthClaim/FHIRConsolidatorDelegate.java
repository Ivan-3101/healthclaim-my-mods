package com.DronaPay.frm.HealthClaim;

import com.DronaPay.frm.HealthClaim.generic.services.AgentResultStorageService;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * FHIR Consolidator Delegate
 *
 * Consolidates all ocrToStatic outputs into a single request for FHIR Analyser.
 * Builds the request in the format:
 * {
 *   "data": {
 *     "doc_fhir": [
 *       {...doc1 fields...},
 *       {...doc2 fields...}
 *     ]
 *   },
 *   "agentid": "FHIR_Analyser"
 * }
 *
 * Stores the consolidated request in MinIO for FHIR Analyser to consume.
 */
@Slf4j
public class FHIRConsolidatorDelegate implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("=== FHIR Consolidator Started ===");

        String tenantId = execution.getTenantId();
        String ticketId = String.valueOf(execution.getVariable("TicketID"));

        log.info("TicketID: {}, TenantID: {}", ticketId, tenantId);

        // Get fileProcessMap which contains all agent results
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> fileProcessMap =
                (Map<String, Map<String, Object>>) execution.getVariable("fileProcessMap");

        if (fileProcessMap == null || fileProcessMap.isEmpty()) {
            log.error("fileProcessMap is null or empty");
            throw new RuntimeException("No document processing results found");
        }

        log.info("Processing {} documents for FHIR consolidation", fileProcessMap.size());

        // Build consolidated doc_fhir array
        List<Object> docFhirList = new ArrayList<>();
        int successCount = 0;
        int failCount = 0;

        for (Map.Entry<String, Map<String, Object>> entry : fileProcessMap.entrySet()) {
            String filename = entry.getKey();
            Map<String, Object> fileResults = entry.getValue();

            log.debug("Processing file: {}", filename);

            // Get ocrToStatic output
            if (!fileResults.containsKey("ocrToStaticOutput")) {
                log.warn("No ocrToStatic output for file: {}, skipping", filename);
                failCount++;
                continue;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> ocrToStaticOutput =
                    (Map<String, Object>) fileResults.get("ocrToStaticOutput");

            String apiCall = (String) ocrToStaticOutput.get("apiCall");
            if (!"success".equals(apiCall)) {
                log.warn("ocrToStatic failed for file: {}, skipping", filename);
                failCount++;
                continue;
            }

            String minioPath = (String) ocrToStaticOutput.get("minioPath");
            if (minioPath == null) {
                log.error("No MinIO path for file: {}, skipping", filename);
                failCount++;
                continue;
            }

            try {
                // Retrieve ocrToStatic result from MinIO
                Map<String, Object> result =
                        AgentResultStorageService.retrieveAgentResult(tenantId, minioPath);

                String rawResponse = (String) result.get("rawResponse");
                if (rawResponse == null || rawResponse.trim().isEmpty()) {
                    log.error("Empty rawResponse for file: {}, skipping", filename);
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
                    docFhirList.add(answer.toMap());
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
        log.info("Consolidated FHIR request ({} bytes): {}",
                consolidatedJson.length(),
                consolidatedJson.length() > 500 ?
                        consolidatedJson.substring(0, 500) + "..." : consolidatedJson);

        // Store consolidated request in MinIO
        storeConsolidatedRequest(tenantId, ticketId, consolidatedRequest);

        // Set process variable for easy access
        execution.setVariable("fhirConsolidatedRequest", consolidatedJson);

        log.info("=== FHIR Consolidator Completed ===");
    }

    /**
     * Store consolidated FHIR request in MinIO
     */
    private void storeConsolidatedRequest(String tenantId, String ticketId,
                                          JSONObject consolidatedRequest) {
        try {
            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("agentId", "fhirConsolidator");
            resultMap.put("statusCode", 200);
            resultMap.put("success", true);
            resultMap.put("rawResponse", consolidatedRequest.toString());
            resultMap.put("extractedData", new HashMap<>());
            resultMap.put("timestamp", System.currentTimeMillis());

            // Store in stage-wise structure
            String minioPath = AgentResultStorageService.storeAgentResultStageWise(
                    tenantId, ticketId, "consolidated", "fhirConsolidator", resultMap
            );

            log.info("Stored consolidated FHIR request at: {}", minioPath);

        } catch (Exception e) {
            log.error("Failed to store consolidated FHIR request in MinIO", e);
            throw new RuntimeException("Could not store consolidated FHIR request", e);
        }
    }
}