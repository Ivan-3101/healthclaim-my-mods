package com.DronaPay.frm.HealthClaim;

import java.util.HashMap;
import java.util.Map;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.util.EntityUtils;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.camunda.bpm.engine.variable.value.FileValue;
import org.json.JSONObject;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OCRToFHIR implements JavaDelegate {
    @SuppressWarnings("unchecked")
    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("OCR To FHIR service called by ticket id " + execution.getVariable("TicketID"));
        FileValue fileValue = (FileValue) execution.getVariableTyped(execution.getVariable("attachment").toString());

        JSONObject requestBody = new JSONObject();
        JSONObject data = new JSONObject();

        // --- THIS IS THE FIX ---
        // We now read "ocr_text" as a simple string to match how OCROnDoc.java saves it.
        String ocrText = (String) execution.getVariable("ocr_text");
        data.put("ocr_text", ocrText);

        requestBody.put("data", data);
        requestBody.put("agentid", "ocrToFhir");

        APIServices apiServices = new APIServices(execution.getTenantId());
        CloseableHttpResponse response = apiServices.callAgent(requestBody.toString());

        String resp = EntityUtils.toString(response.getEntity());
        int statucode = response.getStatusLine().getStatusCode();
        log.info("OCR To FHIR API status " + statucode);
        log.info("OCR To FHIR API response " + resp);
        Map<String, Object> ocrToFHIROutput = new HashMap<>();

        ocrToFHIROutput.put("ocrToFHIRAPIResponse", resp);
        ocrToFHIROutput.put("ocrToFHIRAPIStatusCode", statucode);

        if (statucode == 200) {
            ocrToFHIROutput.put("ocrToFHIRAPICall", "success");

            JSONObject resObj = new JSONObject(resp);

            JSONObject fhirJsonObject = resObj.getJSONObject("answer");
            String fhirJsonString = fhirJsonObject.toString();

            execution.setVariable("fhir_json", fhirJsonString);

            log.info(">>>> OCRtoFHIR Process Variable 'fhir_json' SET TO: " + fhirJsonString);

        } else {
            ocrToFHIROutput.put("ocrToFHIRAPICall", "failed");
        }

        Map<String, Map<String, Object>> fileProcessMap = (Map<String, Map<String, Object>>) execution
                .getVariable("fileProcessMap");
        if (fileProcessMap.containsKey(fileValue.getFilename())) {
            Map<String, Object> proccessOutput = fileProcessMap.get(fileValue.getFilename());
            proccessOutput.put("ocrToFHIROutput", ocrToFHIROutput);
            fileProcessMap.put(fileValue.getFilename(), proccessOutput);
        } else {
            Map<String, Object> proccessOutput = new HashMap<>();
            proccessOutput.put("ocrToFHIROutput", ocrToFHIROutput);
            fileProcessMap.put(fileValue.getFilename(), proccessOutput);
        }
    }
}