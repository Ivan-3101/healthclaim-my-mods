package com.DronaPay.frm.HealthClaim;

import com.DronaPay.frm.HealthClaim.generic.delegates.GenericEmailDelegate;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;

/**
 * Email delegate for FWA reminder
 */
@Slf4j
public class SendFWAReminderMail implements JavaDelegate {

    private final GenericEmailDelegate genericDelegate = new GenericEmailDelegate();

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.debug("SendFWAReminderMail called - delegating to generic implementation");

        execution.setVariableLocal("emailType", "fwaReminder");
        execution.setVariableLocal("workflowKey", "HealthClaim");

        genericDelegate.execute(execution);

        log.debug("SendFWAReminderMail completed via generic delegate");
    }
}