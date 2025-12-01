package com.DronaPay.frm.HealthClaim;

import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;

import com.DronaPay.frm.HealthClaim.generic.services.ConfigurationService;
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

        String filename = (String) execution.getVariable("attachment");

        JSONObject requestBody = new JSONObject();
        JSONObject data = new JSONObject();

        String ocrText = (String) execution.getVariable("ocr_text");

        if (ocrText == null) {
            ocrText = "";
            log.warn("OCR text is null for file: " + filename);
        }

        log.debug("Converting OCR text to FHIR for document: {}, text length: {} characters", filename, ocrText.length());

        data.put("ocr_text", ocrText);

        requestBody.put("data", data);
        requestBody.put("agentid", "ocrToFhir");

        // Load workflow config
        Connection conn = execution.getProcessEngine()
                .getProcessEngineConfiguration()
                .getDataSource()
                .getConnection();

        JSONObject workflowConfig = ConfigurationService.loadWorkflowConfig("HealthClaim", execution.getTenantId(), conn);
        conn.close();

        APIServices apiServices = new APIServices(execution.getTenantId(), workflowConfig);
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

            Object answerObj = resObj.get("answer");
            String fhirJsonString;

            if (answerObj instanceof JSONObject) {
                fhirJsonString = ((JSONObject) answerObj).toString();
            } else {
                fhirJsonString = answerObj.toString();
            }

            execution.setVariable("fhir_json", fhirJsonString);

            log.info("FHIR conversion successful for document: {}", filename);
            log.debug("FHIR JSON set in process variable 'fhir_json'");

        } else {
            ocrToFHIROutput.put("ocrToFHIRAPICall", "failed");
            log.error("FHIR conversion failed for document: {} with status: {}", filename, statucode);
        }

        Map<String, Map<String, Object>> fileProcessMap = (Map<String, Map<String, Object>>) execution
                .getVariable("fileProcessMap");

        if (fileProcessMap == null) {
            fileProcessMap = new HashMap<>();
        }

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

        log.info("OCR to FHIR processing completed for document: {}", filename);
    }
}