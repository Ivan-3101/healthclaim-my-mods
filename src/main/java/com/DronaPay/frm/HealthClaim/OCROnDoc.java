package com.DronaPay.frm.HealthClaim;

import java.io.InputStream;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.io.IOUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.util.EntityUtils;
import org.camunda.bpm.engine.delegate.BpmnError;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.camunda.bpm.engine.variable.value.FileValue;
import org.json.JSONObject;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OCROnDoc implements JavaDelegate {

    @SuppressWarnings("unchecked")
    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("OCR On Doc service called by ticket id " + execution.getVariable("TicketID"));

        FileValue fileValue = (FileValue) execution.getVariableTyped(execution.getVariable("attachment").toString());

        InputStream fileContent = fileValue.getValue();

        byte[] bytes = IOUtils.toByteArray(fileContent);
        String base64 = Base64.getEncoder().encodeToString(bytes);

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
            JSONObject resObj=new JSONObject(resp);

            // --- THIS IS THE FIX ---
            // We now save the "answer" as a simple string, not a serialized object.
            String ocrText = resObj.getString("answer");
            execution.setVariable("ocr_text", ocrText);

        } else {
            ocrOnDocOutput.put("ocrOnDocAPICall", "failed");
        }

        Map<String, Map<String, Object>> fileProcessMap = (Map<String, Map<String, Object>>) execution
                .getVariable("fileProcessMap");
        if (fileProcessMap.containsKey(fileValue.getFilename())) {
            Map<String, Object> proccessOutput = fileProcessMap.get(fileValue.getFilename());
            proccessOutput.put("ocrOnDocOutput", ocrOnDocOutput);
            fileProcessMap.put(fileValue.getFilename(), proccessOutput);
        } else {
            Map<String, Object> proccessOutput = new HashMap<>();
            proccessOutput.put("ocrOnDocOutput", ocrOnDocOutput);
            fileProcessMap.put(fileValue.getFilename(), proccessOutput);
        }

        execution.setVariable("fileProcessMap", fileProcessMap);

        if (statucode != 200) {
            throw new BpmnError("failedOcr");
        }
    }
}