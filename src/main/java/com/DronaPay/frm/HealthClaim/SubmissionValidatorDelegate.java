package com.DronaPay.frm.HealthClaim;

import com.DronaPay.frm.HealthClaim.generic.config.WorkflowStageMapping;
import com.DronaPay.frm.HealthClaim.generic.services.ConfigurationService;
import com.DronaPay.frm.HealthClaim.generic.services.ObjectStorageService;
import com.DronaPay.frm.HealthClaim.generic.storage.StorageProvider;
import com.DronaPay.frm.HealthClaim.generic.utils.StoragePathBuilder;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.util.EntityUtils;
import org.cibseven.bpm.engine.delegate.BpmnError;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

@Slf4j
public class SubmissionValidatorDelegate implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("=== Submission Validator Started ===");

        String tenantId = execution.getTenantId();
        String ticketId = String.valueOf(execution.getVariable("TicketID"));
        String workflowKey = StoragePathBuilder.getWorkflowType(execution);
        String taskName = StoragePathBuilder.getTaskName(execution);
        int stageNumber = WorkflowStageMapping.getStageNumber(workflowKey, taskName);

        if (stageNumber == -1) {
            log.warn("Stage number not found for task '{}', using previous + 1", taskName);
            stageNumber = StoragePathBuilder.getStageNumber(execution) + 1;
        }

        execution.setVariable("stageNumber", stageNumber);
        log.info("Stage {}: {} - TicketID: {}, TenantID: {}", stageNumber, taskName, ticketId, tenantId);

        Connection conn = execution.getProcessEngine()
                .getProcessEngineConfiguration()
                .getDataSource()
                .getConnection();

        JSONObject workflowConfig = ConfigurationService.loadWorkflowConfig(workflowKey, tenantId, conn);
        conn.close();

        String consolidatorMinioPath = (String) execution.getVariable("fhirConsolidatorMinioPath");

        if (consolidatorMinioPath == null || consolidatorMinioPath.trim().isEmpty()) {
            log.error("No fhirConsolidatorMinioPath found in process variables");
            throw new BpmnError("submissionValidatorFailed", "Missing fhirConsolidatorMinioPath");
        }

        log.info("Retrieving consolidated FHIR request from MinIO: {}", consolidatorMinioPath);

        StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);
        InputStream stream = storage.downloadDocument(consolidatorMinioPath);
        byte[] content = stream.readAllBytes();
        String jsonString = new String(content, StandardCharsets.UTF_8);

        JSONObject consolidatedJson = new JSONObject(jsonString);
        consolidatedJson.put("agentid", "Submission_Validator");
        String modifiedRequest = consolidatedJson.toString();

        if (modifiedRequest == null || modifiedRequest.trim().isEmpty()) {
            log.error("Empty consolidated request retrieved from MinIO");
            throw new BpmnError("submissionValidatorFailed", "Empty consolidated FHIR request in MinIO");
        }

        log.info("Retrieved consolidated FHIR request ({} bytes) from MinIO", modifiedRequest.length());
        log.info("Modified agentid to Submission_Validator");

        APIServices apiServices = new APIServices(tenantId, workflowConfig);
        CloseableHttpResponse response = apiServices.callAgent(modifiedRequest);

        String resp = EntityUtils.toString(response.getEntity());
        int statusCode = response.getStatusLine().getStatusCode();

        log.info("Submission_Validator API status: {}", statusCode);
        log.debug("Submission_Validator API response: {}", resp);

        if (statusCode != 200) {
            log.error("Submission_Validator agent failed with status: {}", statusCode);
            throw new BpmnError("submissionValidatorFailed", "Submission_Validator agent failed with status: " + statusCode);
        }

        // Store result using NEW MinIO structure
        Properties props = ConfigurationService.getTenantProperties(tenantId);
        String rootFolder = props.getProperty("storage.minio.bucketName", "insurance-claims");

        JSONObject result = new JSONObject();
        result.put("agentId", "Submission_Validator");
        result.put("statusCode", statusCode);
        result.put("success", true);
        result.put("rawResponse", resp);
        result.put("extractedData", new HashMap<>());
        result.put("timestamp", System.currentTimeMillis());

        byte[] resultContent = result.toString(2).getBytes(StandardCharsets.UTF_8);
        String outputFilename = "consolidated.json";

        String storagePath = StoragePathBuilder.buildTaskDocsPath(
                rootFolder, tenantId, workflowKey, ticketId,
                stageNumber, taskName, outputFilename
        );

        storage.uploadDocument(storagePath, resultContent, "application/json");
        log.info("Stored Submission_Validator result at: {}", storagePath);

        extractAndSetProcessVariables(execution, resp);

        execution.setVariable("submissionValidatorMinioPath", storagePath);
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