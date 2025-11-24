package com.DronaPay.frm.HealthClaim;

import com.DronaPay.frm.HealthClaim.generic.delegates.GenericEmailDelegate;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;

/**
 * Wrapper delegate for Rejection Email
 * Delegates to generic implementation
 */
@Slf4j
public class SendRejectionMail implements JavaDelegate {

    private final GenericEmailDelegate genericDelegate = new GenericEmailDelegate();

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.debug("SendRejectionMail called - delegating to generic implementation");

        // Set email type for generic delegate
        execution.setVariableLocal("emailType", "rejection");
        execution.setVariableLocal("workflowKey", "HealthClaim");

        // Call the generic implementation
        genericDelegate.execute(execution);

        log.debug("SendRejectionMail completed via generic delegate");
    }
}