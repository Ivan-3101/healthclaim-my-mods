package com.DronaPay.frm.HealthClaim;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.json.JSONArray;
import org.json.JSONObject;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.util.EntityUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList; // Import ArrayList
import java.util.List; // Import List

@Slf4j
public class PolicyComparator implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("Policy Comparator service called for ticket id " + execution.getVariable("TicketID"));

        // 1. Get the FHIR data from the process variables
        String fhirData = (String) execution.getVariable("fhir_json");

        // --- ADDED LOGGING ---
        log.info(">>>> PolicyComparator READ Process Variable 'fhir_json': " + fhirData);

        // 2. Construct the request payload as per the PDF
        JSONObject requestBody = new JSONObject();
        JSONObject data = new JSONObject();
        // Ensure fhirData is not null before parsing
        if (fhirData == null) {
            log.error("FHIR JSON data is null. Cannot proceed with Policy Comparator.");
            // Optionally throw an error to stop the process or handle appropriately
            throw new org.camunda.bpm.engine.delegate.BpmnError("fhirDataNullError", "FHIR JSON data was null");
        }
        data.put("doc_fhir", new JSONObject(fhirData)); // This line could still throw JSONException if fhirData is not valid JSON
        requestBody.put("data", data);
        requestBody.put("agentid", "policy_comp"); // As per the PDF

        // --- ADDED LOGGING ---
        log.info(">>>> PolicyComparator BUILT Request Body: " + requestBody.toString());

        // 3. Call the new agent via APIServices
        APIServices apiServices = new APIServices(execution.getTenantId());
        CloseableHttpResponse response = apiServices.callPolicyComparatorAgent(requestBody.toString());

        // 4. Process the response
        String resp = EntityUtils.toString(response.getEntity());
        int statusCode = response.getStatusLine().getStatusCode();
        log.info("Policy Comparator API status " + statusCode);
        log.info("Policy Comparator API response " + resp);

        // --- Start of Replacement Block ---
        if (statusCode == 200) {
            JSONObject responseJson = new JSONObject(resp);
            JSONObject answer = responseJson.getJSONObject("answer");
            // We are interested in List 2 which contains potential issues/deviations
            JSONArray list2 = answer.getJSONArray("List 2");

            List<String> potentialIssues = new ArrayList<>();
            List<String> missingInfoItems = new ArrayList<>();

            for (int i = 0; i < list2.length(); i++) {
                JSONObject item = list2.getJSONObject(i);
                String status = item.optString("Status/Issue", ""); // Use optString for safety
                String area = item.optString("Question/Area", "Unknown Area");

                if ("Missing information".equalsIgnoreCase(status)) {
                    missingInfoItems.add(area);
                } else if (!"Match".equalsIgnoreCase(status)) {
                    // Add other non-matching statuses to potential issues list
                    potentialIssues.add(area + ": " + status);
                }
            }

            // --- Save Smaller, Specific Variables ---
            // Save items marked as "Missing information"
            execution.setVariable("policyMissingInfo", String.join(", ", missingInfoItems));
            log.info(">>>> PolicyComparator SET 'policyMissingInfo': " + String.join(", ", missingInfoItems));

            // Save other potential issues (e.g., "Potential exclusion", "Potential issue")
            execution.setVariable("policyPotentialIssues", String.join("\n", potentialIssues)); // Use newline for readability
            log.info(">>>> PolicyComparator SET 'policyPotentialIssues': " + String.join("\n", potentialIssues));

            // --- IMPORTANT: Ensure the old line saving the full response is removed or commented out ---
            // execution.setVariable("policyComparatorResponse", resp); // <-- Make sure this line is gone!

        } else {
            // Optional: Add error handling for non-200 status codes
            log.error("Policy Comparator API returned status code: " + statusCode);
            // Example: throw new org.camunda.bpm.engine.delegate.BpmnError("failedPolicyComparator");
        }
        // --- End of Replacement Block ---
    }
}