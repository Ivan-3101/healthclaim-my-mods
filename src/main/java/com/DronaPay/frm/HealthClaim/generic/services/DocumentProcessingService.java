package com.DronaPay.frm.HealthClaim.generic.services;

import com.DronaPay.frm.HealthClaim.generic.storage.StorageProvider;
import com.DronaPay.frm.HealthClaim.generic.utils.StageHelper;
import lombok.extern.slf4j.Slf4j;
import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.bpm.engine.variable.value.FileValue;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;

@Slf4j
public class DocumentProcessingService {

    /**
     * Process documents and upload to object storage with stage-based paths
     * @param docsObject - the docs variable from process
     * @param tenantId - tenant ID
     * @param workflowKey - workflow key (e.g., "HealthClaim")
     * @param ticketId - ticket ID
     * @param stageNumber - current stage number
     * @param taskName - current task name
     * @return Map of filename -> storage path
     */
    public static Map<String, String> processAndUploadDocuments(
            Object docsObject, String tenantId, String workflowKey, String ticketId,
            int stageNumber, String taskName) {

        Map<String, String> documentPaths = new HashMap<>();

        if (docsObject == null) {
            log.warn("No documents provided in 'docs' variable");
            return documentPaths;
        }

        try {
            // Get storage provider
            StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);

            // Build stage folder name
            String stageFolderName = StageHelper.buildStageFolderName(stageNumber, taskName);

            // Convert docs to list
            List<Map<String, Object>> docsList = convertToDocsList(docsObject);

            for (Map<String, Object> doc : docsList) {
                String filename = doc.get("filename").toString();
                String mimetype = doc.get("mimetype").toString();
                String base64Content = doc.get("content").toString();

                // Decode base64 content
                byte[] fileContent = Base64.getDecoder().decode(base64Content);

                // Build stage-based storage path: {tenantId}/{workflowKey}/{ticketId}/{stage#}_{TaskName}/{filename}
                String storagePath = String.format("%s/%s/%s/%s/%s",
                        tenantId, workflowKey, ticketId, stageFolderName, filename);

                // Upload to storage
                String documentUrl = storage.uploadDocument(storagePath, fileContent, mimetype);

                // Store path mapping
                documentPaths.put(filename, storagePath);

                log.info("Uploaded document '{}' to stage-based path: {}", filename, storagePath);
            }

            log.info("Processed {} documents for stage {}", documentPaths.size(), stageNumber);
            return documentPaths;

        } catch (Exception e) {
            log.error("Failed to process and upload documents", e);
            throw new RuntimeException("Document processing failed", e);
        }
    }

    /**
     * Convert docs object to list of maps
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> convertToDocsList(Object docsObject) {
        if (docsObject instanceof List) {
            return (List<Map<String, Object>>) docsObject;
        } else if (docsObject instanceof String) {
            JSONArray jsonArray = new JSONArray((String) docsObject);
            List<Map<String, Object>> docsList = new ArrayList<>();
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject jsonDoc = jsonArray.getJSONObject(i);
                Map<String, Object> docMap = new HashMap<>();
                jsonDoc.keySet().forEach(key -> docMap.put(key, jsonDoc.get(key)));
                docsList.add(docMap);
            }
            return docsList;
        } else {
            throw new IllegalArgumentException("Unsupported docs format: " + docsObject.getClass());
        }
    }

    /**
     * Initialize file process map
     */
    public static Map<String, Map<String, Object>> initializeFileProcessMap(Set<String> filenames) {
        Map<String, Map<String, Object>> fileProcessMap = new HashMap<>();
        for (String filename : filenames) {
            fileProcessMap.put(filename, new HashMap<>());
        }
        return fileProcessMap;
    }

    /**
     * Process documents (legacy - creates FileValue objects without upload)
     */
    @SuppressWarnings("unchecked")
    public static Map<String, FileValue> processDocuments(Object docsObject) {
        Map<String, FileValue> fileMap = new HashMap<>();

        if (docsObject == null) {
            log.warn("No documents provided in 'docs' variable");
            return fileMap;
        }

        try {
            List<Map<String, Object>> docsList = convertToDocsList(docsObject);

            for (Map<String, Object> doc : docsList) {
                String filename = doc.get("filename").toString();
                String mimetype = doc.get("mimetype").toString();
                String base64Content = doc.get("content").toString();

                byte[] fileContent = Base64.getDecoder().decode(base64Content);

                FileValue fileValue = Variables.fileValue(filename)
                        .file(fileContent)
                        .mimeType(mimetype)
                        .create();

                fileMap.put(filename, fileValue);
            }

            log.info("Processed {} documents (in-memory)", fileMap.size());
            return fileMap;

        } catch (Exception e) {
            log.error("Failed to process documents", e);
            throw new RuntimeException("Document processing failed", e);
        }
    }

    /**
     * Download document as FileValue (for backward compatibility)
     */
    public static FileValue downloadDocumentAsFileValue(String filename, String storagePath,
                                                        String tenantId) throws Exception {
        StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);
        byte[] content = storage.downloadDocument(storagePath).readAllBytes();

        return Variables.fileValue(filename)
                .file(content)
                .mimeType(getMimeType(filename))
                .create();
    }

    /**
     * Get MIME type from filename
     */
    private static String getMimeType(String filename) {
        if (filename.endsWith(".pdf")) {
            return "application/pdf";
        } else if (filename.endsWith(".jpg") || filename.endsWith(".jpeg")) {
            return "image/jpeg";
        } else if (filename.endsWith(".png")) {
            return "image/png";
        }
        return "application/octet-stream";
    }
}