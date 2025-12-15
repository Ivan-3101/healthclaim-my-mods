package com.DronaPay.frm.HealthClaim.listeners;

import com.DronaPay.frm.HealthClaim.generic.services.AgentResultStorageService;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.ExecutionListener;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

@Slf4j
public class SaveEditedFormListener implements ExecutionListener {

    @Override
    public void notify(DelegateExecution execution) throws Exception {
        String action = (String) execution.getVariable("Action");

        // Only save on approval
        if (!"approve".equalsIgnoreCase(action)) {
            log.info("Action is not approve, skipping edited form save");
            return;
        }

        String ticketId = String.valueOf(execution.getVariable("TicketID"));
        String tenantId = execution.getTenantId();

        log.info("=== Execution Listener: Saving Edited Form for Ticket {} ===", ticketId);

        try {
            // 1. Get original UI_Displayer output from MinIO
            String uiDisplayerMinioPath = (String) execution.getVariable("uiDisplayerMinioPath");
            if (uiDisplayerMinioPath == null || uiDisplayerMinioPath.trim().isEmpty()) {
                log.warn("No uiDisplayerMinioPath found, cannot save edited form");
                return;
            }

            log.info("Retrieving original UI_Displayer output from: {}", uiDisplayerMinioPath);

            Map<String, Object> result = AgentResultStorageService.retrieveAgentResult(tenantId, uiDisplayerMinioPath);
            String originalResponse = (String) result.get("apiResponse");

            if (originalResponse == null || originalResponse.trim().isEmpty()) {
                log.error("Empty original UI_Displayer response from MinIO");
                return;
            }

            // 2. Parse original response
            JSONObject originalJson = new JSONObject(originalResponse);
            JSONArray answerArray = originalJson.getJSONArray("answer");
            JSONArray updatedArray = new JSONArray();

            int updatedCount = 0;

            // 3. Update ALL fields with edited values from process variables
            for (int i = 0; i < answerArray.length(); i++) {
                JSONObject field = answerArray.getJSONObject(i);
                String fieldName = field.getString("field_name");

                JSONObject updatedField = new JSONObject(field.toString());

                // Get edited value for ALL fields
                String varName = "ui_" + fieldName
                        .toLowerCase()
                        .replaceAll("[^a-z0-9]", "_")
                        .replaceAll("_+", "_")
                        .replaceAll("^_|_$", "");

                Object editedValue = execution.getVariable(varName);

                if (editedValue != null) {
                    String originalValue = field.opt("value") != null ? field.opt("value").toString() : "";
                    String newValue = editedValue.toString();

                    // Update value
                    updatedField.put("value", newValue);
                    updatedField.put("user_edited", true);
                    updatedField.put("original_value", originalValue);

                    // Track if actually changed (including null -> value)
                    if (!originalValue.equals(newValue)) {
                        updatedCount++;
                        log.info("Field '{}' updated: '{}' -> '{}'", fieldName,
                                originalValue.isEmpty() ? "[empty]" : originalValue, newValue);
                    }
                } else {
                    // Keep original value for fields not edited
                    updatedField.put("user_edited", false);
                }

                updatedArray.put(updatedField);
            }

            // 4. Build updated response JSON
            JSONObject updatedResponse = new JSONObject();
            updatedResponse.put("agentid", "UI_Displayer");
            updatedResponse.put("answer", updatedArray);
            updatedResponse.put("version", "v2_edited");
            updatedResponse.put("edited_timestamp", System.currentTimeMillis());
            updatedResponse.put("ticket_id", ticketId);
            updatedResponse.put("fields_updated_count", updatedCount);

            // 5. Store in MinIO
            Map<String, Object> updatedResult = new HashMap<>();
            updatedResult.put("agentId", "UI_Displayer_Edited");
            updatedResult.put("statusCode", 200);
            updatedResult.put("apiResponse", updatedResponse.toString());
            updatedResult.put("version", "v2");
            updatedResult.put("timestamp", System.currentTimeMillis());

            String editedPath = AgentResultStorageService.storeAgentResultStageWise(
                    tenantId, ticketId, "outputs", "UI_Displayer_Edited", updatedResult);

            log.info("Stored edited form (v2) at: {}", editedPath);

            // 6. Set process variable for reference
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