package com.DronaPay.frm.HealthClaim;

import java.time.Instant;
import java.util.Random;
import java.util.UUID;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.util.EntityUtils;
import org.camunda.bpm.engine.delegate.BpmnError;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.json.JSONObject;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ClaimCostComputation implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("Claim cost computation service called for ticket id " + execution.getVariable("TicketID"));

        String json = "{\n    \"reqid\": \"{{$guid}}\",\n    \"org\": \"EPIFI\",\n    \"ts\": \"{{$isoTimestamp}}\",\n    \"txn\": {\n        \"ts\": \"{{$isoTimestamp}}\",\n        \"id\": \"{{$randomCountryCode}}{{$timestamp}}\",\n        \"org_txn_id\": \"\",\n        \"note\": \"\",\n        \"type\": \"PAY\",\n        \"class\": \"health_claim_cost\",\n        \"attribs\": {\n            \n                \"patient_name\": \"Ravi Kumar\",\n                \"gender\": \"Male\",\n                \"doctor_id\": \"DR1024\",\n                \"doctor_name\": \"Dr. Anjali Mehta\",\n                \"disease_name\": \"Type 2 Diabetes\",\n                \"procedure\": \"Insulin Therapy\",\n                \"start_date\": \"2025-07-15\",\n                \"end_date\": \"2025-08-05\",\n                \"medication_name\": \"Metformin 500mg\",\n                \"instruction_for_usage\": \"Take one tablet twice daily after meals\",\n                \"patient_contact\": \"+91-9876543210\",\n                \"insurance_company\": \"Star Health Insurance\",\n               \"time_period\": \"2025-07-15 to 2025-08-05\",\n                \"hospital_name\": \"Apollo Hospital, Delhi\",\n                \"claim_for\": \"Treatment of Type 2 Diabetes\",\n                \"claim_amount\": 15000\n            \n        }\n    },\n    \"payer\": {\n        \"addr\": \"helliiooiiooohooo@1\",\n        \"mcc\": 0,\n        \"type\": \"PERSON\",\n        \"amount\": 500000,\n        \"currency\": \"INR\",\n        \"attribs\": {\n            \"mid\": \"EPIFI101\"\n        }\n    },\n    \"payee\": {\n        \"addr\": \"b5ZbTaCQzpgmlrtCB8DI_cZQPfRVNsC1C1XrzVfGgzWUiHbF6cCKO6-lyYFQ09Vf\",\n        \"type\": \"PERSON\",\n        \"amount\": 500000,\n        \"currency\": \"INR\",\n        \"attribs\": {}\n    }\n}\n";
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

        APIServices apiServices = new APIServices(execution.getTenantId());
        CloseableHttpResponse response = apiServices.claimCost(reqBody.toString());

        String resp = EntityUtils.toString(response.getEntity());
        int statucode = response.getStatusLine().getStatusCode();
        log.info("Claim Cost Computation API status " + statucode);
        log.info("Claim Cost Computation API  response " + resp);
        execution.setVariable("claimCostResp", resp);

        if (statucode == 200) {
            JSONObject resObj = new JSONObject(resp);

            // FIX: Convert approved_amount to Long
            if(!resObj.isNull("/score/decisiondetails/0/approved_amount")){
                Object approvedAmountObj = resObj.optQuery("/score/decisiondetails/0/approved_amount");
                if (approvedAmountObj != null) {
                    execution.setVariable("approvedAmount", convertToLong(approvedAmountObj));
                }
            }

            // FIX: Convert bonus to Long
            if(!resObj.isNull("/score/decisiondetails/0/bonus")){
                Object bonusObj = resObj.optQuery("/score/decisiondetails/0/bonus");
                if (bonusObj != null) {
                    execution.setVariable("bonusAmount", convertToLong(bonusObj));
                }
            }

            // FIX: Convert final_amount to Long
            if(!resObj.isNull("/score/decisiondetails/0/final_amount")){
                Object finalAmountObj = resObj.optQuery("/score/decisiondetails/0/final_amount");
                if (finalAmountObj != null) {
                    execution.setVariable("finalAmount", convertToLong(finalAmountObj));
                }
            }
        }else{
            throw new BpmnError("failedClaimCost");
        }
    }

    /**
     * Helper method to convert Object to Long (handles Double, Integer, String)
     */
    private Long convertToLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Double) {
            return ((Double) value).longValue();
        } else if (value instanceof Integer) {
            return ((Integer) value).longValue();
        } else if (value instanceof Long) {
            return (Long) value;
        } else {
            // Fallback: try to parse as double then convert
            try {
                return Math.round(Double.parseDouble(value.toString()));
            } catch (NumberFormatException e) {
                log.error("Failed to convert value to Long: " + value, e);
                return 0L;
            }
        }
    }

}