package com.DronaPay.frm.HealthClaim;

import com.DronaPay.frm.HealthClaim.generic.delegates.GenericEmailDelegate;
import lombok.extern.slf4j.Slf4j;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;

/**
 * Wrapper delegate for Reminder Email to Policy Holder
 * Delegates to generic implementation
 */
@Slf4j
public class SendReminderToPolicyHolder implements JavaDelegate {

    private final GenericEmailDelegate genericDelegate = new GenericEmailDelegate();

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.debug("SendReminderToPolicyHolder called - delegating to generic implementation");

        // Set email type for generic delegate
        execution.setVariableLocal("emailType", "reminder");
        execution.setVariableLocal("workflowKey", "HealthClaim");

        // Call the generic implementation
        genericDelegate.execute(execution);

        log.debug("SendReminderToPolicyHolder completed via generic delegate");
    }
}