package com.DronaPay.frm.HealthClaim.listeners;

import com.DronaPay.frm.HealthClaim.generic.services.AgentResultStorageService;
import lombok.extern.slf4j.Slf4j;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.ExecutionListener;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Listener to save edited form data from user verification task
 *
 * NEW structure:
 * - Reads original from: 11_UIDisplayer/task-docs/result.json
 * - Writes edited to: 11_UIDisplayer/task-docs/edited.json
 */
@Slf4j
public class SaveEditedFormListener implements ExecutionListener {

    @Override
    public void notify(DelegateExecution execution) throws Exception {
        log.info("=== SaveEditedFormListener Started ===");

        try {
            String tenantId = execution.getTenantId();
            String ticketId = String.valueOf(execution.getVariable("TicketID"));
            String workflowKey = (String) execution.getVariable("WorkflowKey");
            if (workflowKey == null) {
                workflowKey = "HealthClaim";
            }

            // Get edited form data from user task
            Object editedFormDataObj = execution.getVariable("editedFormData");
            if (editedFormDataObj == null) {
                log.warn("No editedFormData found, skipping save");
                return;
            }

            JSONObject editedFormData = new JSONObject(editedFormDataObj.toString());

            // Get original UI displayer data from MinIO
            String uiDisplayerMinioPath = (String) execution.getVariable("UI_DisplayerMinioPath");
            if (uiDisplayerMinioPath == null) {
                log.error("UI_DisplayerMinioPath not found");
                throw new RuntimeException("Cannot save edited form - original data path missing");
            }

            Map<String, Object> originalResult =
                    AgentResultStorageService.retrieveAgentResult(tenantId, uiDisplayerMinioPath);
            String originalResponse = (String) originalResult.get("apiResponse");

            JSONObject originalData = new JSONObject(originalResponse);
            JSONArray originalAnswer = originalData.getJSONArray("answer");

            // Build updated answer array
            JSONArray updatedArray = new JSONArray();
            int updatedCount = 0;

            for (int i = 0; i < originalAnswer.length(); i++) {
                JSONObject field = originalAnswer.getJSONObject(i);
                String fieldName = field.getString("field_name");

                JSONObject updatedField = new JSONObject();
                updatedField.put("field_name", fieldName);
                updatedField.put("field_type", field.optString("field_type", "text"));
                updatedField.put("section", field.optString("section", ""));

                // Check if this field was edited
                if (editedFormData.has(fieldName)) {
                    Object editedValue = editedFormData.get(fieldName);
                    String originalValue = field.has("value") && !field.isNull("value")
                            ? field.opt("value").toString() : "";
                    String newValue = editedValue.toString();

                    // Update value
                    updatedField.put("value", newValue);
                    updatedField.put("edited", true);
                    updatedField.put("original_value", originalValue);

                    // Track if actually changed
                    if (!originalValue.equals(newValue)) {
                        updatedCount++;
                        log.info("Field '{}' edited: '{}' -> '{}'", fieldName,
                                originalValue.isEmpty() ? "[empty]" : originalValue, newValue);
                    }
                } else {
                    // Keep current value for fields not edited
                    updatedField.put("value", field.opt("value"));
                    updatedField.put("edited", false);
                }

                updatedArray.put(updatedField);
            }

            // Build updated response JSON
            JSONObject updatedResponse = new JSONObject();
            updatedResponse.put("agentid", "UI_Displayer");
            updatedResponse.put("answer", updatedArray);
            updatedResponse.put("version", "v2_edited");
            updatedResponse.put("edited_timestamp", System.currentTimeMillis());
            updatedResponse.put("ticket_id", ticketId);
            updatedResponse.put("fields_updated_count", updatedCount);

            // Store in MinIO using NEW structure
            Map<String, Object> updatedResult = new HashMap<>();
            updatedResult.put("agentId", "UI_Displayer");
            updatedResult.put("statusCode", 200);
            updatedResult.put("apiResponse", updatedResponse.toString());
            updatedResult.put("version", "v2");
            updatedResult.put("timestamp", System.currentTimeMillis());

            // Store edited form
            String editedPath = AgentResultStorageService.storeEditedForm(
                    tenantId, workflowKey, ticketId, updatedResult);

            log.info("Stored edited form at: {}", editedPath);

            // Set process variable for reference
            execution.setVariable("editedFormMinioPath", editedPath);
            execution.setVariable("fieldsUpdatedCount", updatedCount);

            log.info("=== Edited Form Saved Successfully ({} fields updated) ===", updatedCount);

        } catch (Exception e) {
            log.error("Error in SaveEditedFormListener", e);
            execution.setVariable("editedFormSaveError", e.getMessage());
            // Don't throw - let workflow continue even if save fails
        }
    }
}