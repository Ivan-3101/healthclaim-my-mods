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
public class SaveFinalFormListener implements ExecutionListener {

    @Override
    public void notify(DelegateExecution execution) throws Exception {
        String action = (String) execution.getVariable("FinalAction");

        // Only save on approval
        if (!"approve".equalsIgnoreCase(action)) {
            log.info("FinalAction is not approve, skipping final form save");
            return;
        }

        String ticketId = String.valueOf(execution.getVariable("TicketID"));
        String tenantId = execution.getTenantId();

        log.info("=== Execution Listener: Saving Final Form for Ticket {} ===", ticketId);

        try {
            // 1. Get current UI_Displayer data (edited or original)
            String currentData = getCurrentUIDisplayerData(execution, tenantId);

            if (currentData == null || currentData.trim().isEmpty()) {
                log.warn("No current UI displayer data found, cannot save final form");
                return;
            }

            // 2. Parse current data
            JSONObject currentJson = new JSONObject(currentData);
            JSONArray answerArray = currentJson.getJSONArray("answer");
            JSONArray updatedArray = new JSONArray();

            int updatedCount = 0;

            // 3. Update ALL fields with edited values from process variables
            for (int i = 0; i < answerArray.length(); i++) {
                JSONObject field = answerArray.getJSONObject(i);
                String fieldName = field.getString("field_name");

                JSONObject updatedField = new JSONObject(field.toString());

                // Get edited value for ALL fields
                String varName = "final_" + fieldName
                        .toLowerCase()
                        .replaceAll("[^a-z0-9]", "_")
                        .replaceAll("_+", "_")
                        .replaceAll("^_|_$", "");

                Object editedValue = execution.getVariable(varName);

                if (editedValue != null) {
                    String originalValue = field.opt("value") != null ?
                            field.opt("value").toString() : "";
                    String newValue = editedValue.toString();

                    // Update value
                    updatedField.put("value", newValue);
                    updatedField.put("final_edited", true);
                    updatedField.put("pre_final_value", originalValue);

                    // Track if actually changed
                    if (!originalValue.equals(newValue)) {
                        updatedCount++;
                        log.info("Field '{}' updated in final review: '{}' -> '{}'", fieldName,
                                originalValue.isEmpty() ? "[empty]" : originalValue, newValue);
                    }
                } else {
                    // Keep current value for fields not edited
                    updatedField.put("final_edited", false);
                }

                updatedArray.put(updatedField);
            }

            // 4. Build final response JSON
            JSONObject finalResponse = new JSONObject();
            finalResponse.put("agentid", "final");
            finalResponse.put("answer", updatedArray);
            finalResponse.put("version", "v3_final");
            finalResponse.put("final_timestamp", System.currentTimeMillis());
            finalResponse.put("ticket_id", ticketId);
            finalResponse.put("fields_updated_count", updatedCount);

            // 5. Store in MinIO - NEW "final" folder
            Map<String, Object> finalResult = new HashMap<>();
            finalResult.put("agentId", "final");
            finalResult.put("statusCode", 200);
            finalResult.put("apiResponse", finalResponse.toString());
            finalResult.put("version", "v3");
            finalResult.put("timestamp", System.currentTimeMillis());

            // Store with agentId="final" and stage="consolidated"
            // This creates: {tenantId}/HealthClaim/{ticketId}/results/final/consolidated.json
            String finalPath = AgentResultStorageService.storeAgentResultStageWise(
                    tenantId, ticketId, "consolidated", "final", finalResult);

            log.info("Stored final form at: {}", finalPath);

            // 6. Set process variable for reference
            execution.setVariable("finalFormMinioPath", finalPath);
            execution.setVariable("finalFieldsUpdatedCount", updatedCount);

            log.info("=== Final Form Saved Successfully ({} fields updated) ===", updatedCount);

        } catch (Exception e) {
            log.error("Error in SaveFinalFormListener", e);
            execution.setVariable("finalFormSaveError", e.getMessage());
            // Don't throw - let workflow continue even if save fails
        }
    }

    private String getCurrentUIDisplayerData(DelegateExecution execution, String tenantId) throws Exception {
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
}