package com.DronaPay.frm.HealthClaim;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.json.JSONArray;
import org.json.JSONObject;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SendRejectionMail implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("Send rejection mail service called by ticket id ",execution.getVariable("TicketID"));

         JSONObject reqBody = new JSONObject();
        reqBody.put("itenantId", 0);
        reqBody.put("templateid", 4);
        JSONArray toEmail = new JSONArray();
        toEmail.put(execution.getVariable("sender_email"));
        JSONArray ccEmail = new JSONArray();
        JSONArray bccEmail = new JSONArray();
        reqBody.put("toEmail", toEmail);
        reqBody.put("ccEmail", ccEmail);
        reqBody.put("bccEmail", bccEmail);

        JSONObject bodyParams = new JSONObject();
        bodyParams.put("name", execution.getVariable("holder_name"));
        bodyParams.put("policyId", execution.getVariable("policy_id"));

        reqBody.put("bodyParams", bodyParams);

        APIServices apiServices = new APIServices(execution.getTenantId());
        apiServices.sendEmailViaUiserver(reqBody, execution.getTenantId());
    }
    
}
