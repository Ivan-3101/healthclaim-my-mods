package com.DronaPay.frm.HealthClaim;

import com.DronaPay.frm.HealthClaim.generic.services.AgentResultStorageService;
import com.DronaPay.frm.HealthClaim.generic.services.ConfigurationService;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.util.EntityUtils;
import org.cibseven.bpm.engine.delegate.BpmnError;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.json.JSONArray;
import org.json.JSONObject;

import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class PolicyCoherenceDelegate implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("=== Policy Coherence Started ===");

        String tenantId = execution.getTenantId();
        String ticketId = String.valueOf(execution.getVariable("TicketID"));

        log.info("TicketID: {}, TenantID: {}", ticketId, tenantId);

        // 1. Load workflow configuration
        Connection conn = execution.getProcessEngine()
                .getProcessEngineConfiguration()
                .getDataSource()
                .getConnection();

        JSONObject workflowConfig = ConfigurationService.loadWorkflowConfig("HealthClaim", tenantId, conn);
        conn.close();

        // 2. Get consolidated FHIR request from MinIO
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

        // 3. Get UI displayer output (edited version if available, otherwise original)
        String uiDisplayerData = getUIDisplayerData(execution, tenantId);

        if (uiDisplayerData == null) {
            log.error("No UI displayer data found");
            throw new BpmnError("policyCoherenceFailed", "Missing UI displayer data");
        }

        // 4. Build request with both consolidated FHIR and UI displayer data
        String policyCoherenceRequest = buildPolicyCoherenceRequest(consolidatedRequest, uiDisplayerData, ticketId);

        log.info("Built Policy Coherence request ({} bytes)", policyCoherenceRequest.length());

        // 5. Call Policy Coherence agent
        APIServices apiServices = new APIServices(tenantId, workflowConfig);
        CloseableHttpResponse response = apiServices.callAgent(policyCoherenceRequest);

        String resp = EntityUtils.toString(response.getEntity());
        int statusCode = response.getStatusLine().getStatusCode();

        log.info("Policy Coherence API status: {}", statusCode);
        log.debug("Policy Coherence API response: {}", resp);

        if (statusCode != 200) {
            log.error("Policy Coherence agent failed with status: {}", statusCode);
            throw new BpmnError("policyCoherenceFailed",
                    "Policy Coherence agent failed with status: " + statusCode);
        }

        // 6. Store result in MinIO
        Map<String, Object> fullResult = AgentResultStorageService.buildResultMap(
                "policy_comp1", statusCode, resp, new HashMap<>());

        String policyCoherenceMinioPath = AgentResultStorageService.storeAgentResultStageWise(
                tenantId, ticketId, "consolidated", "policy_comp1", fullResult);

        log.info("Stored Policy Coherence result at: {}", policyCoherenceMinioPath);

        // 7. Set MinIO path for reference
        execution.setVariable("policyCoherenceMinioPath", policyCoherenceMinioPath);
        execution.setVariable("policyCoherenceSuccess", true);

        log.info("=== Policy Coherence Completed ===");
    }

    /**
     * Get UI displayer data - prefer edited version, fallback to original
     */
    private String getUIDisplayerData(DelegateExecution execution, String tenantId) throws Exception {
        // First try edited version
        String editedFormMinioPath = (String) execution.getVariable("editedFormMinioPath");

        if (editedFormMinioPath != null && !editedFormMinioPath.trim().isEmpty()) {
            log.info("Using edited UI displayer data from: {}", editedFormMinioPath);
            Map<String, Object> result = AgentResultStorageService.retrieveAgentResult(tenantId, editedFormMinioPath);
            return (String) result.get("apiResponse");
        }

        // Fallback to original
        String uiDisplayerMinioPath = (String) execution.getVariable("uiDisplayerMinioPath");

        if (uiDisplayerMinioPath != null && !uiDisplayerMinioPath.trim().isEmpty()) {
            log.info("Using original UI displayer data from: {}", uiDisplayerMinioPath);
            Map<String, Object> result = AgentResultStorageService.retrieveAgentResult(tenantId, uiDisplayerMinioPath);
            return (String) result.get("apiResponse");
        }

        return null;
    }

    /**
     * Build Policy Coherence request with consolidated FHIR + UI displayer data
     */
    private String buildPolicyCoherenceRequest(String consolidatedRequest, String uiDisplayerData, String ticketId) {
        JSONObject consolidatedJson = new JSONObject(consolidatedRequest);
        JSONObject uiDisplayerJson = new JSONObject(uiDisplayerData);

        // Get existing doc_fhir array from consolidated request
        JSONArray docFhirArray = consolidatedJson.getJSONObject("data").getJSONArray("doc_fhir");

        // Convert UI displayer output to doc_fhir format
        JSONObject uiDisplayerDoc = convertUIDisplayerToDocFhir(uiDisplayerJson, ticketId);

        // Append UI displayer document to the array
        docFhirArray.put(uiDisplayerDoc);

        // Build final request
        JSONObject finalRequest = new JSONObject();
        JSONObject data = new JSONObject();
        data.put("doc_fhir", docFhirArray);
        finalRequest.put("data", data);
        finalRequest.put("agentid", "policy_comp1");

        return finalRequest.toString();
    }

    /**
     * Convert UI displayer output to doc_fhir format
     */
    private JSONObject convertUIDisplayerToDocFhir(JSONObject uiDisplayerJson, String ticketId) {
        JSONObject docFhirDoc = new JSONObject();
        docFhirDoc.put("doc_type", "ui_displayer_edited");

        // Add metadata
        JSONObject metadata = new JSONObject();
        metadata.put("ticket_id", ticketId);
        metadata.put("version", uiDisplayerJson.optString("version", "v2"));
        metadata.put("edited_timestamp", uiDisplayerJson.optLong("edited_timestamp", System.currentTimeMillis()));
        metadata.put("fields_updated_count", uiDisplayerJson.optInt("fields_updated_count", 0));
        docFhirDoc.put("metadata", metadata);

        // Convert answer array to fields object grouped by doc_type
        JSONArray answerArray = uiDisplayerJson.getJSONArray("answer");
        JSONObject fields = new JSONObject();

        for (int i = 0; i < answerArray.length(); i++) {
            JSONObject field = answerArray.getJSONObject(i);
            String docType = field.optString("doc_type", "Unknown");
            String fieldName = field.getString("field_name");
            Object value = field.opt("value");

            // Initialize doc type object if not exists
            if (!fields.has(docType)) {
                fields.put(docType, new JSONObject());
            }

            // Add field to doc type
            JSONObject docTypeFields = fields.getJSONObject(docType);
            docTypeFields.put(fieldName, value != null && !JSONObject.NULL.equals(value) ? value.toString() : null);
        }

        docFhirDoc.put("fields", fields);

        return docFhirDoc;
    }
}