package com.DronaPay.frm.HealthClaim;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.camunda.bpm.engine.variable.Variables;
import org.camunda.bpm.engine.variable.value.FileValue;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GenerateIDAndWorkflowName implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.debug("entered class " + GenerateIDAndWorkflowName.class + " method execute");

        try {
            execution.setVariable("TicketID", getGeneratedID(execution));
            log.info("ticket id generated successfully");
        } catch (Exception e) {
            log.error("Error : " + e + "\nParam : " + execution);
            throw e;
        }

        List<String> attachmentVars = new ArrayList<>();
//        for (String varnam : execution.getVariableNames()) {
//
//            try {
//                FileValue file = execution.getVariableTyped(varnam);
//                log.info("var is file " + varnam);
//                attachmentVars.add(varnam);
//            } catch (ClassCastException e) {
//
//            }
//
//        }
        Object docsObj = execution.getVariable("docs");
        if (docsObj instanceof List) {
            List<Map<String, Object>> docs = (List<Map<String, Object>>) docsObj;

            for (Map<String, Object> doc : docs) {
                String filename = doc.get("filename").toString();
                String mimetype = doc.get("mimetype").toString();
                String encoding = doc.get("encoding").toString();
                String base64Content = doc.get("content").toString();

                byte[] fileContent = Base64.getDecoder().decode(base64Content);

                FileValue fileValue = Variables.fileValue(filename)
                        .file(fileContent)
                        .mimeType(mimetype)
                        .encoding(encoding)
                        .create();

                // 🔑 Recreate variables as before
                execution.setVariable(filename, fileValue);
                attachmentVars.add(filename);
            }
        }

        Map<String, Map<String, Object>> fileProcessMap = new HashMap<>();

        execution.setVariable("attachmentVars", attachmentVars);
        execution.setVariable("fileProcessMap", fileProcessMap);

        log.debug("exiting class " + GenerateIDAndWorkflowName.class + " method execute");

    }

    public long getGeneratedID(DelegateExecution execution) throws Exception {
        log.debug("entered class " + GenerateIDAndWorkflowName.class + " method getGeneratedID");
        Properties props = new Properties();
        props.load(GenerateIDAndWorkflowName.class.getClassLoader()
                .getResourceAsStream("application.properties_" + execution.getTenantId()));
        execution.setVariable("timeinterval", props.getProperty("expiry.duration"));

        long myId = 0;
        Connection conn = execution.getProcessEngine().getProcessEngineConfiguration().getDataSource().getConnection();
        try {
            PreparedStatement insertStatement = conn
                    .prepareStatement("INSERT INTO ui.ticketidgenerator(processinstanceid)VALUES (?);");
            insertStatement.setString(1, execution.getProcessInstanceId());
            insertStatement.executeUpdate();
            PreparedStatement selectStatement = conn
                    .prepareStatement("Select ticketid from ui.ticketidgenerator where processinstanceid = ? ;");
            selectStatement.setString(1, execution.getProcessInstanceId());

            ResultSet rs = selectStatement.executeQuery();
            if (rs.next())
                myId = rs.getLong(1);
            else
                throw new NullPointerException("Failed to retrieve id");
            conn.close();
        } catch (SQLException e) {
            log.error("Error : " + e);
            throw e;
        } finally {
            conn.close();
        }
        log.debug("exiting class " + GenerateIDAndWorkflowName.class + " method getGeneratedID");
        return myId;
    }

}
