package com.DronaPay.frm.HealthClaim.generic.storage;

import lombok.extern.slf4j.Slf4j;
import org.cibseven.bpm.engine.delegate.DelegateExecution;

/**
 * Centralized utility for building MinIO storage paths following the new folder structure.
 *
 * Structure: insurance-claims/{tenantId}/{WorkflowType}/{TicketID}/{stage#}_{TaskName}/{folderType}/
 *
 * Folder Types:
 * - userdoc/uploaded/   - Always present (inputs to stage)
 * - userdoc/processed/  - Optional (when documents are transformed/split)
 * - task-docs/          - Only for agent stages (agent outputs)
 */
@Slf4j
public class StoragePathBuilder {

    // Folder type constants
    public static final String USERDOC_FOLDER = "userdoc";
    public static final String UPLOADED_SUBFOLDER = "uploaded";
    public static final String PROCESSED_SUBFOLDER = "processed";
    public static final String TASK_DOCS_FOLDER = "task-docs";

    // Stage number mapping for HealthClaim workflow
    // Maps BPMN task name to stage number
    private static final java.util.Map<String, Integer> HEALTH_CLAIM_STAGES = new java.util.HashMap<>();

    static {
        // Initialize HealthClaim stage mapping
        HEALTH_CLAIM_STAGES.put("GenerateTicketIDAndWorkflowName", 1);
        HEALTH_CLAIM_STAGES.put("VerifyMasterData", 2);
        HEALTH_CLAIM_STAGES.put("IdentifyForgedDocuments", 3);
        HEALTH_CLAIM_STAGES.put("DocumentClassifier", 4);
        HEALTH_CLAIM_STAGES.put("DocTypeSplitter", 5);
        HEALTH_CLAIM_STAGES.put("OCROnDoc", 6);
        HEALTH_CLAIM_STAGES.put("OcrToStatic", 7);
        HEALTH_CLAIM_STAGES.put("FHIRConsolidator", 8);
        HEALTH_CLAIM_STAGES.put("SubmissionValidator", 9);
        HEALTH_CLAIM_STAGES.put("FHIRAnalyser", 10);
        HEALTH_CLAIM_STAGES.put("UIDisplayer", 11);
        HEALTH_CLAIM_STAGES.put("LoadUIFields", 12);
        HEALTH_CLAIM_STAGES.put("VerifyExtractedInfo", 13);
        HEALTH_CLAIM_STAGES.put("PolicyCoherence", 14);
        HEALTH_CLAIM_STAGES.put("MedicalCoherence", 15);
        HEALTH_CLAIM_STAGES.put("LoadFinalReviewFields", 16);
        HEALTH_CLAIM_STAGES.put("FinalReviewTask", 17);
        HEALTH_CLAIM_STAGES.put("FWADecisioning", 18);
        HEALTH_CLAIM_STAGES.put("ClaimCostComputation", 19);
    }

    /**
     * Get stage number for a task name
     */
    public static int getStageNumber(String taskName, String workflowKey) {
        if ("HealthClaim".equals(workflowKey)) {
            return HEALTH_CLAIM_STAGES.getOrDefault(taskName, 99);
        }
        // Future: Add mappings for MotorClaim, PropertyClaim, etc.
        return 99; // Default for unmapped tasks
    }

    /**
     * Get current task name from BPMN execution context
     */
    public static String getCurrentTaskName(DelegateExecution execution) {
        try {
            return execution.getCurrentActivityName();
        } catch (Exception e) {
            log.warn("Could not get current activity name, returning unknown", e);
            return "Unknown";
        }
    }

    /**
     * Build base path for a stage: insurance-claims/{tenantId}/{WorkflowType}/{TicketID}/{stage#}_{TaskName}/
     */
    public static String buildStagePath(String tenantId, String workflowKey, String ticketId,
                                        String taskName) {
        int stageNumber = getStageNumber(taskName, workflowKey);
        return String.format("insurance-claims/%s/%s/%s/%d_%s/",
                tenantId, workflowKey, ticketId, stageNumber, taskName);
    }

    /**
     * Build path for userdoc/uploaded/ (inputs to stage)
     * Pattern: insurance-claims/{tenantId}/{WorkflowType}/{TicketID}/{stage#}_{TaskName}/userdoc/uploaded/{filename}
     */
    public static String buildUserUploadPath(String tenantId, String workflowKey, String ticketId,
                                             String taskName, String filename) {
        String stagePath = buildStagePath(tenantId, workflowKey, ticketId, taskName);
        return stagePath + USERDOC_FOLDER + "/" + UPLOADED_SUBFOLDER + "/" + filename;
    }

    /**
     * Build path for userdoc/processed/ (transformed/split documents)
     * Pattern: insurance-claims/{tenantId}/{WorkflowType}/{TicketID}/{stage#}_{TaskName}/userdoc/processed/{filename}
     */
    public static String buildUserProcessedPath(String tenantId, String workflowKey, String ticketId,
                                                String taskName, String filename) {
        String stagePath = buildStagePath(tenantId, workflowKey, ticketId, taskName);
        return stagePath + USERDOC_FOLDER + "/" + PROCESSED_SUBFOLDER + "/" + filename;
    }

    /**
     * Build path for task-docs/ (agent outputs)
     * Pattern: insurance-claims/{tenantId}/{WorkflowType}/{TicketID}/{stage#}_{TaskName}/task-docs/{filename}
     */
    public static String buildTaskDocsPath(String tenantId, String workflowKey, String ticketId,
                                           String taskName, String filename) {
        String stagePath = buildStagePath(tenantId, workflowKey, ticketId, taskName);
        return stagePath + TASK_DOCS_FOLDER + "/" + filename;
    }

    /**
     * Build path from execution context - automatically determines task name and stage
     */
    public static String buildPathFromExecution(DelegateExecution execution, String folderType,
                                                String filename) {
        String tenantId = execution.getTenantId();
        String workflowKey = (String) execution.getVariable("WorkflowKey");
        if (workflowKey == null) {
            workflowKey = "HealthClaim"; // Fallback
        }
        String ticketId = String.valueOf(execution.getVariable("TicketID"));
        String taskName = getCurrentTaskName(execution);

        switch (folderType) {
            case UPLOADED_SUBFOLDER:
                return buildUserUploadPath(tenantId, workflowKey, ticketId, taskName, filename);
            case PROCESSED_SUBFOLDER:
                return buildUserProcessedPath(tenantId, workflowKey, ticketId, taskName, filename);
            case TASK_DOCS_FOLDER:
                return buildTaskDocsPath(tenantId, workflowKey, ticketId, taskName, filename);
            default:
                throw new IllegalArgumentException("Unknown folder type: " + folderType);
        }
    }

    /**
     * Check if a task is an agent task (should have task-docs folder)
     */
    public static boolean isAgentTask(String taskName) {
        return taskName.contains("Identify") ||
                taskName.contains("Classifier") ||
                taskName.contains("OCR") ||
                taskName.contains("OcrToStatic") ||
                taskName.contains("Validator") ||
                taskName.contains("Analyser") ||
                taskName.contains("Analyzer") ||
                taskName.contains("Displayer") ||
                taskName.contains("Coherence") ||
                taskName.contains("Comparator");
    }

    /**
     * Get previous stage's path for reading outputs
     * Used when current stage needs to read from previous stage's outputs
     */
    public static String getPreviousStageOutputPath(String tenantId, String workflowKey, String ticketId,
                                                    String currentTaskName, String filename,
                                                    boolean fromProcessed) {
        int currentStage = getStageNumber(currentTaskName, workflowKey);
        String previousTaskName = getTaskNameByStage(currentStage - 1, workflowKey);

        if (fromProcessed) {
            return buildUserProcessedPath(tenantId, workflowKey, ticketId, previousTaskName, filename);
        } else {
            // Check if previous was an agent task
            if (isAgentTask(previousTaskName)) {
                return buildTaskDocsPath(tenantId, workflowKey, ticketId, previousTaskName, filename);
            } else {
                return buildUserUploadPath(tenantId, workflowKey, ticketId, previousTaskName, filename);
            }
        }
    }

    /**
     * Get task name by stage number (reverse lookup)
     */
    private static String getTaskNameByStage(int stageNumber, String workflowKey) {
        if ("HealthClaim".equals(workflowKey)) {
            for (java.util.Map.Entry<String, Integer> entry : HEALTH_CLAIM_STAGES.entrySet()) {
                if (entry.getValue() == stageNumber) {
                    return entry.getKey();
                }
            }
        }
        return "Unknown";
    }

    /**
     * Copy documents from one stage to another
     * Used at the start of each stage to copy inputs to userdoc/uploaded/
     */
    public static void copyToStageInput(com.DronaPay.frm.HealthClaim.generic.storage.StorageProvider storage,
                                        String sourcePath, String targetPath) throws Exception {
        log.debug("Copying from {} to {}", sourcePath, targetPath);

        // Download from source
        java.io.InputStream sourceStream = storage.downloadDocument(sourcePath);
        byte[] content = sourceStream.readAllBytes();
        sourceStream.close();

        // Upload to target
        storage.uploadDocument(targetPath, content, "application/octet-stream");

        log.debug("Successfully copied to stage input");
    }
}