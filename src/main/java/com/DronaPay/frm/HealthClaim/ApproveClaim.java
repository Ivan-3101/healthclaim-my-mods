package com.DronaPay.frm.HealthClaim;

import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ApproveClaim implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("Approve claim service called ticket id "+ execution.getVariable("TicketID"));

    }

}
