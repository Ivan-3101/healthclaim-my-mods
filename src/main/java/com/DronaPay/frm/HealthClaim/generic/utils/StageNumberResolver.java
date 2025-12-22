package com.DronaPay.frm.HealthClaim.generic.utils;

import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.model.bpmn.instance.FlowNode;
import org.cibseven.bpm.model.bpmn.instance.StartEvent;
import org.cibseven.bpm.model.bpmn.instance.ServiceTask;
import org.cibseven.bpm.model.bpmn.instance.UserTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class StageNumberResolver {

    private static final Logger logger = LoggerFactory.getLogger(StageNumberResolver.class);

    public static int resolveStageNumber(DelegateExecution execution) {
        try {
            String currentActivityId = execution.getCurrentActivityId();
            List<String> orderedActivityIds = getExecutionOrder(execution);

            int index = orderedActivityIds.indexOf(currentActivityId);
            if (index >= 0) {
                int stageNumber = index + 1;
                logger.debug("Resolved stage number {} for activity {}", stageNumber, currentActivityId);
                return stageNumber;
            }
        } catch (Exception e) {
            logger.error("Failed to resolve stage number: {}", e.getMessage());
        }

        logger.warn("Could not resolve stage number, returning 99 as fallback");
        return 99;
    }

    private static List<String> getExecutionOrder(DelegateExecution execution) {
        List<String> order = new ArrayList<>();
        Set<String> visited = new HashSet<>();

        try {
            var modelInstance = execution.getBpmnModelInstance();
            var processes = modelInstance.getModelElementsByType(
                    org.cibseven.bpm.model.bpmn.instance.Process.class);

            if (processes.isEmpty()) return order;

            var process = processes.iterator().next();
            var startEvents = process.getChildElementsByType(StartEvent.class);

            if (!startEvents.isEmpty()) {
                traverseSequence(startEvents.iterator().next(), order, visited);
            }
        } catch (Exception e) {
            logger.error("Error traversing BPMN: {}", e.getMessage());
        }

        return order;
    }

    private static void traverseSequence(FlowNode node, List<String> order, Set<String> visited) {
        if (node == null || visited.contains(node.getId())) {
            return;
        }

        visited.add(node.getId());

        if (node instanceof ServiceTask || node instanceof UserTask) {
            order.add(node.getId());
        }

        for (var flow : node.getOutgoing()) {
            traverseSequence(flow.getTarget(), order, visited);
        }
    }
}