package com.DronaPay.frm.HealthClaim.generic.delegates;

import com.DronaPay.frm.HealthClaim.generic.services.ConfigurationService;
import lombok.extern.slf4j.Slf4j;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.Expression;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.json.JSONArray;
import org.json.JSONObject;

import java.sql.Connection;

/**
 * Generic Single Agent Delegate
 * Used by the "Generic Agent Task" Element Template.
 * Fetches specific agent configuration from DB based on 'agentId' and delegates execution.
 */
@Slf4j
public class GenericSingleAgentDelegate implements JavaDelegate {

    // Field Injection: Only agentId comes from the Template for now
    private Expression agentId;

    private final GenericAgentExecutorDelegate genericExecutor = new GenericAgentExecutorDelegate();

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        // 1. Resolve Agent ID (Required from Template)
        String targetAgentId = (agentId != null) ? (String) agentId.getValue(execution) : null;

        if (targetAgentId == null || targetAgentId.trim().isEmpty()) {
            throw new RuntimeException("GenericSingleAgentDelegate: 'agentId' is missing. Please configure it in the BPMN.");
        }

        // 2. Resolve Workflow Key (Strictly from Process Variable) <-- Obtained in the first generic id stage through the template
        String targetWorkflowKey = (String) execution.getVariable("workflowKey");

        // NO FALLBACKS allowed. If it's missing, we must fail.
        if (targetWorkflowKey == null || targetWorkflowKey.trim().isEmpty()) {
            throw new RuntimeException("GenericSingleAgentDelegate: Process variable 'workflowKey' is missing. Ensure GenericIDGenerator has run first.");
        }

        log.debug("GenericSingleAgentDelegate running Agent: '{}' for Workflow: '{}'", targetAgentId, targetWorkflowKey);

        // 3. Fetch Master Configuration from DB
        Connection conn = execution.getProcessEngine()
                .getProcessEngineConfiguration()
                .getDataSource()
                .getConnection();

        JSONObject workflowConfig = ConfigurationService.loadWorkflowConfig(targetWorkflowKey, execution.getTenantId(), conn);
        conn.close();

        // 4. Extract Specific Agent Configuration
        JSONObject selectedConfig = null;
        if (workflowConfig.has("agents")) {
            JSONArray agents = workflowConfig.getJSONArray("agents");
            for (int i = 0; i < agents.length(); i++) {
                JSONObject agent = agents.getJSONObject(i);
                if (targetAgentId.equals(agent.optString("agentId"))) {
                    selectedConfig = agent;
                    break;
                }
            }
        }

        if (selectedConfig == null) {
            throw new RuntimeException("Configuration for agent '" + targetAgentId + "' not found in database for workflow '" + targetWorkflowKey + "'.");
        }

        // 5. Pass configuration to the Executor
        execution.setVariable("currentAgentConfig", selectedConfig);

        // 6. Execute
        genericExecutor.execute(execution);
    }
}