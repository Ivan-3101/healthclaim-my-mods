package com.DronaPay.frm.HealthClaim;

import com.DronaPay.frm.HealthClaim.generic.services.AgentResultStorageService;
import com.DronaPay.frm.HealthClaim.generic.services.ConfigurationService;
import com.DronaPay.frm.HealthClaim.generic.services.ObjectStorageService;
import com.DronaPay.frm.HealthClaim.generic.storage.StorageProvider;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.util.EntityUtils;
import org.cibseven.bpm.engine.delegate.BpmnError;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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

        StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);
        InputStream stream = storage.downloadDocument(consolidatorMinioPath);
        byte[] content = stream.readAllBytes();
        String consolidatedRequest = new String(content, StandardCharsets.UTF_8);

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
            throw new BpmnError("fhirAnalyserFailed", "FHIR_Analyser agent failed with status: " + statusCode);
        }

        Map<String, Object> fullResult = AgentResultStorageService.buildResultMap(
                "FHIR_Analyser", statusCode, resp, new HashMap<>());

        String analyserMinioPath = AgentResultStorageService.storeAgentResultStageWise(
                tenantId, ticketId, "consolidated", "FHIR_Analyser", fullResult);

        log.info("Stored FHIR_Analyser result at: {}", analyserMinioPath);

        extractAndSetProcessVariables(execution, resp);

        execution.setVariable("fhirAnalyserMinioPath", analyserMinioPath);
        execution.setVariable("fhirAnalyserSuccess", true);

        log.info("=== FHIR Analyser Completed ===");
    }

    private void extractAndSetProcessVariables(DelegateExecution execution, String response) {
        try {
            JSONObject responseJson = new JSONObject(response);

            if (responseJson.has("answer")) {
                JSONObject answer = responseJson.getJSONObject("answer");

                if (answer.has("claim_status")) {
                    execution.setVariable("claimStatus", answer.getString("claim_status"));
                    log.info("Claim Status: {}", answer.getString("claim_status"));
                }

                if (answer.has("claim_summary")) {
                    String summary = answer.getString("claim_summary");
                    if (summary.length() > 3500) {
                        summary = summary.substring(0, 3500);
                    }
                    execution.setVariable("claimSummary", summary);
                    log.info("Claim Summary: {} chars", summary.length());
                }

                if (answer.has("final_recommendation")) {
                    execution.setVariable("finalRecommendation", answer.getString("final_recommendation"));
                    log.info("Final Recommendation: {}", answer.getString("final_recommendation"));
                }
            }

        } catch (Exception e) {
            log.error("Error extracting process variables from FHIR Analyser response", e);
        }
    }
}