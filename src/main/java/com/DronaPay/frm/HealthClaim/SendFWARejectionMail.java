package com.DronaPay.frm.HealthClaim;

import com.DronaPay.frm.HealthClaim.generic.delegates.GenericEmailDelegate;
import lombok.extern.slf4j.Slf4j;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;

/**
 * Email delegate for FWA rejection
 */
@Slf4j
public class SendFWARejectionMail implements JavaDelegate {

    private final GenericEmailDelegate genericDelegate = new GenericEmailDelegate();

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.debug("SendFWARejectionMail called");

        execution.setVariableLocal("emailType", "fwaRejection");
        execution.setVariableLocal("workflowKey", "HealthClaim");

        genericDelegate.execute(execution);

        log.debug("SendFWARejectionMail completed");
    }
}