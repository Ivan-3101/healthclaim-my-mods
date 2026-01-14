package com.DronaPay.frm.HealthClaim.generic.delegates;

import com.DronaPay.frm.HealthClaim.generic.services.ConfigurationService;
import com.DronaPay.frm.HealthClaim.generic.services.DocumentProcessingService;
import lombok.extern.slf4j.Slf4j;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;

@Slf4j
public class GenericIDGeneratorDelegate implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("=== Generic ID Generator Started ===");

        String tenantId = execution.getTenantId();
        String processInstanceId = execution.getProcessInstanceId();

        long ticketId = generateTicketID(execution, processInstanceId);
        execution.setVariable("TicketID", ticketId);
        log.info("Generated TicketID: {}", ticketId);

        String workflowKey = "HealthClaim";
        String stageName = "Generate_TicketID_and_Workflow_Name";

        Object docsObject = execution.getVariable("docs");
        Map<String, String> documentPaths = DocumentProcessingService.processAndUploadDocuments(
                docsObject, tenantId, workflowKey, String.valueOf(ticketId), stageName
        );

        List<String> attachmentVars = new ArrayList<>(documentPaths.keySet());
        execution.setVariable("attachmentVars", attachmentVars);
        log.info("Set {} attachments for processing: {}", attachmentVars.size(), attachmentVars);

        execution.setVariable("documentPaths", documentPaths);
        log.debug("Document paths: {}", documentPaths);

        Map<String, Map<String, Object>> fileProcessMap =
                DocumentProcessingService.initializeFileProcessMap(documentPaths.keySet());
        execution.setVariable("fileProcessMap", fileProcessMap);

        try {
            Properties props = ConfigurationService.getTenantProperties(tenantId);
            String expiryDuration = props.getProperty("expiry.duration", "24h");
            execution.setVariable("timeinterval", expiryDuration);
            log.debug("Set expiry duration: {}", expiryDuration);
        } catch (Exception e) {
            log.warn("Could not load expiry duration, using default", e);
            execution.setVariable("timeinterval", "24h");
        }

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