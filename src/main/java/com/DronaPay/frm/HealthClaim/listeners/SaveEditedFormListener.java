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

import java.nio.charset.StandardCharsets;
import java.util.Map;
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

            // Get the edited form data
            Object editedData = execution.getVariable("editedFormData");
            if (editedData == null) {
                log.info("No edited form data found, skipping save");
                return;
            }

            JSONObject editedJson = new JSONObject(editedData.toString());
            JSONArray editedFields = editedJson.getJSONArray("editedFields");

            // Load original UI display data from MinIO
            String originalMinioPath = (String) execution.getVariable("uiDisplayerMinioPath");

            StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);
            byte[] originalBytes = storage.downloadDocument(originalMinioPath).readAllBytes();
            String originalContent = new String(originalBytes, StandardCharsets.UTF_8);

            JSONObject originalResponse = new JSONObject(originalContent);
            JSONArray originalAnswer = originalResponse.getJSONArray("answer");

            // Merge edited fields back into original structure
            int updatedCount = 0;
            for (int i = 0; i < editedFields.length(); i++) {
                JSONObject editedField = editedFields.getJSONObject(i);
                String editedFieldName = editedField.getString("field");
                Object editedValue = editedField.get("value");

                // Find and update in original
                for (int j = 0; j < originalAnswer.length(); j++) {
                    JSONObject originalField = originalAnswer.getJSONObject(j);
                    if (originalField.getString("field").equals(editedFieldName)) {
                        originalField.put("value", editedValue);
                        updatedCount++;
                        break;
                    }
                }
            }

            // Build updated response JSON
            JSONObject updatedResponse = new JSONObject();
            updatedResponse.put("agentid", "UI_Displayer");
            updatedResponse.put("answer", originalAnswer);
            updatedResponse.put("version", "v2_edited");
            updatedResponse.put("edited_timestamp", System.currentTimeMillis());
            updatedResponse.put("ticket_id", ticketId);
            updatedResponse.put("fields_updated_count", updatedCount);

            // Store in MinIO using NEW folder structure
            // We'll use the UIDisplayer stage but create an "edited.json" file in task-docs/
            String taskName = "UIDisplayer";  // Or get from context if available
            int stageNumber = WorkflowStageMapping.getStageNumber(workflowKey, taskName);

            if (stageNumber == -1) {
                stageNumber = 11;  // Default UIDisplayer stage
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