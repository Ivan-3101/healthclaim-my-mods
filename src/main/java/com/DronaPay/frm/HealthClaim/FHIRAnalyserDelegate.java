package com.DronaPay.frm.HealthClaim;

import com.DronaPay.frm.HealthClaim.generic.services.AgentResultStorageService;
import com.DronaPay.frm.HealthClaim.generic.services.ConfigurationService;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.util.EntityUtils;
import org.camunda.bpm.engine.delegate.BpmnError;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.json.JSONObject;

import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;

/**
 * FHIR Analyser Delegate
 *
 * Calls the FHIR_Analyser agent with the consolidated FHIR request.
 *
 * Input: Consolidated FHIR request from MinIO (built by FHIRConsolidatorDelegate)
 * Output: FHIR analysis response with validation checks and recommendations
 *
 * Stores the response in MinIO and sets process variables:
 * - claimStatus: Overall claim status (Approved/Review Required/Rejected)
 * - claimSummary: Summary of the analysis
 * - validationChecks: JSON array of validation results
 * - finalRecommendation: Final recommendation from the agent
 */
@Slf4j
public class FHIRAnalyserDelegate implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("=== FHIR Analyser Started ===");

        String tenantId = execution.getTenantId();
        String ticketId = String.valueOf(execution.getVariable("TicketID"));

        log.info("TicketID: {}, TenantID: {}", ticketId, tenantId);

        // 1. Load workflow configuration
        Connection conn = execution.getProcessEngine()
                .getProcessEngineConfiguration()
                .getDataSource()
                .getConnection();

        JSONObject workflowConfig = ConfigurationService.loadWorkflowConfig("HealthClaim", tenantId, conn);
        conn.close();

        // 2. Get consolidated FHIR request from process variable
        String consolidatedRequest = (String) execution.getVariable("fhirConsolidatedRequest");

        if (consolidatedRequest == null || consolidatedRequest.trim().isEmpty()) {
            log.error("No consolidated FHIR request found in process variables");
            throw new BpmnError("fhirAnalyserFailed", "Missing consolidated FHIR request");
        }

        log.info("Using consolidated FHIR request ({} bytes)", consolidatedRequest.length());

        // 3. Call FHIR_Analyser agent
        APIServices apiServices = new APIServices(tenantId, workflowConfig);
        CloseableHttpResponse response = apiServices.callAgent(consolidatedRequest);

        String resp = EntityUtils.toString(response.getEntity());
        int statusCode = response.getStatusLine().getStatusCode();

        log.info("FHIR_Analyser API status: {}", statusCode);
        log.debug("FHIR_Analyser API response: {}", resp);

        if (statusCode != 200) {
            log.error("FHIR_Analyser agent failed with status: {}", statusCode);
            throw new BpmnError("fhirAnalyserFailed",
                    "FHIR_Analyser agent failed with status: " + statusCode);
        }

        // 4. Store result in MinIO
        Map<String, Object> fullResult = AgentResultStorageService.buildResultMap(
                "FHIR_Analyser", statusCode, resp, new HashMap<>());

        String minioPath = AgentResultStorageService.storeAgentResultStageWise(
                tenantId, ticketId, "consolidated", "FHIR_Analyser", fullResult);

        log.info("Stored FHIR_Analyser result at: {}", minioPath);

        // 5. Extract key fields from response and set as process variables
        extractAndSetProcessVariables(execution, resp);

        // 6. Set MinIO path for reference
        execution.setVariable("fhirAnalyserMinioPath", minioPath);
        execution.setVariable("fhirAnalyserSuccess", true);

        log.info("=== FHIR Analyser Completed ===");
    }

    /**
     * Extract key fields from FHIR Analyser response and set as process variables
     */
    private void extractAndSetProcessVariables(DelegateExecution execution, String response) {
        try {
            JSONObject responseJson = new JSONObject(response);

            // Extract claim_status
            if (responseJson.has("claim_status")) {
                String claimStatus = responseJson.getString("claim_status");
                execution.setVariable("claimStatus", claimStatus);
                log.info("Claim Status: {}", claimStatus);
            }

            // Extract claim_summary
            if (responseJson.has("claim_summary")) {
                String claimSummary = responseJson.getString("claim_summary");
                execution.setVariable("claimSummary", claimSummary);
                log.info("Claim Summary: {}", claimSummary);
            }

            // Extract validation_checks (as JSON string)
            if (responseJson.has("validation_checks")) {
                String validationChecks = responseJson.getJSONArray("validation_checks").toString(2);
                execution.setVariable("validationChecks", validationChecks);
                log.debug("Validation Checks: {}", validationChecks);
            }

            // Extract final_recommendation
            if (responseJson.has("final_recommendation")) {
                String finalRecommendation = responseJson.getString("final_recommendation");
                execution.setVariable("finalRecommendation", finalRecommendation);
                log.info("Final Recommendation: {}", finalRecommendation);
            }

            // Extract sequenced_groups (as JSON string)
            if (responseJson.has("sequenced_groups")) {
                String sequencedGroups = responseJson.getJSONArray("sequenced_groups").toString(2);
                execution.setVariable("sequencedGroups", sequencedGroups);
                log.debug("Sequenced Groups: {}", sequencedGroups);
            }

        } catch (Exception e) {
            log.error("Error extracting process variables from FHIR Analyser response", e);
            // Don't throw - we've stored the full response in MinIO
        }
    }
}