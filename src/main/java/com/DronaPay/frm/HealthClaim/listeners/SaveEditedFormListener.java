package com.DronaPay.frm.HealthClaim.listeners;

import com.DronaPay.frm.HealthClaim.generic.services.AgentResultStorageService;
import lombok.extern.slf4j.Slf4j;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.ExecutionListener;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

@Slf4j
public class SaveEditedFormListener implements ExecutionListener {

    @Override
    public void notify(DelegateExecution execution) throws Exception {
        String action = (String) execution.getVariable("Action");

        if (!"approve".equalsIgnoreCase(action)) {
            log.info("Action is not approve, skipping edited form save");
            return;
        }

        String ticketId = String.valueOf(execution.getVariable("TicketID"));
        String tenantId = execution.getTenantId();

        log.info("=== Execution Listener: Saving Edited Form for Ticket {} ===", ticketId);

        try {
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

            JSONObject originalJson = new JSONObject(originalResponse);
            JSONArray answerArray = originalJson.getJSONArray("answer");
            JSONArray updatedArray = new JSONArray();

            int updatedCount = 0;

            for (int i = 0; i < answerArray.length(); i++) {
                JSONObject field = answerArray.getJSONObject(i);
                String fieldName = field.getString("field_name");

                JSONObject updatedField = new JSONObject(field.toString());

                String varName = "ui_" + fieldName
                        .toLowerCase()
                        .replaceAll("[^a-z0-9]", "_")
                        .replaceAll("_+", "_")
                        .replaceAll("^_|_$", "");

                Object editedValue = execution.getVariable(varName);

                if (editedValue != null) {
                    String originalValue = normalizeValue(field.opt("value"));
                    String newValue = normalizeValue(editedValue.toString());

                    updatedField.put("value", editedValue.toString().isEmpty() ? null : editedValue.toString());
                    updatedField.put("user_edited", true);
                    updatedField.put("original_value", field.opt("value"));

                    if (!originalValue.equals(newValue)) {
                        updatedCount++;
                        log.info("Field '{}' updated: '{}' -> '{}'", fieldName,
                                originalValue.isEmpty() ? "[empty]" : originalValue, newValue);
                    }
                } else {
                    updatedField.put("user_edited", false);
                }

                updatedArray.put(updatedField);
            }

            JSONObject updatedResponse = new JSONObject();
            updatedResponse.put("agentid", "UI_Displayer");
            updatedResponse.put("answer", updatedArray);
            updatedResponse.put("version", "v2_edited");
            updatedResponse.put("edited_timestamp", System.currentTimeMillis());
            updatedResponse.put("ticket_id", ticketId);
            updatedResponse.put("fields_updated_count", updatedCount);

            Map<String, Object> updatedResult = new HashMap<>();
            updatedResult.put("agentId", "UI_Displayer");
            updatedResult.put("statusCode", 200);
            updatedResult.put("apiResponse", updatedResponse.toString());
            updatedResult.put("version", "v2");
            updatedResult.put("timestamp", System.currentTimeMillis());

            String editedPath = AgentResultStorageService.storeAgentResult(
                    tenantId, ticketId, "UI_Displayer", "edited", updatedResult);

            log.info("Stored edited form at: {} (same folder as original)", editedPath);

            execution.setVariable("editedFormMinioPath", editedPath);
            execution.setVariable("fieldsUpdatedCount", updatedCount);

            log.info("=== Edited Form Saved Successfully ({} fields updated) ===", updatedCount);

        } catch (Exception e) {
            log.error("Error in SaveEditedFormListener", e);
            execution.setVariable("editedFormSaveError", e.getMessage());
        }
    }

    private String normalizeValue(Object value) {
        if (value == null || JSONObject.NULL.equals(value)) {
            return "";
        }
        String str = value.toString();
        if ("null".equals(str)) {
            return "";
        }
        return str.trim();
    }
}