package com.DronaPay.frm.HealthClaim;

import com.DronaPay.frm.HealthClaim.generic.config.WorkflowStageMapping;
import com.DronaPay.frm.HealthClaim.generic.services.ConfigurationService;
import com.DronaPay.frm.HealthClaim.generic.services.ObjectStorageService;
import com.DronaPay.frm.HealthClaim.generic.storage.StorageProvider;
import com.DronaPay.frm.HealthClaim.generic.utils.StoragePathBuilder;
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
import java.util.Properties;

@Slf4j
public class OCROnDoc implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("=== OCR on Documents Started ===");

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
                stageNumber = 6; // Default OCR stage
            }
        }

        // Don't set stageNumber in execution - this is a multi-instance loop
        // Setting it would increment on each iteration
        log.info("Stage {}: {} - Processing file: {}", stageNumber, taskName, filename);

        @SuppressWarnings("unchecked")
        Map<String, String> documentPaths = (Map<String, String>) execution.getVariable("documentPaths");

        if (documentPaths == null || !documentPaths.containsKey(filename)) {
            throw new RuntimeException("Document path not found for: " + filename);
        }

        String storagePath = documentPaths.get(filename);

        // Extract doctype from filename
        String doctype = filename.substring(0, filename.lastIndexOf("."));

        // Load workflow config
        Connection conn = execution.getProcessEngine()
                .getProcessEngineConfiguration()
                .getDataSource()
                .getConnection();
        JSONObject workflowConfig = ConfigurationService.loadWorkflowConfig(workflowKey, tenantId, conn);
        conn.close();

        // Download file from storage
        StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);
        InputStream fileContent = storage.downloadDocument(storagePath);
        byte[] bytes = IOUtils.toByteArray(fileContent);
        String base64 = Base64.getEncoder().encodeToString(bytes);

        // Build request
        JSONObject data = new JSONObject();
        data.put("base64_img", base64);

        JSONObject requestBody = new JSONObject();
        requestBody.put("data", data);
        requestBody.put("agentid", "openaiVision");

        log.info("Calling openaiVision agent for {}", filename);

        // Call API
        JSONObject externalAPIs = workflowConfig.getJSONObject("externalAPIs");
        JSONObject agentAPI = externalAPIs.getJSONObject("agentAPI");
        String baseUrl = agentAPI.getString("baseUrl");
        String username = agentAPI.getString("username");
        String password = agentAPI.getString("password");

        URI apiUri = new URI(baseUrl + "/agent");

        CloseableHttpClient httpClient = HttpClients.createDefault();
        HttpPost httpPost = new HttpPost(apiUri);
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

        // Store result in NEW MinIO structure
        Properties props = ConfigurationService.getTenantProperties(tenantId);
        String rootFolder = props.getProperty("storage.minio.bucketName", "insurance-claims");

        JSONObject result = new JSONObject();
        result.put("agentId", "openaiVision");
        result.put("statusCode", statusCode);
        result.put("success", true);
        result.put("rawResponse", responseBody);
        result.put("extractedData", new HashMap<>());
        result.put("timestamp", System.currentTimeMillis());

        byte[] content = result.toString(2).getBytes(StandardCharsets.UTF_8);
        String outputFilename = doctype + ".json";

        String outputPath = StoragePathBuilder.buildTaskDocsPath(
                rootFolder, tenantId, workflowKey, ticketId,
                stageNumber, taskName, outputFilename
        );

        storage.uploadDocument(outputPath, content, "application/json");
        log.info("Saved OCR result to: {}", outputPath);

        // Update fileProcessMap
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> fileProcessMap =
                (Map<String, Map<String, Object>>) execution.getVariable("fileProcessMap");

        if (fileProcessMap == null) {
            fileProcessMap = new HashMap<>();
        }

        Map<String, Object> fileResults = fileProcessMap.getOrDefault(filename, new HashMap<>());

        Map<String, Object> agentResult = new HashMap<>();
        agentResult.put("statusCode", statusCode);
        agentResult.put("apiCall", "Success");
        agentResult.put("extractedData", new HashMap<>());
        agentResult.put("rawResponse", responseBody);

        fileResults.put("openaiVision", agentResult);
        fileProcessMap.put(filename, fileResults);
        execution.setVariable("fileProcessMap", fileProcessMap);

        log.info("=== OCR on Documents Completed for {} ===", filename);
    }
}