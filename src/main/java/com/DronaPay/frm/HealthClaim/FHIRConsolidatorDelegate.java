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

import java.io.InputStream;
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

        if (splitDocumentVars == null || splitDocumentVars.isEmpty()) {
            throw new RuntimeException("No split documents found for FHIR consolidation");
        }

        Properties props = ConfigurationService.getTenantProperties(tenantId);
        String rootFolder = props.getProperty("storage.minio.bucketName", "insurance-claims");
        StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);

        // Build doc_fhir array by reading OCR_to_Static outputs from MinIO
        JSONArray docFhirArray = new JSONArray();

        // OCR_to_Static is stage 7
        int ocrToStaticStage = 7;
        String ocrToStaticTaskName = "OCR_to_Static";

        for (String filename : splitDocumentVars) {
            try {
                // Build MinIO path for OCR_to_Static output
                String jsonFilename = filename.replace(".pdf", ".json");
                String minioPath = StoragePathBuilder.buildTaskDocsPath(
                        rootFolder, tenantId, workflowKey, ticketId,
                        ocrToStaticStage, ocrToStaticTaskName, jsonFilename
                );

                log.debug("Reading OCR_to_Static output from: {}", minioPath);

                // Download and parse
                InputStream stream = storage.downloadDocument(minioPath);
                byte[] content = stream.readAllBytes();
                String jsonString = new String(content, StandardCharsets.UTF_8);

                JSONObject storedResult = new JSONObject(jsonString);

                if (!storedResult.has("rawResponse")) {
                    log.warn("No rawResponse in OCR_to_Static result for {}", filename);
                    continue;
                }

                String rawResponse = storedResult.getString("rawResponse");
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
                log.error("Failed to read OCR_to_Static output for {}: {}", filename, e.getMessage());
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

        log.info("Consolidated FHIR request with {} doc_fhir entries", docFhirArray.length());

        // Store consolidated request in MinIO
        String minioPath = storeConsolidatedRequest(tenantId, ticketId, workflowKey,
                stageNumber, taskName, consolidatedRequest);

        // ONLY store MinIO path, NOT the JSON content (too large for process variables)
        execution.setVariable("fhirConsolidatorMinioPath", minioPath);

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