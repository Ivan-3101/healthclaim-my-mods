package com.DronaPay.frm.HealthClaim;

import com.DronaPay.frm.HealthClaim.generic.delegates.GenericAgentExecutorDelegate;
import com.DronaPay.frm.HealthClaim.generic.services.AgentResultStorageService;
import lombok.extern.slf4j.Slf4j;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

        // Output mapping - DO NOT set large response as process variable
        // GenericAgentExecutorDelegate stores full response in MinIO
        JSONObject outputMapping = new JSONObject();
        JSONObject variablesToSet = new JSONObject();

        // We'll extract summary fields after retrieving from MinIO
        // No need to set policyComparatorRawResponse here

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

        // Now extract policy comparison fields from the MinIO-stored response
        extractPolicyComparisonFields(execution);

        log.debug("PolicyComparator completed via generic delegate with MinIO storage");
    }

    /**
     * Extract policy comparison fields from MinIO-stored response
     * This maintains backward compatibility with existing form fields
     */
    @SuppressWarnings("unchecked")
    private void extractPolicyComparisonFields(DelegateExecution execution) {
        try {
            // Get tenant ID and ticket ID
            String tenantId = execution.getTenantId();
            String ticketId = String.valueOf(execution.getVariable("TicketID"));
            String filename = (String) execution.getVariable("attachment");

            // Retrieve full response from MinIO
            Map<String, Map<String, Object>> fileProcessMap =
                    (Map<String, Map<String, Object>>) execution.getVariable("fileProcessMap");

            if (fileProcessMap == null || !fileProcessMap.containsKey(filename)) {
                log.warn("No fileProcessMap entry for file: {}", filename);
                execution.setVariable("policyMissingInfo", "Error: No policy comparison data");
                execution.setVariable("policyPotentialIssues", "Unable to retrieve policy comparison");
                execution.setVariable("policyComparatorStatus", "failed");
                return;
            }

            Map<String, Object> fileResults = fileProcessMap.get(filename);
            Map<String, Object> policyOutput = (Map<String, Object>) fileResults.get("policy_compOutput");

            if (policyOutput == null || !"success".equals(policyOutput.get("apiCall"))) {
                log.warn("Policy comparator API call failed or missing output");
                execution.setVariable("policyMissingInfo", "Error: Policy comparison failed");
                execution.setVariable("policyPotentialIssues", "API call unsuccessful");
                execution.setVariable("policyComparatorStatus", "failed");
                return;
            }

            // Get MinIO storage path
            String storagePath = (String) policyOutput.get("minioPath");
            if (storagePath == null) {
                log.error("No MinIO path found for policy comparator result");
                execution.setVariable("policyMissingInfo", "Error: Missing storage path");
                execution.setVariable("policyPotentialIssues", "Unable to retrieve stored result");
                execution.setVariable("policyComparatorStatus", "error");
                return;
            }

            // Retrieve full result from MinIO
            Map<String, Object> fullResult = AgentResultStorageService.retrieveAgentResult(tenantId, storagePath);

            // Get the API response
            String apiResponse = (String) fullResult.get("apiResponse");
            if (apiResponse == null) {
                log.warn("No API response in MinIO result");
                execution.setVariable("policyMissingInfo", "Error: Empty response");
                execution.setVariable("policyPotentialIssues", "No data in stored result");
                execution.setVariable("policyComparatorStatus", "failed");
                return;
            }

            // Parse the response JSON
            JSONObject responseJson = new JSONObject(apiResponse);

            // Extract the answer field
            if (!responseJson.has("answer")) {
                log.warn("Policy comparator response missing 'answer' field");
                execution.setVariable("policyMissingInfo", "None");
                execution.setVariable("policyPotentialIssues", "None");
                execution.setVariable("policyComparatorStatus", "success");
                return;
            }

            JSONObject answer = responseJson.getJSONObject("answer");

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

                if ("Missing Information".equalsIgnoreCase(status)) {
                    missingInfoItems.add(area);
                } else if (!"Match".equalsIgnoreCase(status) && !status.isEmpty()) {
                    potentialIssues.add(area + ": " + status);
                }
            }

            // Set process variables for user task form (SUMMARY ONLY - not full JSON)
            String missingInfo = missingInfoItems.isEmpty() ? "None" : String.join(", ", missingInfoItems);
            String issues = potentialIssues.isEmpty() ? "None" : String.join("\n", potentialIssues);

            execution.setVariable("policyMissingInfo", missingInfo);
            execution.setVariable("policyPotentialIssues", issues);
            execution.setVariable("policyComparatorStatus", "success");

            log.info("Extracted policy comparison: Missing={}, Issues={}", missingInfo, issues);

        } catch (Exception e) {
            log.error("Error extracting policy comparison fields from MinIO", e);
            execution.setVariable("policyMissingInfo", "Error parsing response");
            execution.setVariable("policyPotentialIssues", "Unable to extract policy comparison data");
            execution.setVariable("policyComparatorStatus", "error");
        }
    }
}