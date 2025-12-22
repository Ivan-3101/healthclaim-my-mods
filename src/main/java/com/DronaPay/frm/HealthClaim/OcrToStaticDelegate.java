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
import java.util.Map;
import java.util.Properties;

@Slf4j
public class OcrToStaticDelegate implements JavaDelegate {

    @Override
    @SuppressWarnings("unchecked")
    public void execute(DelegateExecution execution) throws Exception {

        String filename = (String) execution.getVariable("attachment");
        String ticketId = String.valueOf(execution.getVariable("TicketID"));
        String tenantId = execution.getTenantId();
        String workflowKey = StoragePathBuilder.getWorkflowType(execution);
        String taskName = StoragePathBuilder.getTaskName(execution);
        int stageNumber = WorkflowStageMapping.getStageNumber(workflowKey, taskName);

        if (stageNumber == -1) {
            log.warn("Stage number not found for task '{}', using previous stageNumber", taskName);
            stageNumber = StoragePathBuilder.getStageNumber(execution);
            if (stageNumber == -1) {
                stageNumber = 7; // Default OcrToStatic stage
            }
        }

        // Don't set stageNumber in execution - this is a multi-instance loop
        log.info("Stage {}: {} - Processing file: {}", stageNumber, taskName, filename);

        // Get previous OCR stage info
        Map<String, Map<String, Object>> fileProcessMap =
                (Map<String, Map<String, Object>>) execution.getVariable("fileProcessMap");

        if (fileProcessMap == null) {
            throw new BpmnError("MISSING_FILE_PROCESS_MAP", "fileProcessMap not found");
        }

        Map<String, Object> fileResults = fileProcessMap.get(filename);
        if (fileResults == null) {
            throw new BpmnError("MISSING_FILE_RESULTS", "No results for file: " + filename);
        }

        Map<String, Object> ocrResult = (Map<String, Object>) fileResults.get("openaiVision");
        if (ocrResult == null) {
            throw new BpmnError("MISSING_OCR_RESULT", "No OCR result for: " + filename);
        }

        String rawResponse = (String) ocrResult.get("rawResponse");
        if (rawResponse == null) {
            throw new BpmnError("MISSING_RAW_RESPONSE", "No rawResponse in OCR result");
        }

        JSONObject openaiVisionResult = new JSONObject(rawResponse);
        if (!openaiVisionResult.has("answer")) {
            throw new BpmnError("MISSING_ANSWER", "openaiVision result missing answer field");
        }

        JSONObject answer = openaiVisionResult.getJSONObject("answer");
        log.info("Retrieved openaiVision answer with doc_type: {}", answer.optString("doc_type", "unknown"));

        // Build request for ocrToStatic
        JSONObject requestBody = new JSONObject();
        requestBody.put("data", answer);
        requestBody.put("agentid", "ocrTostatic");

        log.info("Calling ocrToStatic API");

        // Load workflow config
        Connection conn = execution.getProcessEngine()
                .getProcessEngineConfiguration()
                .getDataSource()
                .getConnection();
        JSONObject workflowConfig = ConfigurationService.loadWorkflowConfig(workflowKey, tenantId, conn);
        conn.close();

        // Call API
        APIServices apiServices = new APIServices(tenantId, workflowConfig);
        CloseableHttpResponse response = apiServices.callAgent(requestBody.toString());

        String resp = EntityUtils.toString(response.getEntity());
        int statusCode = response.getStatusLine().getStatusCode();

        log.info("OcrToStatic API status: {}", statusCode);
        log.debug("OcrToStatic API response: {}", resp);

        if (statusCode != 200) {
            throw new BpmnError("ocrToStaticFailed", "OcrToStatic agent failed with status: " + statusCode);
        }

        // Parse response to extract fields
        JSONObject apiResponse = new JSONObject(resp);
        Map<String, Object> extractedData = new HashMap<>();

        if (apiResponse.has("answer")) {
            JSONObject answerData = apiResponse.getJSONObject("answer");
            if (answerData.has("fields")) {
                extractedData.put("fields", answerData.getJSONObject("fields").toString());
            }
            if (answerData.has("doc_type")) {
                extractedData.put("doc_type", answerData.getString("doc_type"));
            }
        }

        // Store result in NEW MinIO structure
        Properties props = ConfigurationService.getTenantProperties(tenantId);
        String rootFolder = props.getProperty("storage.minio.bucketName", "insurance-claims");

        StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);

        JSONObject result = new JSONObject();
        result.put("agentId", "ocrToStatic");
        result.put("statusCode", statusCode);
        result.put("success", true);
        result.put("rawResponse", resp);
        result.put("extractedData", extractedData);
        result.put("timestamp", System.currentTimeMillis());

        byte[] content = result.toString(2).getBytes(StandardCharsets.UTF_8);

        String outputFilename = filename.replace(".pdf", ".json");
        String storagePath = StoragePathBuilder.buildTaskDocsPath(
                rootFolder, tenantId, workflowKey, ticketId,
                stageNumber, taskName, outputFilename
        );

        storage.uploadDocument(storagePath, content, "application/json");
        log.info("Stored ocrToStatic result at: {}", storagePath);

        // Update fileProcessMap
        Map<String, Object> agentResult = new HashMap<>();
        agentResult.put("statusCode", statusCode);
        agentResult.put("apiCall", "Success");
        agentResult.put("extractedData", extractedData);

        fileResults.put("ocrToStatic", agentResult);
        fileProcessMap.put(filename, fileResults);
        execution.setVariable("fileProcessMap", fileProcessMap);

        log.info("=== OcrToStatic Completed for file: {} ===", filename);
    }
}