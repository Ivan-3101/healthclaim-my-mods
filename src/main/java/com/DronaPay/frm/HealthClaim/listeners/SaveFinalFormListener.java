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
 * Listener to save final reviewed form data from final review task
 *
 * NEW structure:
 * - Reads edited from: 11_UIDisplayer/task-docs/edited.json (or original result.json)
 * - Writes final to: 17_FinalReviewTask/task-docs/final.json
 */
@Slf4j
public class SaveFinalFormListener implements ExecutionListener {

    @Override
    public void notify(DelegateExecution execution) throws Exception {
        log.info("=== SaveFinalFormListener Started ===");

        try {
            String tenantId = execution.getTenantId();
            String ticketId = String.valueOf(execution.getVariable("TicketID"));
            String workflowKey = (String) execution.getVariable("WorkflowKey");
            if (workflowKey == null) {
                workflowKey = "HealthClaim";
            }

            // Get final form data from user task
            Object finalFormDataObj = execution.getVariable("finalFormData");
            if (finalFormDataObj == null) {
                log.warn("No finalFormData found, skipping save");
                return;
            }

            JSONObject finalFormData = new JSONObject(finalFormDataObj.toString());

            // Get edited or original UI displayer data
            String dataPath = (String) execution.getVariable("editedFormMinioPath");
            if (dataPath == null) {
                dataPath = (String) execution.getVariable("UI_DisplayerMinioPath");
            }

            if (dataPath == null) {
                log.error("No UI displayer data path found");
                throw new RuntimeException("Cannot save final form - data path missing");
            }

            Map<String, Object> previousResult =
                    AgentResultStorageService.retrieveAgentResult(tenantId, dataPath);
            String previousResponse = (String) previousResult.get("apiResponse");

            JSONObject previousData = new JSONObject(previousResponse);
            JSONArray previousAnswer = previousData.getJSONArray("answer");

            // Build final answer array
            JSONArray finalArray = new JSONArray();
            int updatedCount = 0;

            for (int i = 0; i < previousAnswer.length(); i++) {
                JSONObject field = previousAnswer.getJSONObject(i);
                String fieldName = field.getString("field_name");

                JSONObject finalField = new JSONObject();
                finalField.put("field_name", fieldName);
                finalField.put("field_type", field.optString("field_type", "text"));
                finalField.put("section", field.optString("section", ""));

                // Check if this field was edited in final review
                if (finalFormData.has(fieldName)) {
                    Object editedValue = finalFormData.get(fieldName);
                    String originalValue = field.has("value") && !field.isNull("value")
                            ? field.opt("value").toString() : "";
                    String newValue = editedValue.toString();

                    // Update value
                    finalField.put("value", newValue);
                    finalField.put("final_edited", true);
                    finalField.put("pre_final_value", originalValue);

                    // Track if actually changed
                    if (!originalValue.equals(newValue)) {
                        updatedCount++;
                        log.info("Field '{}' updated in final review: '{}' -> '{}'", fieldName,
                                originalValue.isEmpty() ? "[empty]" : originalValue, newValue);
                    }
                } else {
                    // Keep current value for fields not edited
                    finalField.put("value", field.opt("value"));
                    finalField.put("final_edited", false);
                }

                finalArray.put(finalField);
            }

            // Build final response JSON
            JSONObject finalResponse = new JSONObject();
            finalResponse.put("agentid", "final");
            finalResponse.put("answer", finalArray);
            finalResponse.put("version", "v3_final");
            finalResponse.put("final_timestamp", System.currentTimeMillis());
            finalResponse.put("ticket_id", ticketId);
            finalResponse.put("fields_updated_count", updatedCount);

            // Store in MinIO using NEW structure
            Map<String, Object> finalResult = new HashMap<>();
            finalResult.put("agentId", "final");
            finalResult.put("statusCode", 200);
            finalResult.put("apiResponse", finalResponse.toString());
            finalResult.put("version", "v3");
            finalResult.put("timestamp", System.currentTimeMillis());

            // Store final form
            String finalPath = AgentResultStorageService.storeFinalForm(
                    tenantId, workflowKey, ticketId, finalResult);

            log.info("Stored final form at: {}", finalPath);

            // Set process variable for reference
            execution.setVariable("finalFormMinioPath", finalPath);
            execution.setVariable("finalFieldsUpdatedCount", updatedCount);

            log.info("=== Final Form Saved Successfully ({} fields updated) ===", updatedCount);

        } catch (Exception e) {
            log.error("Error in SaveFinalFormListener", e);
            execution.setVariable("finalFormSaveError", e.getMessage());
            // Don't throw - let workflow continue even if save fails
        }
    }
}