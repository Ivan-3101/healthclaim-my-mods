package com.DronaPay.frm.HealthClaim;

import com.DronaPay.frm.HealthClaim.generic.delegates.GenericAgentExecutorDelegate;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class PolicyComparator implements JavaDelegate {

    private final GenericAgentExecutorDelegate genericDelegate = new GenericAgentExecutorDelegate();

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.debug("PolicyComparator wrapper called - delegating to generic");

        // Build agent config for backward compatibility
        JSONObject agentConfig = new JSONObject();
        agentConfig.put("agentId", "policy_comp");
        agentConfig.put("displayName", "Policy Comparator");
        agentConfig.put("enabled", true);
        agentConfig.put("critical", false);

        JSONObject config = new JSONObject();

        // Input mapping - uses fhir_json process variable
        JSONObject inputMapping = new JSONObject();
        inputMapping.put("source", "processVariable");
        inputMapping.put("variableName", "fhir_json");
        config.put("inputMapping", inputMapping);

        // Output mapping - we'll extract fields manually after generic delegate runs
        JSONObject outputMapping = new JSONObject();
        JSONObject variablesToSet = new JSONObject();

        // Store raw answer for later processing
        JSONObject rawMapping = new JSONObject();
        rawMapping.put("jsonPath", "/answer");
        rawMapping.put("dataType", "json");
        variablesToSet.put("policyComparatorRawResponse", rawMapping);

        outputMapping.put("variablesToSet", variablesToSet);
        config.put("outputMapping", outputMapping);

        // Error handling
        JSONObject errorHandling = new JSONObject();
        errorHandling.put("onFailure", "logAndContinue");
        errorHandling.put("continueOnError", true);
        config.put("errorHandling", errorHandling);

        agentConfig.put("config", config);

        // Set as current agent config
        execution.setVariable("currentAgentConfig", agentConfig);

        // Execute via generic delegate - THIS WILL STORE IN MINIO
        genericDelegate.execute(execution);

        // Now extract policy comparison fields from the stored response
        extractPolicyComparisonFields(execution);

        log.debug("PolicyComparator completed via generic delegate with MinIO storage");
    }

    /**
     * Extract policy comparison fields from raw response
     * This maintains backward compatibility with existing form fields
     */
    private void extractPolicyComparisonFields(DelegateExecution execution) {
        try {
            Object rawResponseObj = execution.getVariable("policyComparatorRawResponse");

            if (rawResponseObj == null) {
                log.warn("No raw response from policy comparator");
                execution.setVariable("policyMissingInfo", "Error: No response from policy comparator");
                execution.setVariable("policyPotentialIssues", "Unable to perform policy comparison");
                execution.setVariable("policyComparatorStatus", "failed");
                return;
            }

            // Convert to JSONObject
            JSONObject answer;
            if (rawResponseObj instanceof String) {
                answer = new JSONObject((String) rawResponseObj);
            } else if (rawResponseObj instanceof JSONObject) {
                answer = (JSONObject) rawResponseObj;
            } else {
                log.error("Unexpected response type: {}", rawResponseObj.getClass());
                execution.setVariable("policyComparatorStatus", "error");
                return;
            }

            // Check if "List 2" exists
            if (!answer.has("List 2")) {
                log.warn("Policy comparator response missing 'List 2' field");
                execution.setVariable("policyMissingInfo", "None");
                execution.setVariable("policyPotentialIssues", "None");
                execution.setVariable("policyComparatorStatus", "success");
                return;
            }

            JSONArray list2Array = answer.getJSONArray("List 2");

            List<String> potentialIssues = new ArrayList<>();
            List<String> missingInfoItems = new ArrayList<>();

            for (int i = 0; i < list2Array.length(); i++) {
                JSONObject item = list2Array.getJSONObject(i);
                String status = item.optString("Status/Issue", "");
                String area = item.optString("Question/Area", "Unknown Area");

                if ("Missing information".equalsIgnoreCase(status)) {
                    missingInfoItems.add(area);
                } else if (!"Match".equalsIgnoreCase(status) && !status.isEmpty()) {
                    potentialIssues.add(area + ": " + status);
                }
            }

            // Set process variables for user task form
            String missingInfo = missingInfoItems.isEmpty() ? "None" : String.join(", ", missingInfoItems);
            String issues = potentialIssues.isEmpty() ? "None" : String.join("\n", potentialIssues);

            execution.setVariable("policyMissingInfo", missingInfo);
            execution.setVariable("policyPotentialIssues", issues);
            execution.setVariable("policyComparatorStatus", "success");

            log.info("Extracted policy comparison: Missing={}, Issues={}", missingInfo, issues);

        } catch (Exception e) {
            log.error("Error extracting policy comparison fields", e);
            execution.setVariable("policyMissingInfo", "Error parsing response");
            execution.setVariable("policyPotentialIssues", "Unable to extract policy comparison data");
            execution.setVariable("policyComparatorStatus", "error");
        }
    }
}