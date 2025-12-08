package com.DronaPay.frm.HealthClaim;

import com.DronaPay.frm.HealthClaim.generic.services.AgentResultStorageService;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Updated ProcessAgentResponse for new workflow structure
 *
 * Processes per-document agent outputs:
 * - Forgery detection
 * - Document classification
 * - OCR extraction
 * - OCR to FHIR conversion
 *
 * Does NOT consolidate FHIR - that's done by FHIRConsolidationDelegate
 */
@Slf4j
public class ProcessAgentResponse implements JavaDelegate {

    @SuppressWarnings("unchecked")
    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("=== Process Agent Response Started ===");
        log.info("TicketID: {}", execution.getVariable("TicketID"));

        String tenantId = execution.getTenantId();

        Map<String, Map<String, Object>> fileProcessMap =
                (Map<String, Map<String, Object>>) execution.getVariable("fileProcessMap");

        if (fileProcessMap == null || fileProcessMap.isEmpty()) {
            log.warn("fileProcessMap is null or empty, nothing to process");
            return;
        }

        // Initialize tracking lists
        List<String> forgedDocs = new ArrayList<>();
        List<String> failedForgeApiCall = new ArrayList<>();
        List<String> failedDocClassifier = new ArrayList<>();
        List<String> failedOCRCall = new ArrayList<>();
        List<String> failedOCRToFhir = new ArrayList<>();
        List<String> successfulDocs = new ArrayList<>();

        // Process each file's agent outputs
        for (String filename : fileProcessMap.keySet()) {
            Map<String, Object> fileResults = fileProcessMap.get(filename);
            log.debug("Processing agent outputs for file: {}", filename);

            boolean docSuccess = true;

            // Process forgery detection output
            if (fileResults.containsKey("forgeryagentOutput")) {
                if (!processForgeryOutput(filename, fileResults, forgedDocs,
                        failedForgeApiCall, tenantId)) {
                    docSuccess = false;
                }
            }

            // Process document classifier output
            if (fileResults.containsKey("Document_ClassifierOutput")) {
                if (!processDocClassifierOutput(filename, fileResults,
                        failedDocClassifier, tenantId)) {
                    docSuccess = false;
                }
            }

            // Process OCR output
            if (fileResults.containsKey("openaiVisionOutput")) {
                if (!processOCROutput(filename, fileResults, failedOCRCall, tenantId)) {
                    docSuccess = false;
                }
            }

            // Process OCR to FHIR output
            if (fileResults.containsKey("ocrToFhirOutput")) {
                if (!processOCRToFhirOutput(filename, fileResults,
                        failedOCRToFhir, tenantId)) {
                    docSuccess = false;
                }
            }

            if (docSuccess) {
                successfulDocs.add(filename);
            }
        }

        // Set summary variables
        execution.setVariable("forgedDocuments", String.join(", ", forgedDocs));
        execution.setVariable("failedForgeApiCall", String.join(", ", failedForgeApiCall));
        execution.setVariable("failedDocClassifier", String.join(", ", failedDocClassifier));
        execution.setVariable("failedOCRCall", String.join(", ", failedOCRCall));
        execution.setVariable("failedOCRToFhir", String.join(", ", failedOCRToFhir));
        execution.setVariable("successfulDocs", String.join(", ", successfulDocs));

        // Set flags for downstream decision making
        execution.setVariable("hasForgedDocs", !forgedDocs.isEmpty());
        execution.setVariable("hasFailedAgents",
                !failedForgeApiCall.isEmpty() ||
                        !failedDocClassifier.isEmpty() ||
                        !failedOCRCall.isEmpty() ||
                        !failedOCRToFhir.isEmpty());

        log.info("Processing complete: {} successful, {} forged, {} failed agents",
                successfulDocs.size(), forgedDocs.size(),
                failedForgeApiCall.size() + failedDocClassifier.size() +
                        failedOCRCall.size() + failedOCRToFhir.size());

        log.info("=== Process Agent Response Completed ===");
    }

    /**
     * Process forgery detection output from MinIO
     */
    @SuppressWarnings("unchecked")
    private boolean processForgeryOutput(String filename, Map<String, Object> fileResults,
                                         List<String> forgedDocs,
                                         List<String> failedForgeApiCall,
                                         String tenantId) {
        try {
            Map<String, Object> forgeOutput =
                    (Map<String, Object>) fileResults.get("forgeryagentOutput");
            String minioPath = (String) forgeOutput.get("minioPath");
            String apiCall = (String) forgeOutput.get("apiCall");

            if ("success".equals(apiCall)) {
                Map<String, Object> result =
                        AgentResultStorageService.retrieveAgentResult(tenantId, minioPath);

                Boolean isForged = (Boolean) result.get("isForged");
                if (isForged != null && isForged) {
                    forgedDocs.add(filename);
                    log.warn("Document flagged as forged: {}", filename);
                    return false;
                }
                return true;
            } else {
                failedForgeApiCall.add(filename);
                log.error("Forgery detection failed for: {}", filename);
                return false;
            }
        } catch (Exception e) {
            log.error("Error processing forgery output for: {}", filename, e);
            failedForgeApiCall.add(filename);
            return false;
        }
    }

    /**
     * Process document classifier output from MinIO
     */
    @SuppressWarnings("unchecked")
    private boolean processDocClassifierOutput(String filename, Map<String, Object> fileResults,
                                               List<String> failedDocClassifier,
                                               String tenantId) {
        try {
            Map<String, Object> classifierOutput =
                    (Map<String, Object>) fileResults.get("Document_ClassifierOutput");
            String apiCall = (String) classifierOutput.get("apiCall");

            if (!"success".equals(apiCall)) {
                failedDocClassifier.add(filename);
                log.error("Document classifier failed for: {}", filename);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.error("Error processing doc classifier output for: {}", filename, e);
            failedDocClassifier.add(filename);
            return false;
        }
    }

    /**
     * Process OCR output from MinIO
     */
    @SuppressWarnings("unchecked")
    private boolean processOCROutput(String filename, Map<String, Object> fileResults,
                                     List<String> failedOCRCall, String tenantId) {
        try {
            Map<String, Object> ocrOutput =
                    (Map<String, Object>) fileResults.get("openaiVisionOutput");
            String apiCall = (String) ocrOutput.get("apiCall");

            if (!"success".equals(apiCall)) {
                failedOCRCall.add(filename);
                log.error("OCR failed for: {}", filename);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.error("Error processing OCR output for: {}", filename, e);
            failedOCRCall.add(filename);
            return false;
        }
    }

    /**
     * Process OCR to FHIR output from MinIO
     * Just validates success - actual consolidation done by FHIRConsolidationDelegate
     */
    @SuppressWarnings("unchecked")
    private boolean processOCRToFhirOutput(String filename, Map<String, Object> fileResults,
                                           List<String> failedOCRToFhir, String tenantId) {
        try {
            Map<String, Object> fhirOutput =
                    (Map<String, Object>) fileResults.get("ocrToFhirOutput");
            String apiCall = (String) fhirOutput.get("apiCall");

            if (!"success".equals(apiCall)) {
                failedOCRToFhir.add(filename);
                log.error("OCR to FHIR failed for: {}", filename);
                return false;
            }

            log.info("OCR to FHIR successful for: {}", filename);
            return true;

        } catch (Exception e) {
            log.error("Error processing OCR to FHIR output for: {}", filename, e);
            failedOCRToFhir.add(filename);
            return false;
        }
    }
}