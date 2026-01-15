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

@Slf4j
public class FHIRConsolidatorDelegate implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("=== FHIR Consolidator Started ===");

        String tenantId = execution.getTenantId();
        String ticketId = String.valueOf(execution.getVariable("TicketID"));

        // CHANGE: Use BPMN Activity ID
        String stageName = execution.getCurrentActivityId();

        log.info("TicketID: {}, TenantID: {}", ticketId, tenantId);

        @SuppressWarnings("unchecked")
        List<String> splitDocumentVars = (List<String>) execution.getVariable("splitDocumentVars");

        // CHANGE: Get map of inputs
        @SuppressWarnings("unchecked")
        Map<String, String> ocrToStaticResults = (Map<String, String>) execution.getVariable("ocrToStaticResults");

        if (splitDocumentVars == null || splitDocumentVars.isEmpty()) {
            log.error("No split documents found");
            throw new RuntimeException("No documents to consolidate");
        }

        log.info("Processing {} documents for FHIR consolidation", splitDocumentVars.size());

        List<Object> docFhirList = new ArrayList<>();
        int successCount = 0;
        int failCount = 0;

        for (String filename : splitDocumentVars) {
            log.debug("Processing file: {}", filename);

            // CHANGE: Read path from map
            String minioPath = null;
            if (ocrToStaticResults != null && ocrToStaticResults.containsKey(filename)) {
                minioPath = ocrToStaticResults.get(filename);
            }

            if (minioPath == null) {
                log.error("No input path found for file: {}, skipping", filename);
                failCount++;
                continue;
            }

            try {
                Map<String, Object> result =
                        AgentResultStorageService.retrieveAgentResult(tenantId, minioPath);

                String rawResponse = (String) result.get("apiResponse");
                if (rawResponse == null || rawResponse.trim().isEmpty()) {
                    log.error("Empty apiResponse for file: {}, skipping", filename);
                    failCount++;
                    continue;
                }

                JSONObject responseJson = new JSONObject(rawResponse);

                if (!responseJson.has("answer")) {
                    log.warn("No 'answer' field in ocrToStatic response for: {}", filename);
                    failCount++;
                    continue;
                }

                JSONObject answer = responseJson.getJSONObject("answer");

                if (answer.has("response")) {
                    JSONArray responseArray = answer.getJSONArray("response");
                    for (int i = 0; i < responseArray.length(); i++) {
                        docFhirList.add(responseArray.get(i));
                    }
                } else {
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

        JSONObject consolidatedRequest = new JSONObject();
        JSONObject data = new JSONObject();
        data.put("doc_fhir", new JSONArray(docFhirList));
        consolidatedRequest.put("data", data);
        consolidatedRequest.put("agentid", "FHIR_Analyser");

        String consolidatedJson = consolidatedRequest.toString(2);
        log.info("Consolidated FHIR request ({} bytes) ready for FHIR_Analyser",
                consolidatedJson.length());

        // CHANGE: Pass stageName
        String minioPath = storeConsolidatedRequest(tenantId, ticketId, consolidatedRequest, stageName);

        execution.setVariable("fhirConsolidatorMinioPath", minioPath);

        log.info("=== FHIR Consolidator Completed ===");
    }

    private String storeConsolidatedRequest(String tenantId, String ticketId,
                                            JSONObject consolidatedRequest, String stageName) {
        try {
            Map<String, Object> resultMap = new java.util.HashMap<>();
            resultMap.put("agentId", "fhirConsolidator");
            resultMap.put("statusCode", 200);
            resultMap.put("success", true);
            resultMap.put("rawResponse", consolidatedRequest.toString());
            resultMap.put("extractedData", new java.util.HashMap<>());
            resultMap.put("timestamp", System.currentTimeMillis());

            // CHANGE: Use stageName
            String minioPath = AgentResultStorageService.storeAgentResult(
                    tenantId, ticketId, stageName, "consolidated", resultMap
            );

            log.info("Stored consolidated FHIR request at: {}", minioPath);
            return minioPath;

        } catch (Exception e) {
            log.error("Failed to store consolidated FHIR request in MinIO", e);
            throw new RuntimeException("Could not store consolidated FHIR request", e);
        }
    }
}