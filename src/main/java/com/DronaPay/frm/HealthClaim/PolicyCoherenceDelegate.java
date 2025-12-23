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
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.HashMap;
import java.util.Properties;

@Slf4j
public class PolicyCoherenceDelegate implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("=== Policy Coherence Started ===");

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
            throw new BpmnError("policyCoherenceFailed", "Missing fhirConsolidatorMinioPath");
        }

        log.info("Retrieving consolidated FHIR request from MinIO: {}", consolidatorMinioPath);

        StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);
        InputStream stream = storage.downloadDocument(consolidatorMinioPath);
        byte[] content = stream.readAllBytes();
        String consolidatedRequest = new String(content, StandardCharsets.UTF_8);

        if (consolidatedRequest == null || consolidatedRequest.trim().isEmpty()) {
            log.error("Empty consolidated request retrieved from MinIO");
            throw new BpmnError("policyCoherenceFailed", "Empty consolidated FHIR request in MinIO");
        }

        log.info("Retrieved consolidated FHIR request ({} bytes) from MinIO", consolidatedRequest.length());

        String uiDisplayerData = getUIDisplayerData(execution, tenantId);

        if (uiDisplayerData == null) {
            log.error("No UI displayer data found");
            throw new BpmnError("policyCoherenceFailed", "Missing UI displayer data");
        }

        String policyCoherenceRequest = buildPolicyCoherenceRequest(consolidatedRequest, uiDisplayerData, ticketId);

        log.info("Built Policy Coherence request ({} bytes)", policyCoherenceRequest.length());

        APIServices apiServices = new APIServices(tenantId, workflowConfig);
        CloseableHttpResponse response = apiServices.callAgent(policyCoherenceRequest);

        String resp = EntityUtils.toString(response.getEntity());
        int statusCode = response.getStatusLine().getStatusCode();

        log.info("Policy Coherence API status: {}", statusCode);
        log.debug("Policy Coherence API response: {}", resp);

        if (statusCode != 200) {
            log.error("Policy Coherence agent failed with status: {}", statusCode);
            throw new BpmnError("policyCoherenceFailed", "Policy Coherence agent failed with status: " + statusCode);
        }

        // Store result using NEW MinIO structure
        Properties props = ConfigurationService.getTenantProperties(tenantId);
        String rootFolder = props.getProperty("storage.minio.bucketName", "insurance-claims");

        JSONObject result = new JSONObject();
        result.put("agentId", "policy_comp1");
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
        log.info("Stored Policy Coherence result at: {}", storagePath);

        execution.setVariable("policyCoherenceMinioPath", storagePath);
        execution.setVariable("policyCoherenceSuccess", true);

        log.info("=== Policy Coherence Completed ===");
    }

    private String getUIDisplayerData(DelegateExecution execution, String tenantId) throws Exception {
        String editedFormMinioPath = (String) execution.getVariable("editedFormMinioPath");

        if (editedFormMinioPath != null && !editedFormMinioPath.trim().isEmpty()) {
            log.info("Using edited UI displayer data from: {}", editedFormMinioPath);
            StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);
            InputStream stream = storage.downloadDocument(editedFormMinioPath);
            byte[] content = stream.readAllBytes();
            JSONObject json = new JSONObject(new String(content, StandardCharsets.UTF_8));
            return json.has("rawResponse") ? json.getString("rawResponse") : json.toString();
        }

        String uiDisplayerMinioPath = (String) execution.getVariable("uiDisplayerMinioPath");

        if (uiDisplayerMinioPath != null && !uiDisplayerMinioPath.trim().isEmpty()) {
            log.info("Using original UI displayer data from: {}", uiDisplayerMinioPath);
            StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);
            InputStream stream = storage.downloadDocument(uiDisplayerMinioPath);
            byte[] content = stream.readAllBytes();
            JSONObject json = new JSONObject(new String(content, StandardCharsets.UTF_8));
            return json.has("rawResponse") ? json.getString("rawResponse") : json.toString();
        }

        log.error("No UI displayer data found in process variables");
        return null;
    }

    private String buildPolicyCoherenceRequest(String consolidatedRequest, String uiDisplayerData, String ticketId) {
        JSONObject request = new JSONObject();
        request.put("agentid", "policy_comp1");

        JSONObject data = new JSONObject();
        data.put("consolidated_fhir", new JSONObject(consolidatedRequest));
        data.put("ui_displayer_output", new JSONObject(uiDisplayerData));
        data.put("ticket_id", ticketId);

        request.put("data", data);

        return request.toString();
    }
}