package com.DronaPay.frm.HealthClaim;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.json.JSONArray;
import org.json.JSONObject;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.util.EntityUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class PolicyComparator implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("Policy Comparator service called for ticket id " + execution.getVariable("TicketID"));

        // 1. Get the FHIR data from the process variables
        String fhirData = (String) execution.getVariable("fhir_json");

        log.info(">>>> PolicyComparator READ Process Variable 'fhir_json': " + fhirData);

        // 2. Construct the request payload
        JSONObject requestBody = new JSONObject();
        JSONObject data = new JSONObject();

        // Handle null FHIR data
        if (fhirData == null || fhirData.trim().isEmpty()) {
            log.error("FHIR JSON data is null or empty. Cannot proceed with Policy Comparator.");
            execution.setVariable("policyMissingInfo", "FHIR data not available");
            execution.setVariable("policyPotentialIssues", "Unable to compare policy - FHIR data missing");
            execution.setVariable("policyComparatorStatus", "failed");
            return;
        }

        data.put("doc_fhir", new JSONObject(fhirData));
        requestBody.put("data", data);
        requestBody.put("agentid", "policy_comp");

        log.info(">>>> PolicyComparator BUILT Request Body: " + requestBody.toString());

        // 3. Call the agent
        APIServices apiServices = new APIServices(execution.getTenantId());
        CloseableHttpResponse response = apiServices.callPolicyComparatorAgent(requestBody.toString());

        // 4. Process the response
        String resp = EntityUtils.toString(response.getEntity());
        int statusCode = response.getStatusLine().getStatusCode();
        log.info("Policy Comparator API status " + statusCode);
        log.info("Policy Comparator API response " + resp);

        if (statusCode == 200) {
            try {
                JSONObject responseJson = new JSONObject(resp);
                JSONObject answer = responseJson.getJSONObject("answer");
                JSONArray list2 = answer.getJSONArray("List 2");

                List<String> potentialIssues = new ArrayList<>();
                List<String> missingInfoItems = new ArrayList<>();

                for (int i = 0; i < list2.length(); i++) {
                    JSONObject item = list2.getJSONObject(i);
                    String status = item.optString("Status/Issue", "");
                    String area = item.optString("Question/Area", "Unknown Area");

                    if ("Missing information".equalsIgnoreCase(status)) {
                        missingInfoItems.add(area);
                    } else if (!"Match".equalsIgnoreCase(status)) {
                        potentialIssues.add(area + ": " + status);
                    }
                }

                // Save smaller, specific variables
                String missingInfo = missingInfoItems.isEmpty() ? "None" : String.join(", ", missingInfoItems);
                String issues = potentialIssues.isEmpty() ? "None" : String.join("\n", potentialIssues);

                execution.setVariable("policyMissingInfo", missingInfo);
                execution.setVariable("policyPotentialIssues", issues);
                execution.setVariable("policyComparatorStatus", "success");

                log.info(">>>> PolicyComparator SET 'policyMissingInfo': " + missingInfo);
                log.info(">>>> PolicyComparator SET 'policyPotentialIssues': " + issues);

            } catch (Exception e) {
                log.error("Error parsing Policy Comparator response: " + e.getMessage(), e);
                execution.setVariable("policyMissingInfo", "Error parsing response");
                execution.setVariable("policyPotentialIssues", "Unable to extract policy comparison data");
                execution.setVariable("policyComparatorStatus", "error");
            }
        } else {
            log.error("Policy Comparator API returned status code: " + statusCode);
            execution.setVariable("policyMissingInfo", "API call failed");
            execution.setVariable("policyPotentialIssues", "Unable to compare policy - API error " + statusCode);
            execution.setVariable("policyComparatorStatus", "failed");
        }
    }
}