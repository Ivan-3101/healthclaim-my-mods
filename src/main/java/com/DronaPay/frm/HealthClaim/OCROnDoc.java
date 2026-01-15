package com.DronaPay.frm.HealthClaim;

import com.DronaPay.frm.HealthClaim.generic.services.ConfigurationService;
import com.DronaPay.frm.HealthClaim.generic.services.ObjectStorageService;
import com.DronaPay.frm.HealthClaim.generic.storage.StorageProvider;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class OCROnDoc implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("=== OCR on Documents Started ===");

        String filename = (String) execution.getVariable("attachment");
        String ticketId = String.valueOf(execution.getVariable("TicketID"));
        String tenantId = execution.getTenantId();

        // CHANGE: Use BPMN Activity ID
        String stageName = execution.getCurrentActivityId();

        @SuppressWarnings("unchecked")
        Map<String, String> documentPaths = (Map<String, String>) execution.getVariable("documentPaths");

        if (documentPaths == null || !documentPaths.containsKey(filename)) {
            throw new RuntimeException("Document path not found for: " + filename);
        }

        String storagePath = documentPaths.get(filename);
        log.info("Processing file: {} from path: {}", filename, storagePath);

        String doctype = filename.substring(0, filename.lastIndexOf("."));
        log.info("Extracted doctype: {}", doctype);

        StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);
        byte[] pdfBytes;
        try (InputStream fileContent = storage.downloadDocument(storagePath)) {
            pdfBytes = IOUtils.toByteArray(fileContent);
        }

        String base64Content = Base64.getEncoder().encodeToString(pdfBytes);
        log.info("Converted document to base64 ({} bytes)", pdfBytes.length);

        Connection conn = execution.getProcessEngine()
                .getProcessEngineConfiguration()
                .getDataSource()
                .getConnection();

        JSONObject workflowConfig = ConfigurationService.loadWorkflowConfig("HealthClaim", tenantId, conn);
        conn.close();

        if (!workflowConfig.has("externalAPIs")) {
            throw new RuntimeException("externalAPIs not configured in database for tenant: " + tenantId);
        }

        JSONObject externalAPIs = workflowConfig.getJSONObject("externalAPIs");
        if (!externalAPIs.has("agentAPI")) {
            throw new RuntimeException("agentAPI not configured in externalAPIs for tenant: " + tenantId);
        }

        JSONObject agentAPI = externalAPIs.getJSONObject("agentAPI");
        String agentApiUrl = agentAPI.getString("baseUrl");
        String username = agentAPI.getString("username");
        String password = agentAPI.getString("password");

        log.info("Using agent API URL: {}", agentApiUrl);

        JSONObject requestBody = new JSONObject();
        JSONObject data = new JSONObject();
        data.put("base64_img", base64Content);
        data.put("doc_type", doctype);
        requestBody.put("data", data);
        requestBody.put("agentid", "openaiVision");

        log.info("Calling openaiVision agent for doc_type: {}", doctype);

        CloseableHttpClient httpClient = HttpClients.createDefault();
        HttpPost httpPost = new HttpPost(new URI(agentApiUrl + "/agent"));
        httpPost.setHeader("Content-Type", "application/json");

        String auth = username + ":" + password;
        String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
        httpPost.setHeader("Authorization", "Basic " + encodedAuth);

        httpPost.setEntity(new StringEntity(requestBody.toString(), StandardCharsets.UTF_8));

        CloseableHttpResponse response = httpClient.execute(httpPost);
        int statusCode = response.getStatusLine().getStatusCode();
        String responseBody = EntityUtils.toString(response.getEntity());

        response.close();
        httpClient.close();

        if (statusCode != 200) {
            throw new RuntimeException("Agent call failed with status: " + statusCode + ", body: " + responseBody);
        }

        log.info("Received response from openaiVision agent (status: {})", statusCode);

        // CHANGE: Use stageName in path
        String outputPath = tenantId + "/HealthClaim/" + ticketId + "/" + stageName + "/" + doctype + ".json";
        byte[] jsonBytes = responseBody.getBytes(StandardCharsets.UTF_8);
        storage.uploadDocument(outputPath, jsonBytes, "application/json");

        // CHANGE: Store output path in variable for next step to find
        Map<String, String> ocrRawResults = (Map<String, String>) execution.getVariable("ocrRawResults");
        if (ocrRawResults == null) {
            ocrRawResults = new HashMap<>();
        }
        ocrRawResults.put(filename, outputPath);
        execution.setVariable("ocrRawResults", ocrRawResults);

        log.info("Saved OCR result to: {}", outputPath);
        log.info("=== OCR on Documents Completed for {} ===", filename);
    }
}