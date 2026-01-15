package com.DronaPay.frm.HealthClaim;

import com.DronaPay.frm.HealthClaim.generic.services.AgentResultStorageService;
import com.DronaPay.frm.HealthClaim.generic.services.ConfigurationService;
import com.DronaPay.frm.HealthClaim.generic.services.ObjectStorageService;
import com.DronaPay.frm.HealthClaim.generic.storage.StorageProvider;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.util.EntityUtils;
import org.cibseven.bpm.engine.delegate.BpmnError;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.json.JSONObject;

import java.io.InputStream;
import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class OcrToStaticDelegate implements JavaDelegate {

    @Override
    @SuppressWarnings("unchecked")
    public void execute(DelegateExecution execution) throws Exception {

        String filename = (String) execution.getVariable("attachment");
        String ticketId = String.valueOf(execution.getVariable("TicketID"));
        String tenantId = execution.getTenantId();

        // CHANGE: Use BPMN Activity ID
        String stageName = execution.getCurrentActivityId();

        log.info("=== OcrToStatic Started for file: {} ===", filename);

        // CHANGE: Retrieve path from map instead of guessing
        Map<String, String> ocrRawResults = (Map<String, String>) execution.getVariable("ocrRawResults");
        String minioPath = null;

        if (ocrRawResults != null && ocrRawResults.containsKey(filename)) {
            minioPath = ocrRawResults.get(filename);
        } else {
            // Fallback just in case, though variable should exist
            String filenameWithoutExt = filename.replace(".pdf", "");
            // Warning: This path assumption might be broken if OCROnDoc ID changed but map wasn't passed.
            log.warn("ocrRawResults variable missing for {}, attempting default path construction", filename);
            // We can't reliably guess the folder name if it was dynamic, defaulting to what it might be?
            // Ideally we throw error, but let's try assuming previous behavior or strict error.
            throw new BpmnError("ocrToStaticFailed", "Missing input path for " + filename);
        }

        log.info("Fetching openaiVision result from MinIO: {}", minioPath);

        Connection conn = execution.getProcessEngine()
                .getProcessEngineConfiguration()
                .getDataSource()
                .getConnection();

        JSONObject workflowConfig = ConfigurationService.loadWorkflowConfig("HealthClaim", tenantId, conn);
        conn.close();

        StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);
        InputStream resultStream = storage.downloadDocument(minioPath);
        String resultJson = IOUtils.toString(resultStream, "UTF-8");

        JSONObject openaiVisionResult = new JSONObject(resultJson);

        if (!openaiVisionResult.has("answer")) {
            throw new BpmnError("MISSING_ANSWER", "openaiVision result missing answer field");
        }

        JSONObject answer = openaiVisionResult.getJSONObject("answer");

        log.info("Retrieved openaiVision answer with doc_type: {}", answer.optString("doc_type", "unknown"));

        JSONObject requestBody = new JSONObject();
        requestBody.put("data", answer);
        requestBody.put("agentid", "ocrTostatic");

        log.info("Calling ocrToStatic API with agentid: ocrTostatic");
        log.debug("Request body: {}", requestBody.toString());

        APIServices apiServices = new APIServices(tenantId, workflowConfig);
        CloseableHttpResponse response = apiServices.callAgent(requestBody.toString());

        String resp = EntityUtils.toString(response.getEntity());
        int statusCode = response.getStatusLine().getStatusCode();

        log.info("OcrToStatic API status: {}", statusCode);
        log.debug("OcrToStatic API response: {}", resp);

        if (statusCode != 200) {
            throw new BpmnError("ocrToStaticFailed", "OcrToStatic agent failed with status: " + statusCode);
        }

        Map<String, Object> fullResult = AgentResultStorageService.buildResultMap(
                "ocrToStatic", statusCode, resp, new HashMap<>());

        // CHANGE: Use stageName (Activity ID)
        String storedPath = AgentResultStorageService.storeAgentResult(
                tenantId, ticketId, stageName, filename, fullResult);

        // CHANGE: Store result path in variable for Consolidator
        Map<String, String> ocrToStaticResults = (Map<String, String>) execution.getVariable("ocrToStaticResults");
        if (ocrToStaticResults == null) {
            ocrToStaticResults = new HashMap<>();
        }
        ocrToStaticResults.put(filename, storedPath);
        execution.setVariable("ocrToStaticResults", ocrToStaticResults);

        log.info("Stored ocrToStatic result at: {}", storedPath);

        log.info("=== OcrToStatic Completed for file: {} ===", filename);
    }
}