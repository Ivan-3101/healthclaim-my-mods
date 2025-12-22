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
            log.warn("Stage number not found for task '{}', using previous + 1", taskName);
            stageNumber = StoragePathBuilder.getStageNumber(execution) + 1;
        }

        log.info("Stage {}: {}", stageNumber, taskName);

        @SuppressWarnings("unchecked")
        List<String> splitDocumentVars = (List<String>) execution.getVariable("splitDocumentVars");

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> fileProcessMap =
                (Map<String, Map<String, Object>>) execution.getVariable("fileProcessMap");

        if (splitDocumentVars == null || splitDocumentVars.isEmpty()) {
            throw new RuntimeException("No split documents found for FHIR consolidation");
        }

        // Create consolidated FHIR JSON
        JSONObject consolidatedFhir = new JSONObject();
        JSONObject commonData = new JSONObject();
        JSONArray documents = new JSONArray();

        Map<String, Object> mergedFields = new HashMap<>();

        for (String filename : splitDocumentVars) {
            Map<String, Object> fileResults = fileProcessMap.get(filename);

            if (fileResults == null) {
                log.warn("No processing results for {}, skipping", filename);
                continue;
            }

            // Get ocrToStatic agent output
            Map<String, Object> ocrToStaticResult = (Map<String, Object>) fileResults.get("ocrToStatic");

            if (ocrToStaticResult == null) {
                log.warn("No ocrToStatic result for {}, skipping", filename);
                continue;
            }

            Map<String, Object> extractedData = (Map<String, Object>) ocrToStaticResult.get("extractedData");

            if (extractedData != null) {
                // Merge fields preferring non-null values
                for (Map.Entry<String, Object> entry : extractedData.entrySet()) {
                    if (entry.getValue() != null && !mergedFields.containsKey(entry.getKey())) {
                        mergedFields.put(entry.getKey(), entry.getValue());
                    }
                }
            }

            JSONObject docEntry = new JSONObject();
            docEntry.put("filename", filename);
            docEntry.put("extractedData", extractedData != null ? extractedData : new HashMap<>());
            documents.put(docEntry);
        }

        // Build consolidated request
        for (Map.Entry<String, Object> entry : mergedFields.entrySet()) {
            commonData.put(entry.getKey(), entry.getValue());
        }

        consolidatedFhir.put("commonData", commonData);
        consolidatedFhir.put("documents", documents);
        consolidatedFhir.put("totalDocuments", documents.length());
        consolidatedFhir.put("consolidatedAt", System.currentTimeMillis());

        JSONObject consolidatedRequest = new JSONObject();
        consolidatedRequest.put("data", consolidatedFhir);
        consolidatedRequest.put("agentid", "FHIR_Analyser");

        String consolidatedJson = consolidatedRequest.toString(2);
        log.info("Consolidated FHIR request ({} bytes) ready for FHIR_Analyser",
                consolidatedJson.length());

        // Store consolidated request in MinIO using NEW structure (userdoc/processed/)
        String minioPath = storeConsolidatedRequest(tenantId, ticketId, workflowKey,
                stageNumber, taskName, consolidatedRequest);

        execution.setVariable("fhirConsolidatorMinioPath", minioPath);
        execution.setVariable("fhirConsolidatedRequest", consolidatedJson);

        log.info("=== FHIR Consolidator Completed ===");
    }

    /**
     * Store consolidated FHIR request in MinIO using NEW folder structure
     *
     * Pattern: {rootFolder}/{tenantId}/{workflowType}/{ticketId}/{stageNumber}_{taskName}/userdoc/processed/consolidated.json
     */
    private String storeConsolidatedRequest(String tenantId, String ticketId, String workflowKey,
                                            int stageNumber, String taskName,
                                            JSONObject consolidatedRequest) {
        try {
            StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);
            Properties props = ConfigurationService.getTenantProperties(tenantId);
            String rootFolder = props.getProperty("storage.minio.bucketName", "insurance-claims");

            byte[] content = consolidatedRequest.toString(2).getBytes(StandardCharsets.UTF_8);

            // Store in userdoc/processed/ since this is a consolidated/transformed document
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