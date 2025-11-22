package com.DronaPay.frm.HealthClaim;

import com.DronaPay.frm.HealthClaim.generic.delegates.GenericMasterDataVerificationDelegate;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;

/**
 * Wrapper delegate for Master Data Verification
 * Delegates to generic implementation
 */
@Slf4j
public class VerifyMasterData implements JavaDelegate {

    private final GenericMasterDataVerificationDelegate genericDelegate =
            new GenericMasterDataVerificationDelegate();

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.debug("VerifyMasterData called - delegating to generic implementation");

        // Call the generic implementation
        genericDelegate.execute(execution);

        log.debug("VerifyMasterData completed via generic delegate");
    }
}