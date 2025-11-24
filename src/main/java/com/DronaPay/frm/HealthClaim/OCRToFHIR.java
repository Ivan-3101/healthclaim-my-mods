package com.DronaPay.frm.HealthClaim;

import java.util.HashMap;
import java.util.Map;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.util.EntityUtils;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.json.JSONObject;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OCRToFHIR implements JavaDelegate {
    @SuppressWarnings("unchecked")
    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("OCR To FHIR service called by ticket id " + execution.getVariable("TicketID"));

        // --- FIX: Retrieve filename string directly, NOT FileValue ---
        // In MinIO architecture, 'attachment' variable holds the filename string, not a reference to a FileValue object
        String filename = (String) execution.getVariable("attachment");
        // -------------------------------------------------------------

        JSONObject requestBody = new JSONObject();
        JSONObject data = new JSONObject();

        // We read "ocr_text" as a simple string to match how OCROnDoc.java saves it.
        String ocrText = (String) execution.getVariable("ocr_text");

        // Safety check in case OCR failed or text is null
        if (ocrText == null) {
            ocrText = "";
            log.warn("OCR text is null for file: " + filename);
        }

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

            // Handle cases where 'answer' might be a string or object depending on Agent response
            Object answerObj = resObj.get("answer");
            String fhirJsonString;

            if (answerObj instanceof JSONObject) {
                fhirJsonString = ((JSONObject) answerObj).toString();
            } else {
                fhirJsonString = answerObj.toString();
            }

            execution.setVariable("fhir_json", fhirJsonString);

            log.info(">>>> OCRtoFHIR Process Variable 'fhir_json' SET TO: " + fhirJsonString);

        } else {
            ocrToFHIROutput.put("ocrToFHIRAPICall", "failed");
        }

        Map<String, Map<String, Object>> fileProcessMap = (Map<String, Map<String, Object>>) execution
                .getVariable("fileProcessMap");

        // Safety check
        if (fileProcessMap == null) {
            fileProcessMap = new HashMap<>();
        }

        // --- FIX: Use 'filename' string variable instead of 'fileValue.getFilename()' ---
        if (fileProcessMap.containsKey(filename)) {
            Map<String, Object> proccessOutput = fileProcessMap.get(filename);
            proccessOutput.put("ocrToFHIROutput", ocrToFHIROutput);
            fileProcessMap.put(filename, proccessOutput);
        } else {
            Map<String, Object> proccessOutput = new HashMap<>();
            proccessOutput.put("ocrToFHIROutput", ocrToFHIROutput);
            fileProcessMap.put(filename, proccessOutput);
        }

        execution.setVariable("fileProcessMap", fileProcessMap);
    }
}