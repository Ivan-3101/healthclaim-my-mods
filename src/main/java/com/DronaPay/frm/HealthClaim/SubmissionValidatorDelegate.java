package com.DronaPay.frm.HealthClaim;

import com.DronaPay.frm.HealthClaim.generic.services.AgentResultStorageService;
import com.DronaPay.frm.HealthClaim.generic.services.ConfigurationService;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.util.EntityUtils;
import org.cibseven.bpm.engine.delegate.BpmnError;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.json.JSONArray;
import org.json.JSONObject;

import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class SubmissionValidatorDelegate implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("=== Submission Validator Started ===");

        String tenantId = execution.getTenantId();
        String ticketId = String.valueOf(execution.getVariable("TicketID"));

        // CHANGE: Use BPMN Activity ID
        String stageName = execution.getCurrentActivityId();

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
            throw new BpmnError("submissionValidatorFailed", "Missing fhirConsolidatorMinioPath");
        }

        log.info("Retrieving consolidated FHIR request from MinIO: {}", consolidatorMinioPath);

        Map<String, Object> result = AgentResultStorageService.retrieveAgentResult(tenantId, consolidatorMinioPath);
        String consolidatedRequest = (String) result.get("apiResponse");

        if (consolidatedRequest == null || consolidatedRequest.trim().isEmpty()) {
            log.error("Empty consolidated request retrieved from MinIO");
            throw new BpmnError("submissionValidatorFailed", "Empty consolidated FHIR request in MinIO");
        }

        log.info("Retrieved consolidated FHIR request ({} bytes) from MinIO", consolidatedRequest.length());

        JSONObject requestJson = new JSONObject(consolidatedRequest);
        requestJson.put("agentid", "Submission_Validator");
        String modifiedRequest = requestJson.toString();

        log.info("Modified agentid to Submission_Validator");

        APIServices apiServices = new APIServices(tenantId, workflowConfig);
        CloseableHttpResponse response = apiServices.callAgent(modifiedRequest);

        String resp = EntityUtils.toString(response.getEntity());
        int statusCode = response.getStatusLine().getStatusCode();

        log.info("Submission_Validator API status: {}", statusCode);
        log.debug("Submission_Validator API response: {}", resp);

        if (statusCode != 200) {
            log.error("Submission_Validator agent failed with status: {}", statusCode);
            throw new BpmnError("submissionValidatorFailed",
                    "Submission_Validator agent failed with status: " + statusCode);
        }

        Map<String, Object> fullResult = AgentResultStorageService.buildResultMap(
                "Submission_Validator", statusCode, resp, new HashMap<>());

        // CHANGE: Use stageName
        String validatorMinioPath = AgentResultStorageService.storeAgentResult(
                tenantId, ticketId, stageName, "consolidated", fullResult);

        log.info("Stored Submission_Validator result at: {}", validatorMinioPath);

        extractAndSetProcessVariables(execution, resp);

        execution.setVariable("submissionValidatorMinioPath", validatorMinioPath);
        execution.setVariable("submissionValidatorSuccess", true);

        log.info("=== Submission Validator Completed ===");
    }

    private void extractAndSetProcessVariables(DelegateExecution execution, String response) {
        try {
            JSONObject responseJson = new JSONObject(response);

            if (responseJson.has("answer")) {
                JSONObject answer = responseJson.getJSONObject("answer");

                if (answer.has("missing_documents")) {
                    JSONArray missingDocs = answer.getJSONArray("missing_documents");
                    String missingDocuments = missingDocs.toString(2);
                    execution.setVariable("missingDocuments", missingDocuments);
                    log.info("Missing Documents: {}", missingDocuments);
                }
            }

        } catch (Exception e) {
            log.error("Error extracting process variables from Submission Validator response", e);
        }
    }
}