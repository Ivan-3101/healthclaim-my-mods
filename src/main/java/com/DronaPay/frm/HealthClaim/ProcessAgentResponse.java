package com.DronaPay.frm.HealthClaim;

import com.DronaPay.frm.HealthClaim.generic.services.AgentResultStorageService;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
public class ProcessAgentResponse implements JavaDelegate {

    @SuppressWarnings("unchecked")
    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("=== Process Agent Response Started ===");
        log.info("TicketID: {}", execution.getVariable("TicketID"));

        String tenantId = execution.getTenantId();

        Map<String, Map<String, Object>> fileProcessMap = (Map<String, Map<String, Object>>) execution
                .getVariable("fileProcessMap");

        if (fileProcessMap == null || fileProcessMap.isEmpty()) {
            log.warn("fileProcessMap is null or empty, nothing to process");
            return;
        }

        // Initialize tracking lists
        List<String> successFiles = new ArrayList<>();
        List<String> failedFiles = new ArrayList<>();
        List<String> forgedDocs = new ArrayList<>();
        List<String> failedForgeApiCall = new ArrayList<>();
        List<String> failedOCRCall = new ArrayList<>();

        // Process each file's agent outputs
        for (String filename : fileProcessMap.keySet()) {
            Map<String, Object> fileResults = fileProcessMap.get(filename);

            log.debug("Processing agent outputs for file: {}", filename);

            // Process forgery detection output (if exists)
            if (fileResults.containsKey("forgeryagentOutput")) {
                processForgeryOutput(filename, fileResults, forgedDocs, failedForgeApiCall, tenantId);
            }

            // Process OCR output (if exists)
            if (fileResults.containsKey("openaiVisionOutput")) {
                processOCROutput(filename, fileResults, failedOCRCall, tenantId);
            }

            // Process FHIR conversion output (if exists) - EXTRACT FHIR FIELDS
            if (fileResults.containsKey("ocrToFhirOutput")) {
                processFHIROutput(filename, fileResults, successFiles, failedFiles, execution, tenantId);
            }

            // Process policy comparison output (if exists)
            if (fileResults.containsKey("policy_compOutput")) {
                processPolicyOutput(fileResults, execution, tenantId);
            }
        }

        // Set summary variables
        execution.setVariable("successFHIRCall", successFiles.stream().collect(Collectors.joining(",")));
        execution.setVariable("failedFHIRCall", failedFiles.stream().collect(Collectors.joining(",")));
        execution.setVariable("forgedDocs", forgedDocs.stream().collect(Collectors.joining(",")));
        execution.setVariable("failedForgeApiCall", failedForgeApiCall.stream().collect(Collectors.joining(",")));
        execution.setVariable("failedOCRCall", failedOCRCall.stream().collect(Collectors.joining(",")));

        log.info("=== Process Agent Response Completed ===");
        log.info("Success files: {}, Failed files: {}, Forged: {}",
                successFiles.size(), failedFiles.size(), forgedDocs.size());
    }

    /**
     * Process forgery detection agent output from MinIO
     */
    @SuppressWarnings("unchecked")
    private void processForgeryOutput(String filename, Map<String, Object> fileResults,
                                      List<String> forgedDocs, List<String> failedForgeApiCall,
                                      String tenantId) {

        Map<String, Object> forgeOutput = (Map<String, Object>) fileResults.get("forgeryagentOutput");
        String minioPath = (String) forgeOutput.get("minioPath");  // Changed from storagePath
        String apiCall = (String) forgeOutput.get("apiCall");

        if ("success".equals(apiCall)) {
            try {
                // Load result from MinIO
                Map<String, Object> result = AgentResultStorageService.retrieveAgentResult(tenantId, minioPath);

                Boolean isForged = (Boolean) result.get("isForged");
                if (isForged != null && isForged) {
                    forgedDocs.add(filename);
                    log.warn("Document flagged as forged: {}", filename);
                }
            } catch (Exception e) {
                log.error("Error loading forgery result from MinIO: {}", minioPath, e);
                failedForgeApiCall.add(filename);
            }
        } else {
            failedForgeApiCall.add(filename);
            log.error("Forgery detection failed for: {}", filename);
        }
    }

    /**
     * Process OCR agent output from MinIO
     */
    @SuppressWarnings("unchecked")
    private void processOCROutput(String filename, Map<String, Object> fileResults,
                                  List<String> failedOCRCall, String tenantId) {

        Map<String, Object> ocrOutput = (Map<String, Object>) fileResults.get("openaiVisionOutput");
        String apiCall = (String) ocrOutput.get("apiCall");

        if (!"success".equals(apiCall)) {
            failedOCRCall.add(filename);
            log.error("OCR failed for: {}", filename);
        }
    }

    /**
     * Process FHIR conversion agent output from MinIO
     * CRITICAL: This extracts individual FHIR fields for the user task form
     */
    @SuppressWarnings("unchecked")
    private void processFHIROutput(String filename, Map<String, Object> fileResults,
                                   List<String> successFiles, List<String> failedFiles,
                                   DelegateExecution execution, String tenantId) {

        Map<String, Object> fhirOutput = (Map<String, Object>) fileResults.get("ocrToFhirOutput");
        String minioPath = (String) fhirOutput.get("minioPath");  // Changed from storagePath
        String apiCall = (String) fhirOutput.get("apiCall");

        if ("success".equals(apiCall)) {
            successFiles.add(filename);
            log.info("FHIR conversion successful for: {}", filename);

            try {
                // Load result from MinIO
                Map<String, Object> result = AgentResultStorageService.retrieveAgentResult(tenantId, minioPath);

                String apiResponseStr = (String) result.get("apiResponse");
                JSONObject fhirResponse = new JSONObject(apiResponseStr);

                // Get the answer object which contains the FHIR bundle
                if (fhirResponse.has("answer")) {
                    Object answerObj = fhirResponse.get("answer");
                    JSONObject fhirBundle;

                    if (answerObj instanceof String) {
                        fhirBundle = new JSONObject((String) answerObj);
                    } else if (answerObj instanceof JSONObject) {
                        fhirBundle = (JSONObject) answerObj;
                    } else {
                        log.error("Unexpected answer type: {}", answerObj.getClass());
                        return;
                    }

                    // Extract from FHIR entry array
                    JSONArray entry = fhirBundle.optJSONArray("entry");

                    if (entry != null && entry.length() > 0) {
                        log.info("Extracting data from {} FHIR entries", entry.length());

                        for (int i = 0; i < entry.length(); i++) {
                            JSONObject entryObj = entry.getJSONObject(i);

                            // Patient resource
                            if (entryObj.optQuery("/resource/resourceType").equals("Patient")) {
                                setVariableSafe(execution, "patientName",
                                        entryObj.optQuery("/resource/name/0/text"));
                                setVariableSafe(execution, "gender",
                                        entryObj.optQuery("/resource/gender"));
                                setVariableSafe(execution, "contact",
                                        entryObj.optQuery("/resource/telecom/0/value"));
                            }

                            // Practitioner resource
                            if (entryObj.optQuery("/resource/resourceType").equals("Practitioner")) {
                                setVariableSafe(execution, "doctorIdentifier",
                                        entryObj.optQuery("/resource/identifier/0/value"));
                                setVariableSafe(execution, "doctorName",
                                        entryObj.optQuery("/resource/name/0/text"));
                            }

                            // Condition resource
                            if (entryObj.optQuery("/resource/resourceType").equals("Condition")) {
                                setVariableSafe(execution, "diseaseName",
                                        entryObj.optQuery("/resource/code/text"));
                            }

                            // Procedure resource
                            if (entryObj.optQuery("/resource/resourceType").equals("Procedure")) {
                                setVariableSafe(execution, "procedure",
                                        entryObj.optQuery("/resource/code/text"));
                                setVariableSafe(execution, "startDate",
                                        entryObj.optQuery("/resource/occurrenceDateTime"));
                            }

                            // MedicationRequest resource
                            if (entryObj.optQuery("/resource/resourceType").equals("MedicationRequest")) {
                                setVariableSafe(execution, "medicationName",
                                        entryObj.optQuery("/resource/contained/0/code/coding/0/display"));
                                setVariableSafe(execution, "dosageInstruction",
                                        entryObj.optQuery("/resource/dosageInstruction/0/text"));
                            }

                            // Coverage resource
                            if (entryObj.optQuery("/resource/resourceType").equals("Coverage")) {
                                setVariableSafe(execution, "insuranceCompany",
                                        entryObj.optQuery("/resource/payor/0/display"));
                                setVariableSafe(execution, "period",
                                        entryObj.optQuery("/resource/period/start"));
                            }

                            // Claim resource
                            if (entryObj.optQuery("/resource/resourceType").equals("Claim")) {
                                setVariableSafe(execution, "hospitalName",
                                        entryObj.optQuery("/resource/provider/display"));

                                Object claimAmountObj = entryObj.optQuery("/resource/total/value");
                                if (claimAmountObj != null) {
                                    if (claimAmountObj instanceof Number) {
                                        execution.setVariable("claimAmount", ((Number) claimAmountObj).longValue());
                                    } else {
                                        execution.setVariable("claimAmount", Long.parseLong(claimAmountObj.toString()));
                                    }
                                    log.debug("Set variable 'claimAmount' = '{}'", claimAmountObj);
                                }

                                // Extract claim items
                                JSONArray items = (JSONArray) entryObj.optQuery("/resource/item");
                                if (items != null && items.length() > 0) {
                                    StringBuilder claimFor = new StringBuilder();
                                    for (int j = 0; j < items.length(); j++) {
                                        JSONObject item = items.getJSONObject(j);
                                        String service = (String) item.optQuery("productOrService/coding/0/code");
                                        if (service != null) {
                                            if (claimFor.length() > 0) claimFor.append(", ");
                                            claimFor.append(service);
                                        }
                                    }
                                    if (claimFor.length() > 0) {
                                        setVariableSafe(execution, "claimFor", claimFor.toString());
                                    }
                                }
                            }
                        }

                        log.info("Successfully extracted FHIR fields for user task form");

                    } else {
                        log.warn("FHIR response has no entries");
                    }
                } else {
                    log.warn("FHIR response missing 'answer' field");
                }

            } catch (Exception e) {
                log.error("Error extracting FHIR fields from MinIO result", e);
            }

        } else {
            failedFiles.add(filename);
            log.error("FHIR conversion failed for: {}", filename);
        }
    }

    /**
     * Process policy comparison agent output from MinIO
     */
    @SuppressWarnings("unchecked")
    private void processPolicyOutput(Map<String, Object> fileResults, DelegateExecution execution, String tenantId) {

        Map<String, Object> policyOutput = (Map<String, Object>) fileResults.get("policy_compOutput");

        // Policy comparison variables should already be set by PolicyComparator
        // Just log the status
        String policyStatus = (String) execution.getVariable("policyComparatorStatus");
        String policyMissing = (String) execution.getVariable("policyMissingInfo");
        String policyIssues = (String) execution.getVariable("policyPotentialIssues");

        log.info("Policy Comparator Status: {}", policyStatus);
        log.info("Missing Policy Info: {}", policyMissing);
        log.info("Policy Issues: {}", policyIssues);
    }

    /**
     * Safely set a process variable, handling null values
     */
    private void setVariableSafe(DelegateExecution execution, String variableName, Object value) {
        if (value != null && !JSONObject.NULL.equals(value)) {
            execution.setVariable(variableName, value);
            log.debug("Set variable '{}' = '{}'", variableName, value);
        } else {
            log.debug("Variable '{}' is null, skipping", variableName);
        }
    }
}