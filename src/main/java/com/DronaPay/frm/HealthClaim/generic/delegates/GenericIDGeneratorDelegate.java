package com.DronaPay.frm.HealthClaim.generic.delegates;

import com.DronaPay.frm.HealthClaim.generic.config.WorkflowStageMapping;
import com.DronaPay.frm.HealthClaim.generic.services.ConfigurationService;
import com.DronaPay.frm.HealthClaim.generic.services.ObjectStorageService;
import com.DronaPay.frm.HealthClaim.generic.storage.StorageProvider;
import com.DronaPay.frm.HealthClaim.generic.utils.StoragePathBuilder;
import lombok.extern.slf4j.Slf4j;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.json.JSONArray;
import org.json.JSONObject;

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

        // 1. Generate Ticket ID from database sequence
        long ticketId = generateTicketID(execution, processInstanceId);
        execution.setVariable("TicketID", ticketId);
        log.info("Generated TicketID: {}", ticketId);

        // 2. Get workflow key
        String workflowKey = "HealthClaim"; // TODO: Make configurable
        execution.setVariable("workflowKey", workflowKey);

        // 3. Get stage number for this task
        String taskName = StoragePathBuilder.getTaskName(execution);
        int stageNumber = WorkflowStageMapping.getStageNumber(workflowKey, taskName);

        if (stageNumber == -1) {
            log.warn("Stage number not found for task '{}', using stage 1", taskName);
            stageNumber = 1;
        }

        execution.setVariable("stageNumber", stageNumber);
        log.info("Stage {}: {}", stageNumber, taskName);

        // 4. Process documents and upload to object storage with new folder structure
        Object docsObject = execution.getVariable("docs");
        Map<String, String> documentPaths = processAndUploadDocumentsNewStructure(
                docsObject, tenantId, workflowKey, String.valueOf(ticketId), stageNumber, taskName
        );

        // 5. Set document paths for multi-instance loop
        List<String> attachmentVars = new ArrayList<>(documentPaths.keySet());
        execution.setVariable("attachmentVars", attachmentVars);
        log.info("Set {} attachments for processing: {}", attachmentVars.size(), attachmentVars);

        // 6. Store document paths map for later retrieval
        execution.setVariable("documentPaths", documentPaths);
        log.debug("Document paths: {}", documentPaths);

        // 7. Initialize file process map
        Map<String, Map<String, Object>> fileProcessMap = initializeFileProcessMap(documentPaths.keySet());
        execution.setVariable("fileProcessMap", fileProcessMap);

        // 8. Load tenant-specific expiry duration (optional config)
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

    /**
     * Process documents and upload to object storage with NEW folder structure
     *
     * Pattern: {rootFolder}/{tenantId}/{workflowType}/{ticketId}/{stageNumber}_{taskName}/userdoc/uploaded/{filename}
     */
    private Map<String, String> processAndUploadDocumentsNewStructure(
            Object docsObject, String tenantId, String workflowKey,
            String ticketId, int stageNumber, String taskName) {

        Map<String, String> documentPaths = new HashMap<>();

        if (docsObject == null) {
            log.warn("No documents provided in 'docs' variable");
            return documentPaths;
        }

        try {
            // Get storage provider and root folder
            StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);
            Properties props = ConfigurationService.getTenantProperties(tenantId);
            String rootFolder = props.getProperty("storage.minio.bucketName", "insurance-claims");

            // Convert docs to list
            List<Map<String, Object>> docsList = convertToDocsList(docsObject);

            for (Map<String, Object> doc : docsList) {
                String filename = doc.get("filename").toString();
                String mimetype = doc.get("mimetype").toString();
                String base64Content = doc.get("content").toString();

                // Decode base64 content
                byte[] fileContent = Base64.getDecoder().decode(base64Content);

                // Build storage path using new structure
                String storagePath = StoragePathBuilder.buildUserUploadPath(
                        rootFolder, tenantId, workflowKey, ticketId,
                        stageNumber, taskName, filename
                );

                // Upload to storage
                storage.uploadDocument(storagePath, fileContent, mimetype);
                documentPaths.put(filename, storagePath);

                log.info("Uploaded document: {} -> {} ({} bytes)",
                        filename, storagePath, fileContent.length);
            }

            log.info("Successfully uploaded {} documents to storage", documentPaths.size());

        } catch (Exception e) {
            log.error("Error processing and uploading documents", e);
            throw new RuntimeException("Failed to upload documents", e);
        }

        return documentPaths;
    }

    /**
     * Convert various input formats to List<Map<String, Object>>
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> convertToDocsList(Object docsObject) {
        if (docsObject instanceof List) {
            return (List<Map<String, Object>>) docsObject;
        } else if (docsObject instanceof String) {
            JSONArray jsonArray = new JSONArray((String) docsObject);
            List<Map<String, Object>> result = new ArrayList<>();
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject jsonDoc = jsonArray.getJSONObject(i);
                Map<String, Object> docMap = new HashMap<>();
                docMap.put("filename", jsonDoc.getString("filename"));
                docMap.put("mimetype", jsonDoc.getString("mimetype"));
                docMap.put("encoding", jsonDoc.getString("encoding"));
                docMap.put("content", jsonDoc.getString("content"));
                result.add(docMap);
            }
            return result;
        }
        return new ArrayList<>();
    }

    /**
     * Initialize file process map for tracking document processing results
     */
    private Map<String, Map<String, Object>> initializeFileProcessMap(Set<String> filenames) {
        Map<String, Map<String, Object>> fileProcessMap = new HashMap<>();
        for (String filename : filenames) {
            fileProcessMap.put(filename, new HashMap<>());
        }
        log.debug("Initialized file process map for {} files", filenames.size());
        return fileProcessMap;
    }

    /**
     * Generate unique ticket ID using database sequence
     */
    private long generateTicketID(DelegateExecution execution, String processInstanceId) throws Exception {
        Connection conn = null;
        try {
            conn = execution.getProcessEngine()
                    .getProcessEngineConfiguration()
                    .getDataSource()
                    .getConnection();

            // Insert process instance ID
            PreparedStatement insertStmt = conn.prepareStatement(
                    "INSERT INTO ui.ticketidgenerator(processinstanceid) VALUES (?)"
            );
            insertStmt.setString(1, processInstanceId);
            insertStmt.executeUpdate();

            // Retrieve generated ticket ID
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