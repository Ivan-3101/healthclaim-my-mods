package com.DronaPay.frm.HealthClaim;

import com.DronaPay.frm.HealthClaim.generic.services.AgentResultStorageService;
import lombok.extern.slf4j.Slf4j;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class FHIRConsolidatorDelegate implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("=== FHIR Consolidator Started ===");

        String tenantId = execution.getTenantId();
        String ticketId = String.valueOf(execution.getVariable("TicketID"));
        String stageName = execution.getCurrentActivityId();

        @SuppressWarnings("unchecked")
        List<String> splitDocumentVars = (List<String>) execution.getVariable("splitDocumentVars");

        if (splitDocumentVars == null || splitDocumentVars.isEmpty()) {
            throw new RuntimeException("No documents to consolidate");
        }

        log.info("Processing {} documents", splitDocumentVars.size());

        List<Object> docFhirList = new ArrayList<>();
        int successCount = 0;
        int failCount = 0;

        for (String filename : splitDocumentVars) {
            String ocrToStaticPath = tenantId + "/HealthClaim/" + ticketId + "/Raw_To_Structured_OCR/" + filename + ".json";

            try {
                Map<String, Object> result = AgentResultStorageService.retrieveAgentResult(tenantId, ocrToStaticPath);
                String rawResponse = (String) result.get("apiResponse");

                if (rawResponse == null || rawResponse.trim().isEmpty()) {
                    log.error("Empty response for: {}", filename);
                    failCount++;
                    continue;
                }

                JSONObject responseJson = new JSONObject(rawResponse);

                if (!responseJson.has("answer")) {
                    log.warn("No 'answer' field for: {}", filename);
                    failCount++;
                    continue;
                }

                // Check if answer is an array or object
                Object answerObj = responseJson.get("answer");

                if (answerObj instanceof JSONArray) {
                    // Direct array case (like identification.pdf)
                    JSONArray answerArray = (JSONArray) answerObj;
                    for (int i = 0; i < answerArray.length(); i++) {
                        docFhirList.add(answerArray.get(i));
                    }
                } else if (answerObj instanceof JSONObject) {
                    // Object case (like claim_form.pdf)
                    JSONObject answer = (JSONObject) answerObj;
                    if (answer.has("response")) {
                        JSONArray responseArray = answer.getJSONArray("response");
                        for (int i = 0; i < responseArray.length(); i++) {
                            docFhirList.add(responseArray.get(i));
                        }
                    } else {
                        docFhirList.add(answer);
                    }
                } else {
                    log.warn("Unexpected answer type for: {}", filename);
                    failCount++;
                    continue;
                }

                successCount++;
                log.info("Processed: {}", filename);

            } catch (Exception e) {
                log.error("Failed to process: {}", filename, e);
                failCount++;
            }
        }

        if (docFhirList.isEmpty()) {
            throw new RuntimeException("FHIR consolidation produced no results");
        }

        JSONObject consolidatedRequest = new JSONObject();
        consolidatedRequest.put("doc_fhir", docFhirList);

        String consolidatedJson = consolidatedRequest.toString();

        // Store consolidated result to MinIO
        Map<String, Object> resultMap = AgentResultStorageService.buildResultMap(
                "fhir_consolidator", 200, consolidatedJson, new HashMap<>());

        String fhirConsolidatorMinioPath = AgentResultStorageService.storeAgentResult(
                tenantId, ticketId, stageName, "consolidated", resultMap);

        execution.setVariable("fhirConsolidatorMinioPath", fhirConsolidatorMinioPath);

        log.info("=== Completed: {} successful, {} failed ===", successCount, failCount);
        log.info("Stored at: {}", fhirConsolidatorMinioPath);
    }
}