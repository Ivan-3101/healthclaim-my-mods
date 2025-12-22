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
public class OcrToStaticDelegate implements JavaDelegate {

    @Override
    @SuppressWarnings("unchecked")
    public void execute(DelegateExecution execution) throws Exception {

        String filename = (String) execution.getVariable("attachment");
        String ticketId = String.valueOf(execution.getVariable("TicketID"));
        String tenantId = execution.getTenantId();

        log.info("=== OcrToStatic Started for file: {} ===", filename);

        // 1. Get openaiVision result from fileProcessMap
        Map<String, Map<String, Object>> fileProcessMap =
                (Map<String, Map<String, Object>>) execution.getVariable("fileProcessMap");

        if (fileProcessMap == null || !fileProcessMap.containsKey(filename)) {
            throw new BpmnError("MISSING_OCR_RESULT", "No fileProcessMap entry for: " + filename);
        }

        Map<String, Object> fileResults = fileProcessMap.get(filename);

        if (!fileResults.containsKey("openaiVisionOutput")) {
            throw new BpmnError("MISSING_OCR_RESULT", "No openaiVision output for: " + filename);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> ocrOutput = (Map<String, Object>) fileResults.get("openaiVisionOutput");

        String apiCall = (String) ocrOutput.get("apiCall");
        if (!"success".equals(apiCall)) {
            throw new BpmnError("OCR_FAILED", "OCR failed for: " + filename);
        }

        String minioPath = (String) ocrOutput.get("minioPath");

        log.info("Fetching openaiVision result from MinIO: {}", minioPath);

        // 2. Load workflow config
        Connection conn = execution.getProcessEngine()
                .getProcessEngineConfiguration()
                .getDataSource()
                .getConnection();

        JSONObject workflowConfig = ConfigurationService.loadWorkflowConfig("HealthClaim", tenantId, conn);
        conn.close();

        // 3. Fetch openaiVision output from MinIO
        Map<String, Object> result = AgentResultStorageService.retrieveAgentResult(tenantId, minioPath);
        String apiResponse = (String) result.get("apiResponse");

        JSONObject openaiVisionResult = new JSONObject(apiResponse);

        if (!openaiVisionResult.has("answer")) {
            throw new BpmnError("MISSING_ANSWER", "openaiVision result missing answer field");
        }

        JSONObject answer = openaiVisionResult.getJSONObject("answer");

        log.info("Retrieved openaiVision answer with doc_type: {}", answer.optString("doc_type", "unknown"));

        // 4. Build request for ocrToStatic
        JSONObject requestBody = new JSONObject();
        requestBody.put("data", answer);
        requestBody.put("agentid", "ocrTostatic");

        log.info("Calling ocrToStatic API with agentid: ocrTostatic");
        log.debug("Request body: {}", requestBody.toString());

        // 5. Call API
        APIServices apiServices = new APIServices(tenantId, workflowConfig);
        CloseableHttpResponse response = apiServices.callAgent(requestBody.toString());

        String resp = EntityUtils.toString(response.getEntity());
        int statusCode = response.getStatusLine().getStatusCode();

        log.info("OcrToStatic API status: {}", statusCode);
        log.debug("OcrToStatic API response: {}", resp);

        if (statusCode != 200) {
            throw new BpmnError("ocrToStaticFailed", "OcrToStatic agent failed with status: " + statusCode);
        }

        // 6. Store result in MinIO using NEW structure
        Map<String, Object> fullResult = AgentResultStorageService.buildResultMap(
                "ocrToStatic", statusCode, resp, new HashMap<>());

        String storedPath = AgentResultStorageService.storeAgentResult(
                tenantId, "HealthClaim", ticketId, "OcrToStatic", filename, fullResult);

        log.info("Stored ocrToStatic result at: {}", storedPath);

        // 7. Update fileProcessMap
        Map<String, Object> ocrToStaticResult = new HashMap<>();
        ocrToStaticResult.put("statusCode", statusCode);
        ocrToStaticResult.put("minioPath", storedPath);
        ocrToStaticResult.put("apiCall", "success");

        fileResults.put("ocrToStaticOutput", ocrToStaticResult);
        fileProcessMap.put(filename, fileResults);
        execution.setVariable("fileProcessMap", fileProcessMap);

        log.info("=== OcrToStatic Completed for file: {} ===", filename);
    }
}