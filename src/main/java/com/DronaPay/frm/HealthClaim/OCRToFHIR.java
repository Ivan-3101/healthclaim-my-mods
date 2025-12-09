package com.DronaPay.frm.HealthClaim;

import com.DronaPay.frm.HealthClaim.generic.delegates.GenericAgentExecutorDelegate;
import com.DronaPay.frm.HealthClaim.generic.services.AgentResultStorageService;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.json.JSONObject;

import java.util.Map;

/**
 * OCR to FHIR Converter
 * Chains from OCR extraction and document classifier outputs
 * Converts OCR text to structured FHIR format
 */
@Slf4j
public class OCRToFHIR implements JavaDelegate {

    private final GenericAgentExecutorDelegate genericDelegate = new GenericAgentExecutorDelegate();

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.debug("OCRToFHIR wrapper called - delegating to generic");

        String filename = (String) execution.getVariable("attachment");
        String tenantId = execution.getTenantId();

        // Get fileProcessMap to retrieve OCR and classifier outputs
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> fileProcessMap =
                (Map<String, Map<String, Object>>) execution.getVariable("fileProcessMap");

        if (fileProcessMap == null || !fileProcessMap.containsKey(filename)) {
            log.error("No fileProcessMap entry for file: {}", filename);
            throw new RuntimeException("Missing file process data for: " + filename);
        }

        Map<String, Object> fileResults = fileProcessMap.get(filename);

        // Build request body with OCR text and document structure
        JSONObject requestData = new JSONObject();

        // Get OCR text from previous agent output
        if (fileResults.containsKey("openaiVisionOutput")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> ocrOutput =
                    (Map<String, Object>) fileResults.get("openaiVisionOutput");

            String ocrMinioPath = (String) ocrOutput.get("minioPath");
            if (ocrMinioPath != null) {
                try {
                    Map<String, Object> ocrResult =
                            AgentResultStorageService.retrieveAgentResult(tenantId, ocrMinioPath);
                    String ocrResponse = (String) ocrResult.get("apiResponse");

                    // Parse OCR response to get the actual text
                    JSONObject ocrJson = new JSONObject(ocrResponse);
                    if (ocrJson.has("answer")) {
                        requestData.put("ocr_text", ocrJson.get("answer"));
                    }
                } catch (Exception e) {
                    log.error("Failed to retrieve OCR result from MinIO", e);
                    throw new RuntimeException("Cannot retrieve OCR data for FHIR conversion", e);
                }
            }
        }

        // Get document structure from classifier output
        if (fileResults.containsKey("Document_ClassifierOutput")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> classifierOutput =
                    (Map<String, Object>) fileResults.get("Document_ClassifierOutput");

            String classifierMinioPath = (String) classifierOutput.get("minioPath");
            if (classifierMinioPath != null) {
                try {
                    Map<String, Object> classifierResult =
                            AgentResultStorageService.retrieveAgentResult(tenantId, classifierMinioPath);
                    String classifierResponse = (String) classifierResult.get("apiResponse");

                    // Parse classifier response
                    JSONObject classifierJson = new JSONObject(classifierResponse);
                    if (classifierJson.has("answer")) {
                        requestData.put("doc_structure", classifierJson.get("answer"));
                    }
                } catch (Exception e) {
                    log.warn("Failed to retrieve classifier result, proceeding without doc_structure", e);
                }
            }
        }

        // Set the chained request data as a process variable for GenericAgentExecutorDelegate
        execution.setVariable("ocrToFhirInputData", requestData.toString());

        // Build agent config
        JSONObject agentConfig = new JSONObject();
        agentConfig.put("agentId", "ocrToFhir");  // FIXED: Changed from "ocrTostatic" to "ocrToFhir"
        agentConfig.put("displayName", "OCR to FHIR");
        agentConfig.put("enabled", true);
        agentConfig.put("critical", true);

        JSONObject config = new JSONObject();

        // Input mapping - uses chained data
        JSONObject inputMapping = new JSONObject();
        inputMapping.put("source", "processVariable");
        inputMapping.put("variableName", "ocrToFhirInputData");
        inputMapping.put("transformation", "parseJson");
        config.put("inputMapping", inputMapping);

        // Output mapping - stores full response in MinIO
        JSONObject outputMapping = new JSONObject();
        JSONObject variablesToSet = new JSONObject();

        // No need to set large FHIR response as process variable
        // Full response stored in MinIO automatically

        outputMapping.put("variablesToSet", variablesToSet);
        config.put("outputMapping", outputMapping);

        // Error handling
        JSONObject errorHandling = new JSONObject();
        errorHandling.put("onFailure", "throwError");
        errorHandling.put("errorCode", "fhirConversionFailed");
        config.put("errorHandling", errorHandling);

        agentConfig.put("config", config);

        // Set as current agent config
        execution.setVariable("currentAgentConfig", agentConfig);

        // Execute via generic delegate
        genericDelegate.execute(execution);

        log.debug("OCRToFHIR completed via generic delegate");
    }
}