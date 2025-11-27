package com.DronaPay.frm.HealthClaim;

import com.DronaPay.frm.HealthClaim.generic.delegates.GenericScoringAPIDelegate;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;

/**
 * Wrapper delegate for FWA Decisioning
 * Delegates to generic implementation
 */
@Slf4j
public class FWADecisioning implements JavaDelegate {

    private final GenericScoringAPIDelegate genericDelegate = new GenericScoringAPIDelegate();

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.debug("FWADecisioning called - delegating to generic implementation");

        // Set scoring type for generic delegate
        execution.setVariableLocal("scoringType", "fwaDecisioning");
        execution.setVariableLocal("workflowKey", "HealthClaim");

        // Call the generic implementation
        genericDelegate.execute(execution);

        log.debug("FWADecisioning completed via generic delegate");
    }
}