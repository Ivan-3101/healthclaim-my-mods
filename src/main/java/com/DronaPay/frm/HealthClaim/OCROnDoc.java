package com.DronaPay.frm.HealthClaim;

import java.io.InputStream;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import com.DronaPay.frm.HealthClaim.generic.services.ObjectStorageService;
import com.DronaPay.frm.HealthClaim.generic.storage.StorageProvider;
import org.apache.commons.io.IOUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.util.EntityUtils;
import org.camunda.bpm.engine.delegate.BpmnError;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.json.JSONObject;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OCROnDoc implements JavaDelegate {

    @SuppressWarnings("unchecked")
    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("OCR On Doc service called by ticket id " + execution.getVariable("TicketID"));

        // --- CHANGED: Retrieve from MinIO instead of FileValue ---
        String filename = (String) execution.getVariable("attachment");
        Map<String, String> documentPaths = (Map<String, String>) execution.getVariable("documentPaths");

        if (documentPaths == null || !documentPaths.containsKey(filename)) {
            throw new RuntimeException("Storage path not found for document: " + filename);
        }

        String storagePath = documentPaths.get(filename);
        String tenantId = execution.getTenantId();

        log.info("Downloading document from storage for OCR: {}", storagePath);

        // Download from Storage
        StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);
        InputStream fileContent = storage.downloadDocument(storagePath);
        // ---------------------------------------------------------

        byte[] bytes = IOUtils.toByteArray(fileContent);
        String base64 = Base64.getEncoder().encodeToString(bytes);

        log.debug("Document converted to base64 for OCR, size: {} bytes", bytes.length);

        JSONObject requestBody = new JSONObject();
        JSONObject data = new JSONObject();
        data.put("base64_img", base64);
        requestBody.put("data", data);
        requestBody.put("agentid", "openaiVision");

        APIServices apiServices = new APIServices(execution.getTenantId());
        CloseableHttpResponse response = apiServices.callAgent(requestBody.toString());

        String resp = EntityUtils.toString(response.getEntity());
        int statucode = response.getStatusLine().getStatusCode();
        log.info("OCR on Doc API status " + statucode);
        log.info("OCR on Doc API  response " + resp);

        Map<String, Object> ocrOnDocOutput = new HashMap<>();
        ocrOnDocOutput.put("ocrOnDocAPIResponse", resp);
        ocrOnDocOutput.put("ocrOnDocAPIStatusCode", statucode);

        if (statucode == 200) {
            ocrOnDocOutput.put("ocrOnDocAPICall", "success");
            JSONObject resObj = new JSONObject(resp);

            String ocrText = resObj.getString("answer");
            execution.setVariable("ocr_text", ocrText);

            log.info("OCR extraction successful for document: {}", filename);
            log.debug("OCR text length: {} characters", ocrText.length());

        } else {
            ocrOnDocOutput.put("ocrOnDocAPICall", "failed");
            log.error("OCR failed for document: {} with status: {}", filename, statucode);
        }

        Map<String, Map<String, Object>> fileProcessMap = (Map<String, Map<String, Object>>) execution
                .getVariable("fileProcessMap");

        if (fileProcessMap == null) {
            fileProcessMap = new HashMap<>();
        }

        if (fileProcessMap.containsKey(filename)) {
            Map<String, Object> proccessOutput = fileProcessMap.get(filename);
            proccessOutput.put("ocrOnDocOutput", ocrOnDocOutput);
            fileProcessMap.put(filename, proccessOutput);
        } else {
            Map<String, Object> proccessOutput = new HashMap<>();
            proccessOutput.put("ocrOnDocOutput", ocrOnDocOutput);
            fileProcessMap.put(filename, proccessOutput);
        }

        execution.setVariable("fileProcessMap", fileProcessMap);

        if (statucode != 200) {
            throw new BpmnError("failedOcr");
        }

        log.info("OCR processing completed for document: {}", filename);
    }
}