package com.DronaPay.frm.HealthClaim.generic.utils;

import lombok.extern.slf4j.Slf4j;
import org.cibseven.bpm.engine.delegate.DelegateExecution;

/**
 * Stage Helper Utility
 * Automatically manages stage numbers for MinIO folder structure
 * Pattern: {tenantId}/{workflowKey}/{ticketId}/{stage#}_{TaskName}/
 */
@Slf4j
public class StageHelper {

    /**
     * Get or increment stage number
     * - First task: Returns 1
     * - Sequential tasks: Returns previous + 1
     * - Multi-instance loop iterations: Returns same stage number
     */
    public static int getOrIncrementStage(DelegateExecution execution) {
        // Check if we're in a multi-instance loop
        Object loopCounter = execution.getVariable("loopCounter");

        if (loopCounter != null) {
            // Inside loop - use existing stage number (don't increment)
            int currentStage = getCurrentStage(execution);
            log.debug("Multi-instance loop detected (iteration {}), using stage: {}",
                    loopCounter, currentStage);
            return currentStage;
        }

        // Not in loop - increment stage
        int currentStage = getCurrentStage(execution);
        int nextStage = currentStage + 1;
        execution.setVariable("currentStageNumber", nextStage);

        log.debug("Stage incremented: {} -> {}", currentStage, nextStage);
        return nextStage;
    }

    /**
     * Get current stage number from process variable
     */
    private static int getCurrentStage(DelegateExecution execution) {
        Object stage = execution.getVariable("currentStageNumber");
        if (stage == null) {
            return 0;
        }

        if (stage instanceof Integer) {
            return (Integer) stage;
        } else if (stage instanceof Long) {
            return ((Long) stage).intValue();
        } else if (stage instanceof String) {
            try {
                return Integer.parseInt((String) stage);
            } catch (NumberFormatException e) {
                log.warn("Invalid stage number format: {}, returning 0", stage);
                return 0;
            }
        }

        return 0;
    }

    /**
     * Sanitize task name for folder paths
     * Removes special characters, replaces spaces with underscores
     */
    public static String sanitizeTaskName(String taskName) {
        if (taskName == null || taskName.trim().isEmpty()) {
            return "UnknownTask";
        }

        // Replace non-alphanumeric chars with underscores
        return taskName.replaceAll("[^a-zA-Z0-9]", "_")
                .replaceAll("_{2,}", "_")    // Replace multiple underscores with single
                .replaceAll("^_|_$", "");    // Remove leading/trailing underscores
    }

    /**
     * Build stage folder name: {stage#}_{TaskName}
     */
    public static String buildStageFolderName(int stageNumber, String taskName) {
        return stageNumber + "_" + sanitizeTaskName(taskName);
    }
}