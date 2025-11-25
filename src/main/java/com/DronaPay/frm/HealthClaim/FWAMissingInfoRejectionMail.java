package com.DronaPay.frm.HealthClaim;

import com.DronaPay.frm.HealthClaim.generic.delegates.GenericEmailDelegate;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;

/**
 * Email delegate for FWA missing info rejection
 */
@Slf4j
public class FWAMissingInfoRejectionMail implements JavaDelegate {

    private final GenericEmailDelegate genericDelegate = new GenericEmailDelegate();

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.debug("FWAMissingInfoRejectionMail called");

        execution.setVariableLocal("emailType", "fwaMissingInfo");
        execution.setVariableLocal("workflowKey", "HealthClaim");

        genericDelegate.execute(execution);

        log.debug("FWAMissingInfoRejectionMail completed");
    }
}