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
public class MedicalCoherenceDelegate implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("=== Medical Coherence Started ===");

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
            throw new BpmnError("medicalCoherenceFailed", "Missing fhirConsolidatorMinioPath");
        }

        log.info("Retrieving consolidated FHIR request from MinIO: {}", consolidatorMinioPath);

        Map<String, Object> result = AgentResultStorageService.retrieveAgentResult(tenantId, consolidatorMinioPath);
        String consolidatedRequest = (String) result.get("apiResponse");

        if (consolidatedRequest == null || consolidatedRequest.trim().isEmpty()) {
            log.error("Empty consolidated request retrieved from MinIO");
            throw new BpmnError("medicalCoherenceFailed", "Empty consolidated FHIR request in MinIO");
        }

        log.info("Retrieved consolidated FHIR request ({} bytes) from MinIO", consolidatedRequest.length());

        // 3. Get UI displayer output (edited version if available, otherwise original)
        String uiDisplayerData = getUIDisplayerData(execution, tenantId);

        if (uiDisplayerData == null) {
            log.error("No UI displayer data found");
            throw new BpmnError("medicalCoherenceFailed", "Missing UI displayer data");
        }

        // 4. Build request with both consolidated FHIR and UI displayer data
        String medicalCoherenceRequest = buildMedicalCoherenceRequest(consolidatedRequest, uiDisplayerData, ticketId);

        log.info("Built Medical Coherence request ({} bytes)", medicalCoherenceRequest.length());

        // 5. Call Medical Coherence agent
        APIServices apiServices = new APIServices(tenantId, workflowConfig);
        CloseableHttpResponse response = apiServices.callAgent(medicalCoherenceRequest);

        String resp = EntityUtils.toString(response.getEntity());
        int statusCode = response.getStatusLine().getStatusCode();

        log.info("Medical Coherence API status: {}", statusCode);
        log.debug("Medical Coherence API response: {}", resp);

        if (statusCode != 200) {
            log.error("Medical Coherence agent failed with status: {}", statusCode);
            throw new BpmnError("medicalCoherenceFailed",
                    "Medical Coherence agent failed with status: " + statusCode);
        }

        // 6. Store result in MinIO
        Map<String, Object> fullResult = AgentResultStorageService.buildResultMap(
                "medical_comp", statusCode, resp, new HashMap<>());

        String medicalCoherenceMinioPath = AgentResultStorageService.storeAgentResultStageWise(
                tenantId, ticketId, "consolidated", "medical_comp", fullResult);

        log.info("Stored Medical Coherence result at: {}", medicalCoherenceMinioPath);

        // 7. Set MinIO path for reference
        execution.setVariable("medicalCoherenceMinioPath", medicalCoherenceMinioPath);
        execution.setVariable("medicalCoherenceSuccess", true);

        log.info("=== Medical Coherence Completed ===");
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
     * Build Medical Coherence request with consolidated FHIR + UI displayer data
     */
    private String buildMedicalCoherenceRequest(String consolidatedRequest, String uiDisplayerData, String ticketId) {
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
        finalRequest.put("agentid", "medical_comp");

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