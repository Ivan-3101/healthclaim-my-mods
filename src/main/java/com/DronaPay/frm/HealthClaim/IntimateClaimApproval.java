package com.DronaPay.frm.HealthClaim;

import com.DronaPay.frm.HealthClaim.generic.delegates.GenericEmailDelegate;
import lombok.extern.slf4j.Slf4j;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;

/**
 * Wrapper delegate for Claim Approval Email
 * Delegates to generic implementation
 */
@Slf4j
public class IntimateClaimApproval implements JavaDelegate {

    private final GenericEmailDelegate genericDelegate = new GenericEmailDelegate();

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.debug("IntimateClaimApproval called - delegating to generic implementation");

        // Set email type for generic delegate
        execution.setVariableLocal("emailType", "approval");
        execution.setVariableLocal("workflowKey", "HealthClaim");

        // Call the generic implementation
        genericDelegate.execute(execution);

        log.debug("IntimateClaimApproval completed via generic delegate");
    }
}