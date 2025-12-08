package com.DronaPay.frm.HealthClaim;

import com.DronaPay.frm.HealthClaim.generic.delegates.GenericAgentExecutorDelegate;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.json.JSONObject;

/**
 * FHIR Analyser - Validates and analyzes consolidated FHIR data
 *
 * Input: consolidatedFhir variable (merged FHIR data from all documents)
 * Output: FHIR validation results and analysis
 */
@Slf4j
public class FHIRAnalyserDelegate implements JavaDelegate {

    private final GenericAgentExecutorDelegate genericDelegate = new GenericAgentExecutorDelegate();

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("=== FHIR Analyser Started ===");

        // Check if consolidated FHIR data exists
        String consolidatedFhir = (String) execution.getVariable("consolidatedFhir");
        if (consolidatedFhir == null || consolidatedFhir.isEmpty()) {
            log.error("No consolidated FHIR data available");
            execution.setVariable("fhirAnalysisStatus", "failed");
            execution.setVariable("fhirAnalysisError", "No consolidated FHIR data");
            return;
        }

        // Build agent config for FHIR Analyser
        JSONObject agentConfig = new JSONObject();
        agentConfig.put("agentId", "fhirAnalyser");
        agentConfig.put("displayName", "FHIR Analyser");
        agentConfig.put("enabled", true);
        agentConfig.put("critical", false);

        JSONObject config = new JSONObject();

        // Input mapping - uses consolidatedFhir process variable
        JSONObject inputMapping = new JSONObject();
        inputMapping.put("source", "processVariable");
        inputMapping.put("variableName", "consolidatedFhir");
        inputMapping.put("transformation", "none"); // Already JSON
        config.put("inputMapping", inputMapping);

        // Output mapping - extract validation results
        JSONObject outputMapping = new JSONObject();
        JSONObject variablesToSet = new JSONObject();

        // Extract claim status
        JSONObject claimStatusMapping = new JSONObject();
        claimStatusMapping.put("jsonPath", "/claim_status");
        claimStatusMapping.put("dataType", "string");
        variablesToSet.put("fhirClaimStatus", claimStatusMapping);

        // Extract claim summary
        JSONObject claimSummaryMapping = new JSONObject();
        claimSummaryMapping.put("jsonPath", "/claim_summary");
        claimSummaryMapping.put("dataType", "string");
        variablesToSet.put("fhirClaimSummary", claimSummaryMapping);

        // Extract validation checks (will be stored in MinIO, summary in variable)
        JSONObject validationMapping = new JSONObject();
        validationMapping.put("jsonPath", "/validation_checks");
        validationMapping.put("dataType", "json");
        variablesToSet.put("fhirValidationChecks", validationMapping);

        // Extract final recommendation
        JSONObject recommendationMapping = new JSONObject();
        recommendationMapping.put("jsonPath", "/final_recommendation");
        recommendationMapping.put("dataType", "string");
        variablesToSet.put("fhirRecommendation", recommendationMapping);

        outputMapping.put("variablesToSet", variablesToSet);
        config.put("outputMapping", outputMapping);

        // Error handling
        JSONObject errorHandling = new JSONObject();
        errorHandling.put("onFailure", "logAndContinue");
        errorHandling.put("continueOnError", true);
        config.put("errorHandling", errorHandling);

        agentConfig.put("config", config);

        // Set as current agent config
        execution.setVariable("currentAgentConfig", agentConfig);

        // Execute via generic delegate
        genericDelegate.execute(execution);

        // Set analysis status
        String claimStatus = (String) execution.getVariable("fhirClaimStatus");
        if (claimStatus != null && !claimStatus.isEmpty()) {
            execution.setVariable("fhirAnalysisStatus", "success");
            log.info("FHIR Analysis completed with status: {}", claimStatus);
        } else {
            execution.setVariable("fhirAnalysisStatus", "failed");
            log.warn("FHIR Analysis did not return claim status");
        }

        log.info("=== FHIR Analyser Completed ===");
    }
}