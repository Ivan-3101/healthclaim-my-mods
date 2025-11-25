package com.DronaPay.frm.HealthClaim;

import com.DronaPay.frm.HealthClaim.generic.delegates.GenericEmailDelegate;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.camunda.bpm.engine.RuntimeService;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * Email delegate for requesting additional FWA information
 */
@Slf4j
public class RequestFWAInfoMail implements JavaDelegate {

    private final GenericEmailDelegate genericDelegate = new GenericEmailDelegate();

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.debug("RequestFWAInfoMail called - delegating to generic implementation");

        // Set email type for generic delegate
        execution.setVariableLocal("emailType", "requestFWAInfo");
        execution.setVariableLocal("workflowKey", "HealthClaim");

        // Call the generic implementation
        genericDelegate.execute(execution);

        log.debug("RequestFWAInfoMail completed via generic delegate");

        // ===== ASYNC CALLBACK LOGIC (Optional - for testing) =====
        Connection conn = execution.getProcessEngine().getProcessEngineConfiguration()
                .getDataSource().getConnection();
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
                    runtimeService.createMessageCorrelation("fwaInfoResponse")
                            .processInstanceBusinessKey(execution.getBusinessKey())
                            .correlate();
                }
            } catch (Exception e) {
                log.error("Error in async callback logic", e);
            }
        }).start();
    }
}