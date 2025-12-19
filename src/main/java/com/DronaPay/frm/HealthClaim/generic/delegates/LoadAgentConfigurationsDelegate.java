package com.DronaPay.frm.HealthClaim.generic.delegates;

import com.DronaPay.frm.HealthClaim.generic.services.ConfigurationService;
import lombok.extern.slf4j.Slf4j;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.json.JSONArray;
import org.json.JSONObject;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Load Agent Configurations Delegate
 *
 * Loads agent configurations from database and prepares them for multi-instance execution
 *
 * NO FALLBACK - All configuration must come from database
 *
 * Sets Process Variables:
 * - agentConfigurations: List<JSONObject> - List of enabled agent configs sorted by order
 * - agentCount: Integer - Number of agents to execute
 */
@Slf4j
public class LoadAgentConfigurationsDelegate implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("=== Load Agent Configurations Started ===");
        log.info("TicketID: {}", execution.getVariable("TicketID"));

        String tenantId = execution.getTenantId();
        String workflowKey = "HealthClaim"; // TODO: Make configurable

        // 1. Load workflow configuration from database
        Connection conn = execution.getProcessEngine()
                .getProcessEngineConfiguration()
                .getDataSource()
                .getConnection();

        JSONObject workflowConfig = ConfigurationService.loadWorkflowConfig(workflowKey, tenantId, conn);
        conn.close();

        // 2. Extract agents configuration - NO FALLBACK
        if (!workflowConfig.has("agents")) {
            throw new IllegalArgumentException(
                    "No 'agents' configuration found in database. " +
                            "Please add 'agents' array to ui.workflowmasters.filterparams"
            );
        }

        JSONArray agentsArray = workflowConfig.getJSONArray("agents");

        if (agentsArray.length() == 0) {
            log.warn("No agents configured in database");
            execution.setVariable("agentConfigurations", new ArrayList<>());
            execution.setVariable("agentCount", 0);
            return;
        }

        // 3. Filter enabled agents and sort by order
        List<JSONObject> enabledAgents = new ArrayList<>();

        for (int i = 0; i < agentsArray.length(); i++) {
            JSONObject agent = agentsArray.getJSONObject(i);
            boolean enabled = agent.optBoolean("enabled", true);

            if (enabled) {
                enabledAgents.add(agent);
            } else {
                log.info("Agent '{}' is disabled, skipping", agent.optString("displayName", "Unknown"));
            }
        }

        // Sort by order field
        enabledAgents.sort(Comparator.comparingInt(a -> a.optInt("order", 999)));

        // 4. Log agent execution plan
        log.info("Loaded {} enabled agents from database:", enabledAgents.size());
        for (JSONObject agent : enabledAgents) {
            log.info("  - Order {}: {} ({})",
                    agent.optInt("order", 999),
                    agent.optString("displayName", "Unknown"),
                    agent.getString("agentId")
            );
        }

        // 5. Set process variables for multi-instance loop
        execution.setVariable("agentConfigurations", enabledAgents);
        execution.setVariable("agentCount", enabledAgents.size());

        log.info("=== Load Agent Configurations Completed - {} agents ready ===", enabledAgents.size());
    }
}