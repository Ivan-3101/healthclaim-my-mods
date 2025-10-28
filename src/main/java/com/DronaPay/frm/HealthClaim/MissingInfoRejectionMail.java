package com.DronaPay.frm.HealthClaim;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.json.JSONArray;
import org.json.JSONObject;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MissingInfoRejectionMail implements JavaDelegate {
    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("Missing info email service called by ticket id "+execution.getVariable("TicketID"));

        JSONObject reqBody = new JSONObject();

        // Tenant-aware template ID
        String tenantId = execution.getTenantId();
//        int templateId = "1".equals(tenantId) ? 8 : 3;
//        Change id from from 5...10 to 11...15 --> as it had already had other templates in this id
        int templateId = "1".equals(tenantId) ? 13 : 3;


        reqBody.put("itenantId", Integer.parseInt(tenantId));
        reqBody.put("templateid", templateId);

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

        log.info("Sending missing info rejection email with templateId: " + templateId + " for tenantId: " + tenantId);

        APIServices apiServices = new APIServices(execution.getTenantId());
        apiServices.sendEmailViaUiserver(reqBody, execution.getTenantId());
    }
}