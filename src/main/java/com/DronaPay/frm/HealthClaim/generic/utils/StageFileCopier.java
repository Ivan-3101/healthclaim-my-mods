package com.DronaPay.frm.HealthClaim.generic.utils;

import com.DronaPay.frm.HealthClaim.generic.storage.StorageProvider;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.model.bpmn.instance.FlowNode;
import org.cibseven.bpm.model.bpmn.instance.Gateway;
import org.cibseven.bpm.model.bpmn.instance.ServiceTask;
import org.cibseven.bpm.model.bpmn.instance.UserTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;



public class StageFileCopier {

    private static final Logger logger = LoggerFactory.getLogger(StageFileCopier.class);

    public static void copyToNextStage(DelegateExecution execution,
                                       StorageProvider storageProvider,
                                       Collection<String> sourcePaths,
                                       int tenantId,
                                       String workflowKey,
                                       String ticketId) {

        if (sourcePaths == null || sourcePaths.isEmpty()) {
            logger.debug("No files to copy");
            return;
        }

        String nextTaskName = getNextTaskName(execution);
        if (nextTaskName == null) {
            logger.warn("No next task found - skipping file copy");
            return;
        }

        int currentStage = StageNumberResolver.resolveStageNumber(execution);
        int nextStage = currentStage + 1;

        logger.info("Copying {} files to stage {} ({})", sourcePaths.size(), nextStage, nextTaskName);

        for (String sourcePath : sourcePaths) {
            try {
                String filename = sourcePath.substring(sourcePath.lastIndexOf('/') + 1);
                String destPath = String.format("insurance-claims/%d/%s/%s/%d_%s/userdoc/uploaded/%s",
                        tenantId, workflowKey, ticketId, nextStage, nextTaskName, filename);

                storageProvider.copyFile(sourcePath, destPath);
                logger.debug("Copied: {}", filename);
            } catch (Exception e) {
                logger.error("Copy failed for {}: {}", sourcePath, e.getMessage());
            }
        }
    }

    private static String getNextTaskName(DelegateExecution execution) {
        try {
            FlowNode current = (FlowNode) execution.getBpmnModelInstance()
                    .getModelElementById(execution.getCurrentActivityId());

            for (var flow : current.getOutgoing()) {
                FlowNode next = flow.getTarget();

                while (next instanceof Gateway) {
                    var flows = next.getOutgoing();
                    if (flows.isEmpty()) break;
                    next = flows.iterator().next().getTarget();
                }

                if (next instanceof ServiceTask || next instanceof UserTask) {
                    return next.getName();
                }
            }
        } catch (Exception e) {
            logger.error("Error getting next task: {}", e.getMessage());
        }
        return null;
    }
}