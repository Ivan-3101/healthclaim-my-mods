package com.DronaPay.frm.HealthClaim;

import com.DronaPay.frm.HealthClaim.generic.services.AgentResultStorageService;
import com.DronaPay.frm.HealthClaim.generic.services.ConfigurationService;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.util.EntityUtils;
import org.cibseven.bpm.engine.delegate.BpmnError;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.json.JSONObject;

import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class FHIRAnalyserDelegate implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("=== FHIR Analyser Started ===");

        String tenantId = execution.getTenantId();
        String ticketId = String.valueOf(execution.getVariable("TicketID"));

        log.info("TicketID: {}, TenantID: {}", ticketId, tenantId);

        Connection conn = execution.getProcessEngine()
                .getProcessEngineConfiguration()
                .getDataSource()
                .getConnection();

        JSONObject workflowConfig = ConfigurationService.loadWorkflowConfig("HealthClaim", tenantId, conn);
        conn.close();

        String consolidatorMinioPath = (String) execution.getVariable("fhirConsolidatorMinioPath");

        if (consolidatorMinioPath == null || consolidatorMinioPath.trim().isEmpty()) {
            log.error("No fhirConsolidatorMinioPath found in process variables");
            throw new BpmnError("fhirAnalyserFailed", "Missing fhirConsolidatorMinioPath");
        }

        log.info("Retrieving consolidated FHIR request from MinIO: {}", consolidatorMinioPath);

        Map<String, Object> result = AgentResultStorageService.retrieveAgentResult(tenantId, consolidatorMinioPath);
        String consolidatedRequest = (String) result.get("apiResponse");

        if (consolidatedRequest == null || consolidatedRequest.trim().isEmpty()) {
            log.error("Empty consolidated request retrieved from MinIO");
            throw new BpmnError("fhirAnalyserFailed", "Empty consolidated FHIR request in MinIO");
        }

        log.info("Retrieved consolidated FHIR request ({} bytes) from MinIO", consolidatedRequest.length());

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

        Map<String, Object> fullResult = AgentResultStorageService.buildResultMap(
                "FHIR_Analyser", statusCode, resp, new HashMap<>());

        String analyserMinioPath = AgentResultStorageService.storeAgentResult(
                tenantId, ticketId, "FHIR_Analyser", "consolidated", fullResult);

        log.info("Stored FHIR_Analyser result at: {}", analyserMinioPath);

        extractAndSetProcessVariables(execution, resp);

        execution.setVariable("fhirAnalyserMinioPath", analyserMinioPath);
        execution.setVariable("fhirAnalyserSuccess", true);

        log.info("=== FHIR Analyser Completed ===");
    }

    private void extractAndSetProcessVariables(DelegateExecution execution, String response) {
        try {
            JSONObject responseJson = new JSONObject(response);

            if (responseJson.has("claim_status")) {
                String claimStatus = responseJson.getString("claim_status");
                execution.setVariable("claimStatus", claimStatus);
                log.info("Claim Status: {}", claimStatus);
            }

            if (responseJson.has("claim_summary")) {
                String claimSummary = responseJson.getString("claim_summary");
                execution.setVariable("claimSummary", claimSummary);
                log.info("Claim Summary: {}", claimSummary);
            }

            if (responseJson.has("validation_checks")) {
                String validationChecks = responseJson.getJSONArray("validation_checks").toString(2);
                execution.setVariable("validationChecks", validationChecks);
                log.debug("Validation Checks: {}", validationChecks);
            }

            if (responseJson.has("final_recommendation")) {
                String finalRecommendation = responseJson.getString("final_recommendation");
                execution.setVariable("finalRecommendation", finalRecommendation);
                log.info("Final Recommendation: {}", finalRecommendation);
            }

            if (responseJson.has("sequenced_groups")) {
                String sequencedGroups = responseJson.getJSONArray("sequenced_groups").toString(2);
                execution.setVariable("sequencedGroups", sequencedGroups);
                log.debug("Sequenced Groups: {}", sequencedGroups);
            }

        } catch (Exception e) {
            log.error("Error extracting process variables from FHIR Analyser response", e);
        }
    }
}