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
public class UIDisplayerDelegate implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("=== UI Displayer Started ===");

        String tenantId = execution.getTenantId();
        String ticketId = String.valueOf(execution.getVariable("TicketID"));
        String workflowKey = StoragePathBuilder.getWorkflowType(execution);
        String taskName = StoragePathBuilder.getTaskName(execution);
        int stageNumber = WorkflowStageMapping.getStageNumber(workflowKey, taskName);

        if (stageNumber == -1) {
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
            throw new BpmnError("uiDisplayerFailed", "Missing fhirConsolidatorMinioPath");
        }

        log.info("Retrieving consolidated FHIR request from MinIO: {}", consolidatorMinioPath);

        StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);
        InputStream stream = storage.downloadDocument(consolidatorMinioPath);
        byte[] content = stream.readAllBytes();
        String jsonString = new String(content, StandardCharsets.UTF_8);

        JSONObject consolidatedJson = new JSONObject(jsonString);

        // Extract commonData and flatten it to root data level for agent
        JSONObject requestToAgent = new JSONObject();
        requestToAgent.put("agentid", "UI_Displayer");

        if (consolidatedJson.has("data")) {
            JSONObject dataObj = consolidatedJson.getJSONObject("data");

            if (dataObj.has("commonData")) {
                JSONObject commonData = dataObj.getJSONObject("commonData");

                // Flatten: put commonData fields directly under "data"
                requestToAgent.put("data", commonData);

                log.info("Flattened {} fields from commonData to root data level", commonData.length());
            } else {
                log.warn("No commonData found, sending entire data object");
                requestToAgent.put("data", dataObj);
            }
        } else {
            log.error("No data field in consolidated JSON");
            throw new BpmnError("uiDisplayerFailed", "Invalid consolidated JSON structure");
        }

        String modifiedRequest = requestToAgent.toString();
        log.info("Calling UI_Displayer API with flattened data ({} bytes)", modifiedRequest.length());

        APIServices apiServices = new APIServices(tenantId, workflowConfig);
        CloseableHttpResponse response = apiServices.callAgent(modifiedRequest);

        String resp = EntityUtils.toString(response.getEntity());
        int statusCode = response.getStatusLine().getStatusCode();

        log.info("UI_Displayer API status: {}", statusCode);
        log.debug("UI_Displayer API response: {}", resp);

        if (statusCode != 200) {
            throw new BpmnError("uiDisplayerFailed", "UI_Displayer agent failed with status: " + statusCode);
        }

        // Store result
        Properties props = ConfigurationService.getTenantProperties(tenantId);
        String rootFolder = props.getProperty("storage.minio.bucketName", "insurance-claims");

        JSONObject result = new JSONObject();
        result.put("agentId", "UI_Displayer");
        result.put("statusCode", statusCode);
        result.put("success", true);
        result.put("rawResponse", resp);
        result.put("extractedData", new HashMap<>());
        result.put("timestamp", System.currentTimeMillis());

        byte[] resultContent = result.toString(2).getBytes(StandardCharsets.UTF_8);

        String storagePath = StoragePathBuilder.buildTaskDocsPath(
                rootFolder, tenantId, workflowKey, ticketId,
                stageNumber, taskName, "consolidated.json"
        );

        storage.uploadDocument(storagePath, resultContent, "application/json");
        log.info("Stored UI_Displayer result at: {}", storagePath);

        execution.setVariable("uiDisplayerMinioPath", storagePath);
        execution.setVariable("uiDisplayerSuccess", true);

        log.info("=== UI Displayer Completed ===");
    }
}