package com.DronaPay.frm.HealthClaim;

import com.DronaPay.frm.HealthClaim.generic.delegates.GenericEmailDelegate;
import lombok.extern.slf4j.Slf4j;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;

/**
 * Wrapper delegate for Missing Info Rejection Email
 * Delegates to generic implementation
 */
@Slf4j
public class MissingInfoRejectionMail implements JavaDelegate {

    private final GenericEmailDelegate genericDelegate = new GenericEmailDelegate();

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.debug("MissingInfoRejectionMail called - delegating to generic implementation");

        // Set email type for generic delegate
        execution.setVariableLocal("emailType", "missingInfo");
        execution.setVariableLocal("workflowKey", "HealthClaim");

        // Call the generic implementation
        genericDelegate.execute(execution);

        log.debug("MissingInfoRejectionMail completed via generic delegate");
    }
}