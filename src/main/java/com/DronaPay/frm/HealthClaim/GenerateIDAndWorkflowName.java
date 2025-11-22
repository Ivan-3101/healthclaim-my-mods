package com.DronaPay.frm.HealthClaim;

import com.DronaPay.frm.HealthClaim.generic.delegates.GenericIDGeneratorDelegate;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;

/**
 * Wrapper delegate for Generate ID functionality
 * Delegates to generic implementation
 */
@Slf4j
public class GenerateIDAndWorkflowName implements JavaDelegate {

    private final GenericIDGeneratorDelegate genericDelegate = new GenericIDGeneratorDelegate();

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.debug("GenerateIDAndWorkflowName called - delegating to generic implementation");

        // Call the generic implementation
        genericDelegate.execute(execution);

        log.debug("GenerateIDAndWorkflowName completed via generic delegate");
    }
}