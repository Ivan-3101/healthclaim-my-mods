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

        log.info("=== OcrToStatic Started for file: {} ===", filename);

        // 1. Construct MinIO path where OCROnDoc stores results
        // CHANGED: Read from "6_Doing_OCR_on_Documents" instead of "ocr"
        String filenameWithoutExt = filename.replace(".pdf", "");
        String minioPath = String.format("%s/HealthClaim/%s/6_Doing_OCR_on_Documents/%s.json",
                tenantId, ticketId, filenameWithoutExt);

        log.info("Fetching openaiVision result from MinIO: {}", minioPath);

        // 2. Load workflow config
        Connection conn = execution.getProcessEngine()
                .getProcessEngineConfiguration()
                .getDataSource()
                .getConnection();

        JSONObject workflowConfig = ConfigurationService.loadWorkflowConfig("HealthClaim", tenantId, conn);
        conn.close();

        // 3. Fetch openaiVision output from MinIO
        StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);
        InputStream resultStream = storage.downloadDocument(minioPath);
        String resultJson = IOUtils.toString(resultStream, "UTF-8");

        JSONObject openaiVisionResult = new JSONObject(resultJson);

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

        // 6. Store result in MinIO
        Map<String, Object> fullResult = AgentResultStorageService.buildResultMap(
                "ocrToStatic", statusCode, resp, new HashMap<>());

        // CHANGED: Use storeAgentResultInStage with "7_OCR_to_Static"
        String storedPath = AgentResultStorageService.storeAgentResultInStage(
                tenantId, ticketId, filename, "7_OCR_to_Static", fullResult);

        log.info("Stored ocrToStatic result at: {}", storedPath);

        log.info("=== OcrToStatic Completed for file: {} ===", filename);
    }
}