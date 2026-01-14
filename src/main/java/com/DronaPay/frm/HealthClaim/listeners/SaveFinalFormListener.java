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
public class SaveFinalFormListener implements ExecutionListener {

    @Override
    public void notify(DelegateExecution execution) throws Exception {
        String action = (String) execution.getVariable("FinalAction");

        if (!"approve".equalsIgnoreCase(action)) {
            log.info("FinalAction is not approve, skipping final form save");
            return;
        }

        String ticketId = String.valueOf(execution.getVariable("TicketID"));
        String tenantId = execution.getTenantId();

        log.info("=== Execution Listener: Saving Final Form for Ticket {} ===", ticketId);

        try {
            String currentData = getCurrentUIDisplayerData(execution, tenantId);

            if (currentData == null || currentData.trim().isEmpty()) {
                log.warn("No current UI displayer data found, cannot save final form");
                return;
            }

            JSONObject currentJson = new JSONObject(currentData);
            JSONArray answerArray = currentJson.getJSONArray("answer");
            JSONArray updatedArray = new JSONArray();

            int updatedCount = 0;

            for (int i = 0; i < answerArray.length(); i++) {
                JSONObject field = answerArray.getJSONObject(i);
                String fieldName = field.getString("field_name");

                JSONObject updatedField = new JSONObject(field.toString());

                String varName = "final_" + fieldName
                        .toLowerCase()
                        .replaceAll("[^a-z0-9]", "_")
                        .replaceAll("_+", "_")
                        .replaceAll("^_|_$", "");

                Object editedValue = execution.getVariable(varName);

                if (editedValue != null) {
                    String originalValue = normalizeValue(field.opt("value"));
                    String newValue = normalizeValue(editedValue.toString());

                    updatedField.put("value", editedValue.toString().isEmpty() ? null : editedValue.toString());
                    updatedField.put("final_edited", true);
                    updatedField.put("pre_final_value", field.opt("value"));

                    if (!originalValue.equals(newValue)) {
                        updatedCount++;
                        log.info("Field '{}' updated in final review: '{}' -> '{}'", fieldName,
                                originalValue.isEmpty() ? "[empty]" : originalValue, newValue);
                    }
                } else {
                    updatedField.put("final_edited", false);
                }

                updatedArray.put(updatedField);
            }

            JSONObject finalResponse = new JSONObject();
            finalResponse.put("agentid", "final");
            finalResponse.put("answer", updatedArray);
            finalResponse.put("version", "v3_final");
            finalResponse.put("final_timestamp", System.currentTimeMillis());
            finalResponse.put("ticket_id", ticketId);
            finalResponse.put("fields_updated_count", updatedCount);

            Map<String, Object> finalResult = new HashMap<>();
            finalResult.put("agentId", "final");
            finalResult.put("statusCode", 200);
            finalResult.put("rawResponse", finalResponse.toString());
            finalResult.put("version", "v3");
            finalResult.put("timestamp", System.currentTimeMillis());

            String finalPath = AgentResultStorageService.storeAgentResult(
                    tenantId, ticketId, "final", "consolidated", finalResult);

            log.info("Stored final form at: {}", finalPath);

            execution.setVariable("finalFormMinioPath", finalPath);
            execution.setVariable("finalFieldsUpdatedCount", updatedCount);

            log.info("=== Final Form Saved Successfully ({} fields updated) ===", updatedCount);

        } catch (Exception e) {
            log.error("Error in SaveFinalFormListener", e);
            execution.setVariable("finalFormSaveError", e.getMessage());
        }
    }

    private String getCurrentUIDisplayerData(DelegateExecution execution, String tenantId) throws Exception {
        String editedFormMinioPath = (String) execution.getVariable("editedFormMinioPath");

        if (editedFormMinioPath != null && !editedFormMinioPath.trim().isEmpty()) {
            log.info("Using edited UI displayer data from: {}", editedFormMinioPath);
            Map<String, Object> result = AgentResultStorageService.retrieveAgentResult(tenantId, editedFormMinioPath);
            return (String) result.get("apiResponse");
        }

        String uiDisplayerMinioPath = (String) execution.getVariable("uiDisplayerMinioPath");

        if (uiDisplayerMinioPath != null && !uiDisplayerMinioPath.trim().isEmpty()) {
            log.info("Using original UI displayer data from: {}", uiDisplayerMinioPath);
            Map<String, Object> result = AgentResultStorageService.retrieveAgentResult(tenantId, uiDisplayerMinioPath);
            return (String) result.get("apiResponse");
        }

        return null;
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