package com.DronaPay.frm.HealthClaim.generic.delegates;

import com.DronaPay.frm.HealthClaim.generic.config.WorkflowStageMapping;
import com.DronaPay.frm.HealthClaim.generic.services.ConfigurationService;
import com.DronaPay.frm.HealthClaim.generic.services.ObjectStorageService;
import com.DronaPay.frm.HealthClaim.generic.storage.StorageProvider;
import com.DronaPay.frm.HealthClaim.generic.utils.StoragePathBuilder;
import lombok.extern.slf4j.Slf4j;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Helper delegate to copy documents from previous stage to current stage's userdoc/uploaded/
 *
 * Used for stages that need documents as input but don't transform them.
 * Examples: VerifyMasterData, LoadUIFields, LoadFinalReviewFields
 */
@Slf4j
public class StageDocumentCopierDelegate implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("=== Stage Document Copier Started ===");

        String tenantId = execution.getTenantId();
        String ticketId = String.valueOf(execution.getVariable("TicketID"));
        String workflowKey = StoragePathBuilder.getWorkflowType(execution);
        String taskName = StoragePathBuilder.getTaskName(execution);
        int stageNumber = WorkflowStageMapping.getStageNumber(workflowKey, taskName);

        if (stageNumber == -1) {
            log.warn("Stage number not found for task '{}', using previous + 1", taskName);
            stageNumber = StoragePathBuilder.getStageNumber(execution) + 1;
        }

        execution.setVariable("stageNumber", stageNumber);
        log.info("Stage {}: {}", stageNumber, taskName);

        @SuppressWarnings("unchecked")
        Map<String, String> documentPaths = (Map<String, String>) execution.getVariable("documentPaths");

        if (documentPaths == null || documentPaths.isEmpty()) {
            log.warn("No documents to copy");
            return;
        }

        StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);
        Properties props = ConfigurationService.getTenantProperties(tenantId);
        String rootFolder = props.getProperty("storage.minio.bucketName", "insurance-claims");

        // Determine which documents to copy (could be from attachmentVars or splitDocumentVars)
        List<String> documentsToProcess = new ArrayList<>();

        Object attachmentVars = execution.getVariable("attachmentVars");
        Object splitDocumentVars = execution.getVariable("splitDocumentVars");

        if (splitDocumentVars != null) {
            documentsToProcess = (List<String>) splitDocumentVars;
            log.info("Copying {} split documents", documentsToProcess.size());
        } else if (attachmentVars != null) {
            documentsToProcess = (List<String>) attachmentVars;
            log.info("Copying {} original documents", documentsToProcess.size());
        }

        // Copy each document to current stage's userdoc/uploaded/
        for (String filename : documentsToProcess) {
            String previousPath = documentPaths.get(filename);

            if (previousPath == null) {
                log.warn("No previous path found for {}, skipping", filename);
                continue;
            }

            // Download from previous location
            try (InputStream docStream = storage.downloadDocument(previousPath)) {
                byte[] content = docStream.readAllBytes();

                // Upload to current stage's userdoc/uploaded/
                String newPath = StoragePathBuilder.buildUserUploadPath(
                        rootFolder, tenantId, workflowKey, ticketId,
                        stageNumber, taskName, filename
                );

                storage.uploadDocument(newPath, content, guessMimeType(filename));

                // Update documentPaths map
                documentPaths.put(filename, newPath);

                log.debug("Copied {} from {} to {}", filename, previousPath, newPath);
            }
        }

        // Update process variable
        execution.setVariable("documentPaths", documentPaths);

        log.info("=== Stage Document Copier Completed: {} documents copied ===",
                documentsToProcess.size());
    }

    private String guessMimeType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        return "application/octet-stream";
    }
}