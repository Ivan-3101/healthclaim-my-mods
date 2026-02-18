package com.DronaPay.frm.HealthClaim;

import java.time.Instant;
import java.util.Properties;
import java.util.Random;
import java.util.UUID;

import com.DronaPay.generic.utils.TenantPropertiesUtil;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.cibseven.bpm.engine.delegate.BpmnError;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.json.JSONObject;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class FWADecisioning implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("FWA decisioning service called " + execution.getVariable("TicketID"));

        // 1. Prepare Request Body
        String json = "{\n    \"reqid\": \"{{$guid}}\",\n    \"org\": \"SIT\",\n    \"ts\": \"{{$isoTimestamp}}\",\n    \"txn\": {\n        \"ts\": \"{{$isoTimestamp}}\",\n        \"id\": \"{{$randomCountryCode}}{{$timestamp}}\",\n        \"org_txn_id\": \"\",\n        \"note\": \"\",\n        \"type\": \"PAY\",\n        \"class\": \"health_claim\",\n        \"attribs\": {\n            \n                \"patient_name\": \"Ravi Kumar\",\n                \"gender\": \"Male\",\n                \"doctor_id\": \"DR1024\",\n                \"doctor_name\": \"Dr. Anjali Mehta\",\n                \"disease_name\": \"Type 2 Diabetes\",\n               \"procedure\": \"Insulin Therapy\",\n                \"start_date\": \"2025-07-15\",\n                \"medication_name\": \"Metformin 500mg\",\n                \"instruction_for_usage\": \"Take one tablet twice daily after meals\",\n                \"patient_contact\": \"+91-9876543210\",\n                \"insurance_company\": \"Star Health Insurance\",\n                \"time_period\": \"2025-07-15 to 2025-08-05\",\n                \"hospital_name\": \"Apollo Hospital, Delhi\",\n                \"claim_for\": \"Treatment of Type 2 Diabetes\",\n                \"claim_amount\": 15000\n            \n        }\n    },\n    \"payer\": {\n        \"addr\": \"helliiooiiooohooo@1\",\n        \"mcc\": 0,\n        \"type\": \"PERSON\",\n        \"amount\": 500000,\n        \"currency\": \"INR\",\n        \"attribs\": {\n            \"mid\": \"EPIFI101\"\n        }\n    },\n    \"payee\": {\n        \"addr\": \"b5ZbTaCQzpgmlrtCB8DI_cZQPfRVNsC1C1XrzVfGgzWUiHbF6cCKO6-lyYFQ09Vf\",\n        \"type\": \"PERSON\",\n        \"amount\": 500000,\n        \"currency\": \"INR\",\n        \"attribs\": {}\n    }\n}\n";
        JSONObject reqBody = new JSONObject(json);
        reqBody.put("reqid", UUID.randomUUID());
        String timeStamp = Instant.now().toString();
        reqBody.put("ts", timeStamp);
        JSONObject txnObject = reqBody.getJSONObject("txn");
        txnObject.put("ts", timeStamp);
        Random random = new Random();
        String id = (char) ('A' + random.nextInt(26)) + Long.toString(Instant.now().toEpochMilli());
        txnObject.put("id", id);

        JSONObject attribs = txnObject.getJSONObject("attribs");
        attribs.put("patient_name", execution.getVariable("patientName"));
        attribs.put("gender", execution.getVariable("gender"));
        attribs.put("doctor_id", execution.getVariable("doctorIdentifier"));
        attribs.put("doctor_name", execution.getVariable("doctorName"));
        attribs.put("disease_name", execution.getVariable("diseaseName"));
        attribs.put("procedure", execution.getVariable("procedure"));
        attribs.put("start_date", execution.getVariable("startDate"));
        attribs.put("medication_name", execution.getVariable("medicationName"));
        attribs.put("instruction_for_usage", execution.getVariable("dosageInstruction"));
        attribs.put("patient_contact", execution.getVariable("contact"));
        attribs.put("insurance_company", execution.getVariable("insuranceCompany"));
        attribs.put("time_period", execution.getVariable("period"));
        attribs.put("hospital_name", execution.getVariable("hospitalName"));
        attribs.put("claim_for", execution.getVariable("claimFor"));
        attribs.put("claim_amount", execution.getVariable("claimAmount"));

        txnObject.put("attribs", attribs);
        reqBody.put("txn", txnObject);

        // 2. Direct API Call (No APIServices)
        String tenantId = execution.getTenantId();
        Properties props = TenantPropertiesUtil.getTenantProps(tenantId);
        String springApiUrl = props.getProperty("springapi.url");
        String springApiKey = props.getProperty("springapi.api.key");

        CloseableHttpResponse response = executePost(springApiUrl + "/score", springApiKey, reqBody.toString());
        String resp = EntityUtils.toString(response.getEntity());
        int statusCode = response.getStatusLine().getStatusCode();

        log.info("FWA Decision API status " + statusCode);
        log.info("FWA Decision API response " + resp);

        execution.setVariable("RiskScore", 100L); // Default fallback as Long
        execution.setVariable("fwaResponse", resp);

        if (statusCode == 200) {
            JSONObject resObj = new JSONObject(resp);
            // --- FIX START: Cast Double (70.0) to Long (70) ---
            Object scoreObj = resObj.optQuery("/score/score");
            if (scoreObj instanceof Number) {
                // This converts 70.0 -> 70, preventing the NumberFormatException in the UI
                execution.setVariable("RiskScore", ((Number) scoreObj).longValue());
            }
            // --- FIX END ---
        } else {
            throw new BpmnError("failedFWA");
        }
    }

    private CloseableHttpResponse executePost(String url, String apiKey, String body) throws Exception {
        CloseableHttpClient client = HttpClients.createDefault();
        HttpPost httpPost = new HttpPost(url);
        httpPost.addHeader("Content-Type", "application/json");
        httpPost.addHeader("X-API-Key", apiKey);
        httpPost.setEntity(new StringEntity(body));
        return client.execute(httpPost);
    }
}