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
public class SaveEditedFormListener implements ExecutionListener {

    @Override
    public void notify(DelegateExecution execution) throws Exception {
        log.info("=== Saving Edited Form ===");

        try {
            String tenantId = execution.getTenantId();
            String ticketId = String.valueOf(execution.getVariable("TicketID"));
            String workflowKey = StoragePathBuilder.getWorkflowType(execution);

            // Load original UI display data from MinIO
            String originalMinioPath = (String) execution.getVariable("uiDisplayerMinioPath");

            if (originalMinioPath == null || originalMinioPath.trim().isEmpty()) {
                log.warn("No uiDisplayerMinioPath found, skipping edit save");
                return;
            }

            StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);
            InputStream stream = storage.downloadDocument(originalMinioPath);
            byte[] originalBytes = stream.readAllBytes();
            String originalContent = new String(originalBytes, StandardCharsets.UTF_8);

            JSONObject originalResponse = new JSONObject(originalContent);

            if (!originalResponse.has("rawResponse")) {
                log.warn("No rawResponse in original UI displayer data");
                return;
            }

            String rawResponse = originalResponse.getString("rawResponse");
            JSONObject responseJson = new JSONObject(rawResponse);
            JSONArray originalAnswer = responseJson.getJSONArray("answer");

            // Read edited values from process variables and update
            int updatedCount = 0;
            JSONArray updatedAnswer = new JSONArray();

            for (int i = 0; i < originalAnswer.length(); i++) {
                JSONObject field = originalAnswer.getJSONObject(i);
                String fieldName = field.getString("field_name");

                // Get variable name for this field
                String varName = "ui_" + fieldName
                        .toLowerCase()
                        .replaceAll("[^a-z0-9]", "_")
                        .replaceAll("_+", "_")
                        .replaceAll("^_|_$", "");

                Object editedValue = execution.getVariable(varName);

                JSONObject updatedField = new JSONObject(field.toString());

                if (editedValue != null) {
                    String originalValue = field.opt("value") != null ?
                            field.opt("value").toString() : "";
                    String newValue = editedValue.toString();

                    updatedField.put("value", newValue);
                    updatedField.put("edited", true);
                    updatedField.put("original_value", originalValue);

                    if (!originalValue.equals(newValue)) {
                        updatedCount++;
                        log.info("Field '{}' updated: '{}' -> '{}'", fieldName,
                                originalValue, newValue);
                    }
                } else {
                    updatedField.put("edited", false);
                }

                updatedAnswer.put(updatedField);
            }

            if (updatedCount == 0) {
                log.info("No fields were modified, skipping save");
                return;
            }

            // Build updated response JSON
            JSONObject updatedResponseData = new JSONObject();
            updatedResponseData.put("answer", updatedAnswer);

            JSONObject updatedResponse = new JSONObject();
            updatedResponse.put("agentId", "UI_Displayer");
            updatedResponse.put("statusCode", 200);
            updatedResponse.put("success", true);
            updatedResponse.put("rawResponse", updatedResponseData.toString());
            updatedResponse.put("extractedData", originalResponse.opt("extractedData"));
            updatedResponse.put("timestamp", System.currentTimeMillis());
            updatedResponse.put("version", "edited");
            updatedResponse.put("fields_updated_count", updatedCount);

            // Store in MinIO using stage-based structure
            String taskName = "Activity_UIDisplayer";
            int stageNumber = WorkflowStageMapping.getStageNumber(workflowKey, taskName);

            if (stageNumber == -1) {
                stageNumber = 11;
            }

            Properties props = ConfigurationService.getTenantProperties(tenantId);
            String rootFolder = props.getProperty("storage.minio.bucketName", "insurance-claims");

            byte[] content = updatedResponse.toString(2).getBytes(StandardCharsets.UTF_8);

            String editedPath = StoragePathBuilder.buildTaskDocsPath(
                    rootFolder, tenantId, workflowKey, ticketId,
                    stageNumber, taskName, "edited.json"
            );

            storage.uploadDocument(editedPath, content, "application/json");

            log.info("Stored edited form at: {}", editedPath);

            execution.setVariable("editedFormMinioPath", editedPath);
            execution.setVariable("fieldsUpdatedCount", updatedCount);

            log.info("=== Edited Form Saved Successfully ({} fields updated) ===", updatedCount);

        } catch (Exception e) {
            log.error("Error in SaveEditedFormListener", e);
            execution.setVariable("editedFormSaveError", e.getMessage());
        }
    }
}