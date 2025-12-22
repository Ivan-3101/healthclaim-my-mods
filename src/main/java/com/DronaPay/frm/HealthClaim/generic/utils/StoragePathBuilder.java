package com.DronaPay.frm.HealthClaim.generic.utils;

import lombok.extern.slf4j.Slf4j;
import org.cibseven.bpm.engine.delegate.DelegateExecution;

/**
 * Storage Path Builder for new numbered, stage-based MinIO folder structure
 *
 * Structure: {rootFolder}/{tenantId}/{WorkflowType}/{TicketID}/{stage#}_{TaskName}/...
 *
 * Folder types:
 * - userdoc/uploaded/    : ALWAYS present (inputs to this stage)
 * - userdoc/processed/   : OPTIONAL (only when docs are transformed)
 * - task-docs/           : ONLY for agent stages (agent outputs)
 */
@Slf4j
public class StoragePathBuilder {

    // Folder constants
    private static final String USERDOC_FOLDER = "userdoc";
    private static final String UPLOADED_SUBFOLDER = "uploaded";
    private static final String PROCESSED_SUBFOLDER = "processed";
    private static final String TASK_DOCS_FOLDER = "task-docs";

    /**
     * Build path to userdoc/uploaded/ (inputs to this stage)
     *
     * Pattern: {rootFolder}/{tenantId}/{workflowType}/{ticketId}/{stageNumber}_{taskName}/userdoc/uploaded/{filename}
     */
    public static String buildUserUploadPath(String rootFolder, String tenantId, String workflowType,
                                             String ticketId, int stageNumber, String taskName, String filename) {

        String stageFolderName = stageNumber + "_" + sanitizeTaskName(taskName);

        return String.format("%s/%s/%s/%s/%s/%s/%s/%s",
                rootFolder, tenantId, workflowType, ticketId,
                stageFolderName, USERDOC_FOLDER, UPLOADED_SUBFOLDER, filename);
    }

    /**
     * Build path to userdoc/processed/ (transformed/split documents)
     *
     * Pattern: {rootFolder}/{tenantId}/{workflowType}/{ticketId}/{stageNumber}_{taskName}/userdoc/processed/{filename}
     */
    public static String buildUserProcessedPath(String rootFolder, String tenantId, String workflowType,
                                                String ticketId, int stageNumber, String taskName, String filename) {

        String stageFolderName = stageNumber + "_" + sanitizeTaskName(taskName);

        return String.format("%s/%s/%s/%s/%s/%s/%s/%s",
                rootFolder, tenantId, workflowType, ticketId,
                stageFolderName, USERDOC_FOLDER, PROCESSED_SUBFOLDER, filename);
    }

    /**
     * Build path to task-docs/ (agent outputs)
     *
     * Pattern: {rootFolder}/{tenantId}/{workflowType}/{ticketId}/{stageNumber}_{taskName}/task-docs/{filename}
     */
    public static String buildTaskDocsPath(String rootFolder, String tenantId, String workflowType,
                                           String ticketId, int stageNumber, String taskName, String filename) {

        String stageFolderName = stageNumber + "_" + sanitizeTaskName(taskName);

        return String.format("%s/%s/%s/%s/%s/%s/%s",
                rootFolder, tenantId, workflowType, ticketId,
                stageFolderName, TASK_DOCS_FOLDER, filename);
    }

    /**
     * Build base stage path (without filename or folder type)
     *
     * Pattern: {rootFolder}/{tenantId}/{workflowType}/{ticketId}/{stageNumber}_{taskName}
     */
    public static String buildBaseStagePath(String rootFolder, String tenantId, String workflowType,
                                            String ticketId, int stageNumber, String taskName) {

        String stageFolderName = stageNumber + "_" + sanitizeTaskName(taskName);

        return String.format("%s/%s/%s/%s/%s",
                rootFolder, tenantId, workflowType, ticketId, stageFolderName);
    }

    /**
     * Extract stage number from BPMN execution context
     * Uses the "stageNumber" variable set in BPMN or delegate
     *
     * @param execution - Delegate execution context
     * @return Stage number (defaults to 0 if not found)
     */
    public static int getStageNumber(DelegateExecution execution) {
        Object stageObj = execution.getVariable("stageNumber");
        if (stageObj instanceof Integer) {
            return (Integer) stageObj;
        } else if (stageObj instanceof Long) {
            return ((Long) stageObj).intValue();
        } else if (stageObj instanceof String) {
            try {
                return Integer.parseInt((String) stageObj);
            } catch (NumberFormatException e) {
                log.warn("Invalid stageNumber format: {}", stageObj);
            }
        }

        log.warn("stageNumber variable not found or invalid, defaulting to 0");
        return 0;
    }

    /**
     * Get current BPMN task name from execution context
     */
    public static String getTaskName(DelegateExecution execution) {
        String taskName = execution.getCurrentActivityName();
        if (taskName == null || taskName.trim().isEmpty()) {
            taskName = execution.getCurrentActivityId();
        }
        return taskName;
    }

    /**
     * Sanitize task name for use in folder paths
     * Removes special characters, replaces spaces with underscores
     */
    private static String sanitizeTaskName(String taskName) {
        if (taskName == null) {
            return "UnknownTask";
        }
        // Replace spaces and special chars with underscores, keep alphanumeric
        return taskName.replaceAll("[^a-zA-Z0-9]", "_")
                .replaceAll("_{2,}", "_")  // Replace multiple underscores with single
                .replaceAll("^_|_$", "");   // Remove leading/trailing underscores
    }

    /**
     * Get workflow type from execution or default to HealthClaim
     */
    public static String getWorkflowType(DelegateExecution execution) {
        Object workflowKey = execution.getVariable("workflowKey");
        if (workflowKey != null) {
            return workflowKey.toString();
        }

        // Try to extract from process definition key
        String processKey = execution.getProcessDefinitionId();
        if (processKey != null && processKey.contains(":")) {
            String key = processKey.substring(0, processKey.indexOf(":"));
            if (key.contains("HealthClaim") || key.contains("health")) {
                return "HealthClaim";
            }
        }

        log.warn("workflowKey variable not found, defaulting to HealthClaim");
        return "HealthClaim";
    }
}