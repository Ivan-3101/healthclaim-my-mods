package com.DronaPay.frm.HealthClaim;

import com.DronaPay.frm.HealthClaim.generic.config.WorkflowStageMapping;
import com.DronaPay.frm.HealthClaim.generic.services.ConfigurationService;
import com.DronaPay.frm.HealthClaim.generic.services.ObjectStorageService;
import com.DronaPay.frm.HealthClaim.generic.storage.StorageProvider;
import com.DronaPay.frm.HealthClaim.generic.utils.StoragePathBuilder;
import lombok.extern.slf4j.Slf4j;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.*;

@Slf4j
public class FHIRConsolidatorDelegate implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("=== FHIR Consolidator Started ===");

        String tenantId = execution.getTenantId();
        String ticketId = String.valueOf(execution.getVariable("TicketID"));
        String workflowKey = StoragePathBuilder.getWorkflowType(execution);
        String taskName = StoragePathBuilder.getTaskName(execution);
        int stageNumber = WorkflowStageMapping.getStageNumber(workflowKey, taskName);

        if (stageNumber == -1) {
            stageNumber = StoragePathBuilder.getStageNumber(execution) + 1;
        }

        execution.setVariable("stageNumber", stageNumber);
        log.info("Stage {}: {} - TicketID: {}, TenantID: {}", stageNumber, taskName, ticketId, tenantId);

        List<String> splitDocumentVars = (List<String>) execution.getVariable("splitDocumentVars");
        Map<String, Map<String, Object>> fileProcessMap =
                (Map<String, Map<String, Object>>) execution.getVariable("fileProcessMap");

        if (splitDocumentVars == null || splitDocumentVars.isEmpty()) {
            throw new RuntimeException("No split documents found for FHIR consolidation");
        }

        // Build doc_fhir array from OCR_to_Static outputs
        JSONArray docFhirArray = new JSONArray();

        for (String filename : splitDocumentVars) {
            Map<String, Object> fileResults = fileProcessMap.get(filename);

            if (fileResults == null) {
                log.warn("No processing results for {}, skipping", filename);
                continue;
            }

            Map<String, Object> ocrToStaticResult = (Map<String, Object>) fileResults.get("ocrToStatic");

            if (ocrToStaticResult == null) {
                log.warn("No ocrToStatic result for {}, skipping", filename);
                continue;
            }

            String rawResponse = (String) ocrToStaticResult.get("rawResponse");

            if (rawResponse == null || rawResponse.trim().isEmpty()) {
                log.warn("No rawResponse for {}, skipping", filename);
                continue;
            }

            try {
                JSONObject responseJson = new JSONObject(rawResponse);

                if (!responseJson.has("answer")) {
                    log.warn("No 'answer' field in rawResponse for {}", filename);
                    continue;
                }

                Object answerObj = responseJson.get("answer");

                // Handle two formats:
                // Format 1: {"answer": {"doc_type": "...", "field1": "...", ...}}
                // Format 2: {"answer": {"response": [{"doc_type": "...", "fields": {...}}, ...]}}

                if (answerObj instanceof JSONObject) {
                    JSONObject answer = (JSONObject) answerObj;

                    if (answer.has("response") && answer.get("response") instanceof JSONArray) {
                        // Format 2: Multiple documents in response array
                        JSONArray responseArray = answer.getJSONArray("response");
                        for (int i = 0; i < responseArray.length(); i++) {
                            JSONObject docItem = responseArray.getJSONObject(i);
                            docFhirArray.put(docItem);
                            log.debug("Added FHIR doc from {} (format 2): {}", filename, docItem.optString("doc_type", "unknown"));
                        }
                    } else {
                        // Format 1: Single document with fields at root level
                        docFhirArray.put(answer);
                        log.debug("Added FHIR doc from {} (format 1): {}", filename, answer.optString("doc_type", "unknown"));
                    }
                }

            } catch (Exception e) {
                log.error("Failed to parse rawResponse for {}: {}", filename, e.getMessage());
            }
        }

        log.info("Built doc_fhir array with {} documents", docFhirArray.length());

        // Build consolidated request in the format UI_Displayer expects
        JSONObject consolidatedRequest = new JSONObject();
        consolidatedRequest.put("agentid", "FHIR_Analyser");

        JSONObject data = new JSONObject();
        data.put("doc_fhir", docFhirArray);
        data.put("consolidatedAt", System.currentTimeMillis());
        data.put("totalDocuments", docFhirArray.length());

        consolidatedRequest.put("data", data);

        String consolidatedJson = consolidatedRequest.toString(2);
        log.info("Consolidated FHIR request ({} bytes) with {} doc_fhir entries",
                consolidatedJson.length(), docFhirArray.length());

        // Store consolidated request in MinIO
        String minioPath = storeConsolidatedRequest(tenantId, ticketId, workflowKey,
                stageNumber, taskName, consolidatedRequest);

        execution.setVariable("fhirConsolidatorMinioPath", minioPath);
        execution.setVariable("fhirConsolidatedRequest", consolidatedJson);

        log.info("=== FHIR Consolidator Completed ===");
    }

    private String storeConsolidatedRequest(String tenantId, String ticketId, String workflowKey,
                                            int stageNumber, String taskName,
                                            JSONObject consolidatedRequest) {
        try {
            StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);
            Properties props = ConfigurationService.getTenantProperties(tenantId);
            String rootFolder = props.getProperty("storage.minio.bucketName", "insurance-claims");

            byte[] content = consolidatedRequest.toString(2).getBytes(StandardCharsets.UTF_8);

            String minioPath = StoragePathBuilder.buildUserProcessedPath(
                    rootFolder, tenantId, workflowKey, ticketId,
                    stageNumber, taskName, "consolidated.json"
            );

            storage.uploadDocument(minioPath, content, "application/json");

            log.info("Stored consolidated FHIR request at: {}", minioPath);
            return minioPath;

        } catch (Exception e) {
            log.error("Failed to store consolidated FHIR request in MinIO", e);
            throw new RuntimeException("Could not store consolidated FHIR request", e);
        }
    }
}