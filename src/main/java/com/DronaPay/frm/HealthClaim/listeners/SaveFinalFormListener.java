package com.DronaPay.frm.HealthClaim.listeners;

import com.DronaPay.frm.HealthClaim.generic.config.WorkflowStageMapping;
import com.DronaPay.frm.HealthClaim.generic.services.ConfigurationService;
import com.DronaPay.frm.HealthClaim.generic.services.ObjectStorageService;
import com.DronaPay.frm.HealthClaim.generic.storage.StorageProvider;
import com.DronaPay.frm.HealthClaim.generic.utils.StoragePathBuilder;
import lombok.extern.slf4j.Slf4j;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.ExecutionListener;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

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
        String workflowKey = StoragePathBuilder.getWorkflowType(execution);

        log.info("=== Execution Listener: Saving Final Form for Ticket {} ===", ticketId);

        try {
            // Get current UI_Displayer data (edited or original)
            String currentMinioPath = null;
            String editedFormMinioPath = (String) execution.getVariable("editedFormMinioPath");

            if (editedFormMinioPath != null && !editedFormMinioPath.trim().isEmpty()) {
                log.info("Using edited UI displayer data from: {}", editedFormMinioPath);
                currentMinioPath = editedFormMinioPath;
            } else {
                String uiDisplayerMinioPath = (String) execution.getVariable("uiDisplayerMinioPath");
                if (uiDisplayerMinioPath != null && !uiDisplayerMinioPath.trim().isEmpty()) {
                    log.info("Using original UI displayer data from: {}", uiDisplayerMinioPath);
                    currentMinioPath = uiDisplayerMinioPath;
                }
            }

            if (currentMinioPath == null) {
                log.warn("No UI displayer data found, cannot save final form");
                return;
            }

            StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);
            InputStream stream = storage.downloadDocument(currentMinioPath);
            byte[] content = stream.readAllBytes();
            String jsonString = new String(content, StandardCharsets.UTF_8);

            JSONObject storedData = new JSONObject(jsonString);

            if (!storedData.has("rawResponse")) {
                log.warn("No rawResponse in UI displayer data");
                return;
            }

            String rawResponse = storedData.getString("rawResponse");
            JSONObject responseJson = new JSONObject(rawResponse);
            JSONArray answerArray = responseJson.getJSONArray("answer");

            JSONArray updatedArray = new JSONArray();
            int updatedCount = 0;

            // Update ALL fields with edited values from process variables
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
                    String originalValue = field.opt("value") != null ?
                            field.opt("value").toString() : "";
                    String newValue = editedValue.toString();

                    updatedField.put("value", newValue);
                    updatedField.put("final_edited", true);
                    updatedField.put("pre_final_value", originalValue);

                    if (!originalValue.equals(newValue)) {
                        updatedCount++;
                        log.info("Field '{}' updated in final review: '{}' -> '{}'", fieldName,
                                originalValue.isEmpty() ? "null" : originalValue, newValue);
                    }
                } else {
                    updatedField.put("final_edited", false);
                }

                updatedArray.put(updatedField);
            }

            // Build final response JSON
            JSONObject finalResponseData = new JSONObject();
            finalResponseData.put("answer", updatedArray);

            JSONObject finalResponse = new JSONObject();
            finalResponse.put("agentId", "UI_Displayer_Final");
            finalResponse.put("statusCode", 200);
            finalResponse.put("success", true);
            finalResponse.put("rawResponse", finalResponseData.toString());
            finalResponse.put("extractedData", storedData.opt("extractedData"));
            finalResponse.put("timestamp", System.currentTimeMillis());
            finalResponse.put("version", "final");
            finalResponse.put("ticket_id", ticketId);
            finalResponse.put("fields_updated_count", updatedCount);

            // Store in MinIO using stage-based structure (stage 16: Final Review)
            String taskName = "Final_Review";
            int stageNumber = WorkflowStageMapping.getStageNumber(workflowKey, taskName);

            if (stageNumber == -1) {
                stageNumber = 16;
            }

            Properties props = ConfigurationService.getTenantProperties(tenantId);
            String rootFolder = props.getProperty("storage.minio.bucketName", "insurance-claims");

            byte[] finalContent = finalResponse.toString(2).getBytes(StandardCharsets.UTF_8);

            String finalPath = StoragePathBuilder.buildTaskDocsPath(
                    rootFolder, tenantId, workflowKey, ticketId,
                    stageNumber, taskName, "consolidated.json"
            );

            storage.uploadDocument(finalPath, finalContent, "application/json");

            log.info("Stored final form at: {}", finalPath);

            execution.setVariable("finalFormMinioPath", finalPath);
            execution.setVariable("finalFieldsUpdatedCount", updatedCount);

            log.info("=== Final Form Saved Successfully ({} fields updated) ===", updatedCount);

        } catch (Exception e) {
            log.error("Error in SaveFinalFormListener", e);
            execution.setVariable("finalFormSaveError", e.getMessage());
        }
    }
}