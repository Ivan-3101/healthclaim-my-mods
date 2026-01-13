package com.DronaPay.frm.HealthClaim;

import com.DronaPay.frm.HealthClaim.generic.services.AgentResultStorageService;
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
import org.cibseven.bpm.engine.delegate.BpmnError;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class FHIRConsolidatorDelegate implements JavaDelegate {

    @Override
    @SuppressWarnings("unchecked")
    public void execute(DelegateExecution execution) throws Exception {
        log.info("=== FHIR Consolidator Started ===");

        String ticketId = String.valueOf(execution.getVariable("TicketID"));
        String tenantId = execution.getTenantId();

        log.info("TicketID: {}, TenantID: {}", ticketId, tenantId);

        // 1. Get List of Documents
        Map<String, String> documentPaths = (Map<String, String>) execution.getVariable("documentPaths");
        if (documentPaths == null || documentPaths.isEmpty()) {
            throw new BpmnError("NO_DOCUMENTS", "No documents found for FHIR consolidation");
        }

        log.info("Processing {} documents for FHIR consolidation", documentPaths.size());

        List<JSONObject> fhirBundles = new ArrayList<>();
        StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);

        // 2. Fetch OCRtoStatic Result for EACH document
        for (String filename : documentPaths.keySet()) {
            log.debug("Processing file: {}", filename);

            // Read from "7_OCR_to_Static"
            String ocrStaticPath = String.format("%s/HealthClaim/%s/7_OCR_to_Static/%s.json",
                    tenantId, ticketId, filename);

            try {
                log.info("Downloading document from MinIO: {}", ocrStaticPath);
                InputStream stream = storage.downloadDocument(ocrStaticPath);
                String jsonContent = IOUtils.toString(stream, StandardCharsets.UTF_8);

                // Parse the MinIO file content (Wrapper JSON)
                JSONObject storedResult = new JSONObject(jsonContent);
                JSONObject answerObj = null;

                // Case 1: Check 'rawResponse' (matches your file structure)
                if (storedResult.has("rawResponse")) {
                    String rawRespStr = storedResult.getString("rawResponse");
                    try {
                        JSONObject rawResp = new JSONObject(rawRespStr);
                        if (rawResp.has("answer")) {
                            answerObj = rawResp.getJSONObject("answer");
                        }
                    } catch (Exception e) {
                        log.warn("Failed to parse rawResponse string for file {}: {}", filename, e.getMessage());
                    }
                }

                // Case 2: Check 'apiResponse' (AgentResultStorageService standard)
                if (answerObj == null && storedResult.has("apiResponse")) {
                    Object apiResponseObj = storedResult.get("apiResponse");
                    if (apiResponseObj instanceof String) {
                        try {
                            JSONObject apiResp = new JSONObject((String) apiResponseObj);
                            if (apiResp.has("answer")) {
                                answerObj = apiResp.getJSONObject("answer");
                            }
                        } catch (Exception e) {
                            log.warn("Failed to parse apiResponse string for file {}: {}", filename, e.getMessage());
                        }
                    } else if (apiResponseObj instanceof JSONObject) {
                        JSONObject apiResp = (JSONObject) apiResponseObj;
                        if (apiResp.has("answer")) {
                            answerObj = apiResp.getJSONObject("answer");
                        }
                    }
                }

                if (answerObj != null) {
                    fhirBundles.add(answerObj);
                    log.debug("Successfully extracted FHIR bundle for {}", filename);
                } else {
                    log.warn("Could not find 'answer' object in result for file: {}. Content snippet: {}",
                            filename, jsonContent.substring(0, Math.min(jsonContent.length(), 200)));
                }

            } catch (Exception e) {
                log.error("Error processing ocrToStatic output for file: {}", filename, e);
            }
        }

        if (fhirBundles.isEmpty()) {
            log.warn("No FHIR bundles found to consolidate");
            // Graceful exit?
        }

        // 3. Prepare Request for Consolidator Agent
        JSONObject requestBody = new JSONObject();
        JSONObject data = new JSONObject();
        data.put("fhir_bundles", new JSONArray(fhirBundles));
        requestBody.put("data", data);
        requestBody.put("agentid", "fhirConsolidator");

        // 4. Call API
        Connection conn = execution.getProcessEngine().getProcessEngineConfiguration().getDataSource().getConnection();
        JSONObject workflowConfig = ConfigurationService.loadWorkflowConfig("HealthClaim", tenantId, conn);
        conn.close();

        APIServices apiServices = new APIServices(tenantId, workflowConfig);
        CloseableHttpResponse response = apiServices.callAgent(requestBody.toString());
        String resp = EntityUtils.toString(response.getEntity());
        int statusCode = response.getStatusLine().getStatusCode();

        if (statusCode != 200) {
            throw new BpmnError("fhirConsolidatorFailed", "Status: " + statusCode);
        }

        // 5. Store Result
        Map<String, Object> fullResult = AgentResultStorageService.buildResultMap(
                "fhirConsolidator", statusCode, resp, new HashMap<>());

        String storedPath = AgentResultStorageService.storeAgentResultInStage(
                tenantId, ticketId, "consolidated_fhir", "8_FHIR_Consolidator", fullResult);

        // 6. Set Process Variable (extracting answer for next step)
        JSONObject respJson = new JSONObject(resp);
        if (respJson.has("answer")) {
            execution.setVariable("consolidatedFhir", respJson.getJSONObject("answer").toString());
        }

        execution.setVariable("fhirConsolidatorMinioPath", storedPath);
        log.info("FHIR Consolidation completed. Stored at: {}", storedPath);
    }
}