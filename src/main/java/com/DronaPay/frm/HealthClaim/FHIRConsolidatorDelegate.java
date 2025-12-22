package com.DronaPay.frm.HealthClaim;

import com.DronaPay.frm.HealthClaim.generic.services.AgentResultStorageService;
import lombok.extern.slf4j.Slf4j;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;

/**
 * FHIR Consolidator Delegate - Consolidates individual FHIR JSONs into single doc
 *
 * NEW structure:
 * - Reads from: 7_OcrToStatic/task-docs/{filename}.json
 * - Writes to: 8_FHIRConsolidator/userdoc/processed/consolidated.json
 */
@Slf4j
public class FHIRConsolidatorDelegate implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("=== FHIR Consolidator Started ===");

        String tenantId = execution.getTenantId();
        String ticketId = String.valueOf(execution.getVariable("TicketID"));
        String workflowKey = (String) execution.getVariable("WorkflowKey");
        if (workflowKey == null) {
            workflowKey = "HealthClaim";
        }

        log.info("TicketID: {}, TenantID: {}, WorkflowKey: {}", ticketId, tenantId, workflowKey);

        // Get list of split documents (processed files)
        @SuppressWarnings("unchecked")
        List<String> splitDocumentVars = (List<String>) execution.getVariable("splitDocumentVars");

        if (splitDocumentVars == null || splitDocumentVars.isEmpty()) {
            log.error("No split documents found");
            throw new RuntimeException("No documents to consolidate");
        }

        log.info("Processing {} documents for FHIR consolidation", splitDocumentVars.size());

        // Get fileProcessMap to find agent results
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> fileProcessMap =
                (Map<String, Map<String, Object>>) execution.getVariable("fileProcessMap");

        // Build consolidated doc_fhir array
        List<Object> docFhirList = new ArrayList<>();
        int successCount = 0;
        int failCount = 0;

        for (String filename : splitDocumentVars) {
            log.debug("Processing file: {}", filename);

            try {
                // Get ocrToStatic result from fileProcessMap
                if (fileProcessMap == null || !fileProcessMap.containsKey(filename)) {
                    log.warn("No fileProcessMap entry for: {}, skipping", filename);
                    failCount++;
                    continue;
                }

                Map<String, Object> fileResults = fileProcessMap.get(filename);

                if (!fileResults.containsKey("ocrToStaticOutput") && !fileResults.containsKey("ocrToFhirOutput")) {
                    log.warn("No ocrToStatic/ocrToFhir output for: {}, skipping", filename);
                    failCount++;
                    continue;
                }

                // Try both possible output keys (ocrToStatic and ocrToFhir for backward compatibility)
                @SuppressWarnings("unchecked")
                Map<String, Object> ocrOutput = fileResults.containsKey("ocrToStaticOutput")
                        ? (Map<String, Object>) fileResults.get("ocrToStaticOutput")
                        : (Map<String, Object>) fileResults.get("ocrToFhirOutput");

                String apiCall = (String) ocrOutput.get("apiCall");
                if (!"success".equals(apiCall)) {
                    log.warn("ocrToStatic failed for: {}, skipping", filename);
                    failCount++;
                    continue;
                }

                String minioPath = (String) ocrOutput.get("minioPath");

                // Retrieve result from MinIO
                Map<String, Object> result =
                        AgentResultStorageService.retrieveAgentResult(tenantId, minioPath);

                String rawResponse = (String) result.get("apiResponse");
                if (rawResponse == null || rawResponse.trim().isEmpty()) {
                    log.error("Empty apiResponse for file: {}, skipping", filename);
                    failCount++;
                    continue;
                }

                // Parse FHIR JSON
                JSONObject fhirDoc = new JSONObject(rawResponse);

                // Validate it has required fields
                if (!fhirDoc.has("doc_name")) {
                    log.warn("FHIR doc missing 'doc_name' for {}, adding default", filename);
                    fhirDoc.put("doc_name", filename);
                }

                docFhirList.add(fhirDoc);
                successCount++;

                log.info("Added FHIR doc for: {} (size: {} chars)", filename, rawResponse.length());

            } catch (Exception e) {
                log.error("Error processing FHIR for file: {}", filename, e);
                failCount++;
            }
        }

        if (docFhirList.isEmpty()) {
            log.error("No FHIR documents were successfully consolidated");
            throw new RuntimeException("FHIR consolidation produced no results");
        }

        log.info("FHIR Consolidation: {} success, {} failed", successCount, failCount);

        // Build consolidated request
        JSONObject consolidatedRequest = new JSONObject();
        consolidatedRequest.put("doc_fhir", new JSONArray(docFhirList));

        String consolidatedJson = consolidatedRequest.toString(2);

        // Store consolidated FHIR in NEW location
        // Path: insurance-claims/{tenantId}/{WorkflowType}/{TicketID}/8_FHIRConsolidator/userdoc/processed/consolidated.json
        String consolidatedPath = AgentResultStorageService.storeConsolidatedFhir(
                tenantId, workflowKey, ticketId, consolidatedJson);

        log.info("Stored consolidated FHIR at: {}", consolidatedPath);

        // Set process variable for downstream agents
        execution.setVariable("fhirConsolidatedRequest", consolidatedJson);
        execution.setVariable("consolidatedFhirPath", consolidatedPath);
        execution.setVariable("consolidatedDocCount", successCount);

        log.info("=== FHIR Consolidator Completed: {} documents consolidated ===", successCount);
    }
}