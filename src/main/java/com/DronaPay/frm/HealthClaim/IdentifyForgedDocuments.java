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
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.json.JSONObject;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class IdentifyForgedDocuments implements JavaDelegate {

    @SuppressWarnings("unchecked")
    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("Identify Forged Documents called by ticket id " + execution.getVariable("TicketID"));

        // --- CHANGED: Retrieve from MinIO instead of FileValue ---
        String filename = (String) execution.getVariable("attachment");
        Map<String, String> documentPaths = (Map<String, String>) execution.getVariable("documentPaths");

        if (documentPaths == null || !documentPaths.containsKey(filename)) {
            throw new RuntimeException("Storage path not found for document: " + filename);
        }

        String storagePath = documentPaths.get(filename);
        String tenantId = execution.getTenantId();

        // Download from Storage
        StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);
        InputStream fileContent = storage.downloadDocument(storagePath);
        // ---------------------------------------------------------

        byte[] bytes = IOUtils.toByteArray(fileContent);
        String base64 = Base64.getEncoder().encodeToString(bytes);

        JSONObject requestBody = new JSONObject();
        JSONObject data = new JSONObject();
        data.put("base64_img", base64);
        requestBody.put("data", data);
        requestBody.put("agentid", "forgeryagent");

        APIServices apiServices = new APIServices(execution.getTenantId());
        CloseableHttpResponse response = apiServices.callAgent(requestBody.toString());

        String resp = EntityUtils.toString(response.getEntity());
        int statucode = response.getStatusLine().getStatusCode();
        log.info("Forgery Doc API status " + statucode);
        log.info("Forgery Doc API  response " + resp);

        Map<String, Object> forgeryOutput = new HashMap<>();

        forgeryOutput.put("forgeDocAPIResponse", resp);
        forgeryOutput.put("forgeDocAPIStatusCode", statucode);

        if (statucode == 200) {
            forgeryOutput.put("docForgedAPICall", "success");
            JSONObject forgeResp = new JSONObject(resp);
            if (forgeResp.getString("answer").equals("<Not Suspicious>")) {
                forgeryOutput.put("isDocForged", "no");
            } else {
                forgeryOutput.put("isDocForged", "yes");
            }

        } else {
            forgeryOutput.put("docForgedAPICall", "failed");
        }

        Map<String, Map<String, Object>> fileProcessMap = (Map<String, Map<String, Object>>) execution
                .getVariable("fileProcessMap");

        // Ensure map is initialized (just in case)
        if (fileProcessMap == null) {
            fileProcessMap = new HashMap<>();
        }

        if (fileProcessMap.containsKey(filename)) {
            Map<String, Object> proccessOutput = fileProcessMap.get(filename);
            proccessOutput.put("forgedDocCheckOutput", forgeryOutput);
            fileProcessMap.put(filename, proccessOutput);
        } else {
            Map<String, Object> proccessOutput = new HashMap<>();
            proccessOutput.put("forgedDocCheckOutput", forgeryOutput);
            fileProcessMap.put(filename, proccessOutput);
        }

        execution.setVariable("fileProcessMap", fileProcessMap);
    }
}