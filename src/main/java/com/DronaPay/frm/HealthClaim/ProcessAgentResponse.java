package com.DronaPay.frm.HealthClaim;

import com.DronaPay.frm.HealthClaim.generic.services.AgentResultStorageService;
import lombok.extern.slf4j.Slf4j;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Simplified ProcessAgentResponse - only tracks forgery and document classification
 * NO FHIR processing anymore
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

            if (docSuccess) {
                successfulDocs.add(filename);
            }
        }

        // Set summary variables
        execution.setVariable("forgedDocuments", forgedDocs);
        execution.setVariable("failedForgeryCheck", failedForgeApiCall);
        execution.setVariable("failedDocClassification", failedDocClassifier);
        execution.setVariable("successfulDocuments", successfulDocs);

        // Log summary
        log.info("=== Agent Processing Summary ===");
        log.info("Successful documents: {}", successfulDocs.size());
        log.info("Forged documents: {}", forgedDocs.size());
        log.info("Failed forgery checks: {}", failedForgeApiCall.size());
        log.info("Failed classifications: {}", failedDocClassifier.size());

        log.info("=== Process Agent Response Completed ===");
    }

    /**
     * Process forgery detection output from MinIO
     */
    @SuppressWarnings("unchecked")
    private boolean processForgeryOutput(String filename, Map<String, Object> fileResults,
                                         List<String> forgedDocs, List<String> failedForgeApiCall,
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
}