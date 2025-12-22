package com.DronaPay.frm.HealthClaim.generic.services;

import com.DronaPay.frm.HealthClaim.generic.storage.StoragePathBuilder;
import com.DronaPay.frm.HealthClaim.generic.storage.StorageProvider;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;

@Slf4j
public class DocumentProcessingService {

    /**
     * Process documents and upload to MinIO with NEW folder structure
     * Uploads to: insurance-claims/{tenantId}/{WorkflowType}/{TicketID}/1_GenerateTicketIDAndWorkflowName/userdoc/uploaded/
     *
     * @param docsObject - the docs variable from process
     * @param tenantId - tenant ID
     * @param workflowKey - workflow key (e.g., "HealthClaim")
     * @param ticketId - ticket ID
     * @return Map of filename -> storage path
     */
    public static Map<String, String> processAndUploadDocuments(
            Object docsObject, String tenantId, String workflowKey, String ticketId) {

        Map<String, String> documentPaths = new HashMap<>();

        if (docsObject == null) {
            log.warn("No documents provided in 'docs' variable");
            return documentPaths;
        }

        try {
            // Get storage provider
            StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);

            // Convert docs to list
            List<Map<String, Object>> docsList = convertToDocsList(docsObject);

            // Upload to Stage 1 (GenerateTicketIDAndWorkflowName) userdoc/uploaded/
            String taskName = "GenerateTicketIDAndWorkflowName";

            for (Map<String, Object> doc : docsList) {
                String filename = doc.get("filename").toString();
                String mimetype = doc.get("mimetype").toString();
                String base64Content = doc.get("content").toString();

                // Decode base64 content
                byte[] fileContent = Base64.getDecoder().decode(base64Content);

                // Build storage path using new structure
                String storagePath = StoragePathBuilder.buildUserUploadPath(
                        tenantId, workflowKey, ticketId, taskName, filename
                );

                // Upload to storage
                String documentUrl = storage.uploadDocument(storagePath, fileContent, mimetype);

                log.info("Uploaded document '{}' to MinIO: {} ({} bytes)",
                        filename, storagePath, fileContent.length);

                // Store path in map
                documentPaths.put(filename, storagePath);
            }

            log.info("Uploaded {} documents to Stage 1 (initial uploads)", docsList.size());

        } catch (Exception e) {
            log.error("Error processing and uploading documents", e);
            throw new RuntimeException("Failed to upload documents to MinIO", e);
        }

        return documentPaths;
    }

    /**
     * Initialize file process map for tracking agent results
     */
    public static Map<String, Map<String, Object>> initializeFileProcessMap(Set<String> filenames) {
        Map<String, Map<String, Object>> fileProcessMap = new HashMap<>();
        for (String filename : filenames) {
            fileProcessMap.put(filename, new HashMap<>());
        }
        return fileProcessMap;
    }

    /**
     * Convert docs object to list of maps
     */
    private static List<Map<String, Object>> convertToDocsList(Object docsObject) {
        List<Map<String, Object>> docsList = new ArrayList<>();

        try {
            if (docsObject instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> list = (List<Object>) docsObject;
                for (Object item : list) {
                    if (item instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> map = (Map<String, Object>) item;
                        docsList.add(map);
                    }
                }
            } else if (docsObject instanceof String) {
                JSONArray jsonArray = new JSONArray((String) docsObject);
                for (int i = 0; i < jsonArray.length(); i++) {
                    JSONObject jsonDoc = jsonArray.getJSONObject(i);
                    Map<String, Object> docMap = new HashMap<>();
                    docMap.put("filename", jsonDoc.getString("filename"));
                    docMap.put("mimetype", jsonDoc.getString("mimetype"));
                    docMap.put("encoding", jsonDoc.getString("encoding"));
                    docMap.put("content", jsonDoc.getString("content"));
                    docsList.add(docMap);
                }
            }
        } catch (Exception e) {
            log.error("Error converting docs to list", e);
            throw new RuntimeException("Invalid docs format", e);
        }

        return docsList;
    }

    /**
     * Get document as base64 from MinIO
     */
    public static String getDocumentAsBase64(String filename, Map<String, String> documentPaths,
                                             String tenantId) throws Exception {
        String storagePath = documentPaths.get(filename);
        if (storagePath == null) {
            throw new RuntimeException("Storage path not found for: " + filename);
        }

        StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);
        java.io.InputStream fileContent = storage.downloadDocument(storagePath);
        byte[] bytes = fileContent.readAllBytes();
        fileContent.close();

        return Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * Copy documents to next stage's userdoc/uploaded/ folder
     * This is called at the start of each stage to populate its input folder
     *
     * @param storage - Storage provider
     * @param documentPaths - Current document paths map
     * @param tenantId - Tenant ID
     * @param workflowKey - Workflow key
     * @param ticketId - Ticket ID
     * @param targetTaskName - Target task name
     * @return Updated document paths map pointing to new locations
     */
    public static Map<String, String> copyDocumentsToStageInput(
            StorageProvider storage,
            Map<String, String> documentPaths,
            String tenantId,
            String workflowKey,
            String ticketId,
            String targetTaskName) throws Exception {

        Map<String, String> updatedPaths = new HashMap<>();

        for (Map.Entry<String, String> entry : documentPaths.entrySet()) {
            String filename = entry.getKey();
            String sourcePath = entry.getValue();

            // Build target path in new stage's userdoc/uploaded/
            String targetPath = StoragePathBuilder.buildUserUploadPath(
                    tenantId, workflowKey, ticketId, targetTaskName, filename
            );

            // Copy document
            StoragePathBuilder.copyToStageInput(storage, sourcePath, targetPath);

            updatedPaths.put(filename, targetPath);

            log.debug("Copied {} to stage {} input", filename, targetTaskName);
        }

        log.info("Copied {} documents to {} userdoc/uploaded/",
                updatedPaths.size(), targetTaskName);

        return updatedPaths;
    }
}