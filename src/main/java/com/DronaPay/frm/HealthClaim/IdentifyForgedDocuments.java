package com.DronaPay.frm.HealthClaim;

import com.DronaPay.frm.HealthClaim.generic.delegates.GenericAgentExecutorDelegate;
import com.DronaPay.frm.HealthClaim.generic.services.ConfigurationService;
import lombok.extern.slf4j.Slf4j;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.json.JSONArray;
import org.json.JSONObject;

import java.sql.Connection;

@Slf4j
public class IdentifyForgedDocuments implements JavaDelegate {

    private final GenericAgentExecutorDelegate genericDelegate = new GenericAgentExecutorDelegate();

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.debug("IdentifyForgedDocuments wrapper called - fetching config from DB");

        String tenantId = execution.getTenantId();
        // Assuming workflow key matches the process key or is standard "HealthClaim"
        String workflowKey = "HealthClaim";

        // 1. Get DB Connection
        Connection conn = execution.getProcessEngine()
                .getProcessEngineConfiguration()
                .getDataSource()
                .getConnection();

        // 2. Load Workflow Config from Database
        JSONObject workflowConfig = ConfigurationService.loadWorkflowConfig(workflowKey, tenantId, conn);
        conn.close();

        // 3. Find the specific 'forgeryagent' configuration
        JSONObject agentConfig = null;
        if (workflowConfig.has("agents")) {
            JSONArray agents = workflowConfig.getJSONArray("agents");
            for (int i = 0; i < agents.length(); i++) {
                JSONObject agent = agents.getJSONObject(i);
                if ("forgeryagent".equals(agent.optString("agentId"))) {
                    agentConfig = agent;
                    break;
                }
            }
        }

        if (agentConfig == null) {
            throw new RuntimeException("Configuration for 'forgeryagent' not found in database for workflow: " + workflowKey);
        }

        // 4. Delegate execution to the generic handler
        execution.setVariable("currentAgentConfig", agentConfig);
        genericDelegate.execute(execution);

        log.debug("IdentifyForgedDocuments completed via generic delegate");
    }
}