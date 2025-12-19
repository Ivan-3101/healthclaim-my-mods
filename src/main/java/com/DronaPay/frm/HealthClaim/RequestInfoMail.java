package com.DronaPay.frm.HealthClaim;

import com.DronaPay.frm.HealthClaim.generic.delegates.GenericEmailDelegate;
import lombok.extern.slf4j.Slf4j;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.cibseven.bpm.engine.RuntimeService;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * Wrapper delegate for Request Info Email
 * Delegates to generic implementation
 *
 * Note: This delegate also includes async callback logic
 */
@Slf4j
public class RequestInfoMail implements JavaDelegate {

    private final GenericEmailDelegate genericDelegate = new GenericEmailDelegate();

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.debug("RequestInfoMail called - delegating to generic implementation");

        // Set email type for generic delegate
        execution.setVariableLocal("emailType", "requestInfo");
        execution.setVariableLocal("workflowKey", "HealthClaim");

        // Call the generic implementation
        genericDelegate.execute(execution);

        log.debug("RequestInfoMail completed via generic delegate");

        // ===== ASYNC CALLBACK LOGIC (ORIGINAL BEHAVIOR) =====
        // This logic triggers a message correlation after 60 seconds if dummy stub is enabled
        Connection conn = execution.getProcessEngine().getProcessEngineConfiguration().getDataSource()
                .getConnection();
        RuntimeService runtimeService = execution.getProcessEngineServices().getRuntimeService();

        new Thread(() -> {
            try {
                Thread.sleep(60 * 1000); // Wait 60 seconds

                PreparedStatement selectStatement = conn.prepareStatement(
                        "SELECT activeflag FROM ui.templateresponse WHERE templatename = 'HealthClaim';"
                );

                ResultSet rs = selectStatement.executeQuery();
                String enabled = "N";
                if (rs.next()) {
                    enabled = rs.getString("activeflag");
                }

                conn.close();

                if (enabled.equals("Y")) {
                    // Trigger callback message
                    runtimeService.createMessageCorrelation("policyHolderResponse")
                            .processInstanceBusinessKey(execution.getBusinessKey())
                            .correlate();
                }
            } catch (Exception e) {
                log.error("Error in async callback logic", e);
            }
        }).start();
    }
}