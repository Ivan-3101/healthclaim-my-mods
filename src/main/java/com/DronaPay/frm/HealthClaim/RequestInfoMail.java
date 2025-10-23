package com.DronaPay.frm.HealthClaim;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.json.JSONArray;
import org.json.JSONObject;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RequestInfoMail implements JavaDelegate {
    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("Request info mail service called by ticket id ", execution.getVariable("TicketID"));

        JSONObject reqBody = new JSONObject();
        reqBody.put("itenantId", 0);
        reqBody.put("templateid", 1);
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

        Connection conn = execution.getProcessEngine().getProcessEngineConfiguration().getDataSource()
                .getConnection();
        RuntimeService runtimeService = execution.getProcessEngineServices().getRuntimeService();

        new Thread(() -> {
            try {
                Thread.sleep(60 * 1000);

                PreparedStatement selectStatement = conn
                        .prepareStatement(
                                "Select activeflag from ui.templateresponse where templatename = 'HealthClaim' ;");

                ResultSet rs = selectStatement.executeQuery();
                String enabled = "N";
                if (rs.next())
                    enabled = rs.getString("activeflag");

                conn.close();

                if (enabled.equals("Y")) {

                    runtimeService.createMessageCorrelation("policyHolderResponse")
                            .processInstanceBusinessKey(execution.getBusinessKey())
                            .correlate();
                }
            } catch (Exception e) {
                log.error("error in dummy stub ", e);
            }

        }).start();

    }

}
