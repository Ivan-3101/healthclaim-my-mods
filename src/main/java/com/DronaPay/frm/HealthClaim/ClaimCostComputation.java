package com.DronaPay.frm.HealthClaim;

import com.DronaPay.frm.HealthClaim.generic.delegates.GenericScoringAPIDelegate;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;

/**
 * Wrapper delegate for Claim Cost Computation
 * Delegates to generic implementation
 */
@Slf4j
public class ClaimCostComputation implements JavaDelegate {

    private final GenericScoringAPIDelegate genericDelegate = new GenericScoringAPIDelegate();

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.debug("ClaimCostComputation called - delegating to generic implementation");

        // Set scoring type for generic delegate
        execution.setVariableLocal("scoringType", "claimCostComputation");
        execution.setVariableLocal("workflowKey", "HealthClaim");

        // Call the generic implementation
        genericDelegate.execute(execution);

        log.debug("ClaimCostComputation completed via generic delegate");
    }
}