package com.DronaPay.frm.HealthClaim;

import com.DronaPay.frm.HealthClaim.generic.services.AgentResultStorageService;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.cibseven.bpm.engine.delegate.BpmnError;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

@Slf4j
public class PolicyCoherenceDelegate implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("=== Policy Coherence Started ===");

        String tenantId = execution.getTenantId();
        String ticketId = String.valueOf(execution.getVariable("TicketID"));
        String stageName = execution.getCurrentActivityId();

        log.info("TicketID: {}, TenantID: {}", ticketId, tenantId);

        Properties tenantProps = TenantPropertiesUtil.getTenantProps(tenantId);
        String agentApiUrl = tenantProps.getProperty("agent.api.url");
        String agentUsername = tenantProps.getProperty("agent.api.username");
        String agentPassword = tenantProps.getProperty("agent.api.password");

        // Append /agent path if not present
        if (agentApiUrl != null && !agentApiUrl.endsWith("/agent")) {
            agentApiUrl = agentApiUrl + "/agent";
        }

        log.info("Using Agent API URL: {}", agentApiUrl);

        String consolidatorMinioPath = (String) execution.getVariable("fhirConsolidatorMinioPath");

        if (consolidatorMinioPath == null || consolidatorMinioPath.trim().isEmpty()) {
            log.error("No fhirConsolidatorMinioPath found in process variables");
            throw new BpmnError("policyCoherenceFailed", "Missing fhirConsolidatorMinioPath");
        }

        log.info("Retrieving consolidated FHIR request from MinIO: {}", consolidatorMinioPath);

        Map<String, Object> result = AgentResultStorageService.retrieveAgentResult(tenantId, consolidatorMinioPath);
        String consolidatedRequest = (String) result.get("apiResponse");

        if (consolidatedRequest == null || consolidatedRequest.trim().isEmpty()) {
            log.error("Empty consolidated request retrieved from MinIO");
            throw new BpmnError("policyCoherenceFailed", "Empty consolidated FHIR request in MinIO");
        }

        log.info("Retrieved consolidated FHIR request ({} bytes) from MinIO", consolidatedRequest.length());

        String uiDisplayerData = getUIDisplayerData(execution, tenantId);

        if (uiDisplayerData == null) {
            log.error("No UI displayer data found");
            throw new BpmnError("policyCoherenceFailed", "Missing UI displayer data");
        }

        String policyCoherenceRequest = buildPolicyCoherenceRequest(consolidatedRequest, uiDisplayerData, ticketId);

        log.info("Built Policy Coherence request ({} bytes)", policyCoherenceRequest.length());

        String resp = callAgentAPI(agentApiUrl, agentUsername, agentPassword, policyCoherenceRequest);

        log.info("Policy Coherence API call successful");
        log.debug("Policy Coherence API response: {}", resp);

        Map<String, Object> fullResult = AgentResultStorageService.buildResultMap(
                "policy_comp1", 200, resp, new HashMap<>());

        String policyCoherenceMinioPath = AgentResultStorageService.storeAgentResult(
                tenantId, ticketId, stageName, "consolidated", fullResult);

        log.info("Stored Policy Coherence result at: {}", policyCoherenceMinioPath);

        execution.setVariable("policyCoherenceMinioPath", policyCoherenceMinioPath);
        execution.setVariable("policyCoherenceSuccess", true);

        log.info("=== Policy Coherence Completed ===");
    }

    private String callAgentAPI(String url, String username, String password, String requestBody) throws Exception {
        CredentialsProvider provider = new BasicCredentialsProvider();
        provider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(username, password));

        try (CloseableHttpClient client = HttpClients.custom().setDefaultCredentialsProvider(provider).build()) {
            HttpPost post = new HttpPost(url);
            post.setEntity(new StringEntity(requestBody, StandardCharsets.UTF_8));
            post.setHeader("Content-Type", "application/json");

            try (CloseableHttpResponse response = client.execute(post)) {
                int status = response.getStatusLine().getStatusCode();
                String respStr = EntityUtils.toString(response.getEntity());

                if (status != 200) {
                    log.error("Policy Coherence API failed. Status: {}, Response: {}", status, respStr);
                    throw new BpmnError("policyCoherenceFailed", "Policy Coherence agent failed with status: " + status);
                }
                return respStr;
            }
        }
    }

    private String getUIDisplayerData(DelegateExecution execution, String tenantId) throws Exception {
        String editedFormMinioPath = (String) execution.getVariable("editedFormMinioPath");

        if (editedFormMinioPath != null && !editedFormMinioPath.trim().isEmpty()) {
            log.info("Using edited UI displayer data from: {}", editedFormMinioPath);
            Map<String, Object> result = AgentResultStorageService.retrieveAgentResult(tenantId, editedFormMinioPath);
            return (String) result.get("apiResponse");
        }

        String uiDisplayerMinioPath = (String) execution.getVariable("UI_Displayer_MinioPath");

        if (uiDisplayerMinioPath != null && !uiDisplayerMinioPath.trim().isEmpty()) {
            log.info("Using original UI displayer data from: {}", uiDisplayerMinioPath);
            Map<String, Object> result = AgentResultStorageService.retrieveAgentResult(tenantId, uiDisplayerMinioPath);
            return (String) result.get("apiResponse");
        }

        return null;
    }

    private String buildPolicyCoherenceRequest(String consolidatedRequest, String uiDisplayerData, String ticketId) {
        JSONObject consolidatedJson = new JSONObject(consolidatedRequest);
        JSONObject uiDisplayerJson = new JSONObject(uiDisplayerData);

        JSONArray docFhirArray = consolidatedJson.getJSONArray("doc_fhir");

        JSONObject uiDisplayerDoc = convertUIDisplayerToDocFhir(uiDisplayerJson, ticketId);

        docFhirArray.put(uiDisplayerDoc);

        JSONObject finalRequest = new JSONObject();
        JSONObject data = new JSONObject();
        data.put("doc_fhir", docFhirArray);
        finalRequest.put("data", data);
        finalRequest.put("agentid", "policy_comp1");

        return finalRequest.toString();
    }

    private JSONObject convertUIDisplayerToDocFhir(JSONObject uiDisplayerJson, String ticketId) {
        JSONObject docFhirDoc = new JSONObject();
        docFhirDoc.put("doc_type", "ui_displayer_edited");

        JSONObject metadata = new JSONObject();
        metadata.put("ticket_id", ticketId);
        metadata.put("version", uiDisplayerJson.optString("version", "v2"));
        metadata.put("edited_timestamp", uiDisplayerJson.optLong("edited_timestamp", System.currentTimeMillis()));
        metadata.put("fields_updated_count", uiDisplayerJson.optInt("fields_updated_count", 0));
        docFhirDoc.put("metadata", metadata);

        JSONArray answerArray = uiDisplayerJson.getJSONArray("answer");
        JSONObject fields = new JSONObject();

        for (int i = 0; i < answerArray.length(); i++) {
            JSONObject field = answerArray.getJSONObject(i);
            String docType = field.optString("doc_type", "Unknown");
            String fieldName = field.getString("field_name");
            Object value = field.opt("value");

            if (!fields.has(docType)) {
                fields.put(docType, new JSONObject());
            }

            JSONObject docTypeFields = fields.getJSONObject(docType);
            docTypeFields.put(fieldName, value != null && !JSONObject.NULL.equals(value) ? value.toString() : null);
        }

        docFhirDoc.put("fields", fields);

        return docFhirDoc;
    }
}