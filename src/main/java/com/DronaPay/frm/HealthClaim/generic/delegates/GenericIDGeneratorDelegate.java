package com.DronaPay.frm.HealthClaim.generic.delegates;

import com.DronaPay.frm.HealthClaim.generic.services.ConfigurationService;
import com.DronaPay.frm.HealthClaim.generic.services.DocumentProcessingService;
import lombok.extern.slf4j.Slf4j;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.Expression;
import org.cibseven.bpm.engine.delegate.JavaDelegate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;

@Slf4j
public class GenericIDGeneratorDelegate implements JavaDelegate {

    // Field Injection: These values come from the BPMN Element Template
    private Expression workflowKey;      // Required (e.g., "MotorClaim")
    private Expression docsVariableName; // Optional (defaults to "docs")
    private Expression expiryDuration;   // Optional (defaults to config file)

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("=== Generic ID Generator Started ===");

        // 1. Resolve Workflow Key (Critical)
        String workflowKeyStr = (workflowKey != null) ? (String) workflowKey.getValue(execution) : null;
        if (workflowKeyStr == null || workflowKeyStr.trim().isEmpty()) {
            throw new RuntimeException("GenericIDGenerator: 'workflowKey' field is missing in BPMN. Please configure the element template.");
        }

        // --- FIX ADDED HERE ---
        // Saving this as a Process Variable
        execution.setVariable("workflowKey", workflowKeyStr);
        log.info("Set process variable 'workflowKey' to: {}", workflowKeyStr);
        // ----------------------

        // 2. Resolve Docs Variable Name (Default to "docs")
        String docsVarNameStr = (docsVariableName != null) ? (String) docsVariableName.getValue(execution) : "docs";
        if (docsVarNameStr == null || docsVarNameStr.trim().isEmpty()) {
            docsVarNameStr = "docs";
        }

        String tenantId = execution.getTenantId();
        String processInstanceId = execution.getProcessInstanceId();

        // 3. Generate Ticket ID (Database logic remains same)
        long ticketId = generateTicketID(execution, processInstanceId);
        execution.setVariable("TicketID", ticketId);
        log.info("Generated TicketID: {} for Workflow: {}", ticketId, workflowKeyStr);

        // 4. Process Documents (Using injected variable names)
        String stageName = execution.getCurrentActivityId();
        Object docsObject = execution.getVariable(docsVarNameStr);

        // Uses the injected 'workflowKeyStr' for the folder path
        Map<String, String> documentPaths = DocumentProcessingService.processAndUploadDocuments(
                docsObject, tenantId, workflowKeyStr, String.valueOf(ticketId), stageName
        );

        List<String> attachmentVars = new ArrayList<>(documentPaths.keySet());
        execution.setVariable("attachmentVars", attachmentVars);
        log.info("Set {} attachments for processing", attachmentVars.size());

        execution.setVariable("documentPaths", documentPaths);

        Map<String, Map<String, Object>> fileProcessMap =
                DocumentProcessingService.initializeFileProcessMap(documentPaths.keySet());
        execution.setVariable("fileProcessMap", fileProcessMap);

        // 5. Handle Expiry Duration (Template > Config File > Default)
        String finalExpiry = "24h"; // Absolute fallback
        try {
            // Check if template provided a value
            String templateExpiry = (expiryDuration != null) ? (String) expiryDuration.getValue(execution) : null;

            if (templateExpiry != null && !templateExpiry.trim().isEmpty()) {
                finalExpiry = templateExpiry;
                log.info("Using Expiry Duration from BPMN Template: {}", finalExpiry);
            } else {
                // Fallback to property file
                Properties props = ConfigurationService.getTenantProperties(tenantId);
                finalExpiry = props.getProperty("expiry.duration", "24h");
                log.debug("Using Expiry Duration from properties: {}", finalExpiry);
            }
        } catch (Exception e) {
            log.warn("Could not determine expiry duration, using default: {}", finalExpiry);
        }

        execution.setVariable("timeinterval", finalExpiry);
        log.info("=== Generic ID Generator Completed Successfully ===");
    }

    private long generateTicketID(DelegateExecution execution, String processInstanceId) throws Exception {
        Connection conn = null;
        try {
            conn = execution.getProcessEngine()
                    .getProcessEngineConfiguration()
                    .getDataSource()
                    .getConnection();

            PreparedStatement insertStmt = conn.prepareStatement(
                    "INSERT INTO ui.ticketidgenerator(processinstanceid) VALUES (?)"
            );
            insertStmt.setString(1, processInstanceId);
            insertStmt.executeUpdate();

            PreparedStatement selectStmt = conn.prepareStatement(
                    "SELECT ticketid FROM ui.ticketidgenerator WHERE processinstanceid = ?"
            );
            selectStmt.setString(1, processInstanceId);

            ResultSet rs = selectStmt.executeQuery();
            if (rs.next()) {
                return rs.getLong("ticketid");
            } else {
                throw new RuntimeException("Failed to generate ticket ID");
            }

        } finally {
            if (conn != null) {
                conn.close();
            }
        }
    }
}