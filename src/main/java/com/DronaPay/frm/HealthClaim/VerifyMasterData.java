package com.DronaPay.frm.HealthClaim;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.util.EntityUtils;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.camunda.bpm.engine.variable.Variables;
import org.camunda.bpm.engine.variable.value.ObjectValue;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class VerifyMasterData implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("Verify Master data called for ticket id ", execution.getVariable("TicketID"));

        APIServices apiServices = new APIServices(execution.getTenantId());
        CloseableHttpResponse response = apiServices.verifyMasterData(execution.getVariable("policy_id").toString());
        String resp = EntityUtils.toString(response.getEntity());
        int statucode = response.getStatusLine().getStatusCode();
        log.info("Verify Master data status " + statucode);
        log.info("Verify Master data response " + resp);
        ObjectValue respJson = Variables
                .objectValue(resp)
                .serializationDataFormat("application/json")
                .create();
        execution.setVariable("verifyResponse", respJson);
        execution.setVariable("statusCode", statucode);
        if (statucode == 200) {
            execution.setVariable("policyFound", "yes");
        } else {
            execution.setVariable("policyFound", "no");
        }

    }

}
