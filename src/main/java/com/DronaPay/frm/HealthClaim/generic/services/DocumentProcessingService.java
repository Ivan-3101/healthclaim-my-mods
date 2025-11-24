package com.DronaPay.frm.HealthClaim.generic.services;

import com.DronaPay.frm.HealthClaim.generic.storage.StorageProvider;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.variable.Variables;
import org.camunda.bpm.engine.variable.value.FileValue;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;

@Slf4j
public class DocumentProcessingService {

    /**
     * Process documents and upload to object storage
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

            // Get path pattern from properties
            Properties props = ConfigurationService.getTenantProperties(tenantId);
            String pathPattern = props.getProperty("storage.pathPattern",
                    "{tenantId}/{workflowKey}/{ticketId}/");

            // Convert docs to list
            List<Map<String, Object>> docsList = convertToDocsList(docsObject);

            for (Map<String, Object> doc : docsList) {
                String filename = doc.get("filename").toString();
                String mimetype = doc.get("mimetype").toString();
                String base64Content = doc.get("content").toString();

                // Decode base64 content
                byte[] fileContent = Base64.getDecoder().decode(base64Content);

                // Build storage path
                String storagePath = ObjectStorageService.buildStoragePath(
                        pathPattern, tenantId, workflowKey, ticketId, filename
                );

                // Upload to storage
                String documentUrl = storage.uploadDocument(storagePath, fileContent, mimetype);
                documentPaths.put(filename, storagePath);

                log.info("Uploaded document: {} -> {} ({} bytes)",
                        filename, storagePath, fileContent.length);
            }

            log.info("Successfully uploaded {} documents to storage", documentPaths.size());

        } catch (Exception e) {
            log.error("Error processing and uploading documents", e);
        }

        return documentPaths;
    }

    /**
     * Process documents WITHOUT uploading (for backward compatibility)
     * Creates FileValue objects in memory
     */
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
                String encoding = doc.get("encoding").toString();
                String base64Content = doc.get("content").toString();

                // Decode base64 content
                byte[] fileContent = Base64.getDecoder().decode(base64Content);

                // Create FileValue
                FileValue fileValue = Variables.fileValue(filename)
                        .file(fileContent)
                        .mimeType(mimetype)
                        .encoding(encoding)
                        .create();

                fileMap.put(filename, fileValue);
                log.debug("Processed document: {} (size: {} bytes)", filename, fileContent.length);
            }

            log.info("Successfully processed {} documents", fileMap.size());

        } catch (Exception e) {
            log.error("Error processing documents", e);
        }

        return fileMap;
    }

    /**
     * Download document from storage and create FileValue
     * @param filename - document filename
     * @param storagePath - path in object storage
     * @param tenantId - tenant ID
     * @return FileValue object
     */
    public static FileValue downloadDocumentAsFileValue(
            String filename, String storagePath, String tenantId) throws Exception {

        StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);

        // Download document content
        byte[] content = storage.downloadDocument(storagePath).readAllBytes();

        // Determine MIME type from filename
        String mimeType = guessMimeType(filename);

        // Create FileValue
        return Variables.fileValue(filename)
                .file(content)
                .mimeType(mimeType)
                .encoding("UTF-8")
                .create();
    }

    /**
     * Guess MIME type from filename extension
     */
    private static String guessMimeType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".doc")) return "application/msword";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        return "application/octet-stream";
    }

    /**
     * Convert various input formats to List<Map<String, Object>>
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> convertToDocsList(Object docsObject) {
        if (docsObject instanceof List) {
            return (List<Map<String, Object>>) docsObject;
        } else if (docsObject instanceof String) {
            // Parse JSON string
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
    public static Map<String, Map<String, Object>> initializeFileProcessMap(Set<String> filenames) {
        Map<String, Map<String, Object>> fileProcessMap = new HashMap<>();
        for (String filename : filenames) {
            fileProcessMap.put(filename, new HashMap<>());
        }
        log.debug("Initialized file process map for {} files", filenames.size());
        return fileProcessMap;
    }

    /**
     * Get document for agent processing
     * Downloads from storage and returns as base64 string
     */
    public static String getDocumentAsBase64(String filename,
                                             Map<String, String> documentPaths,
                                             String tenantId) throws Exception {
        String storagePath = documentPaths.get(filename);
        if (storagePath == null) {
            throw new IllegalArgumentException("Document not found: " + filename);
        }

        StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);
        byte[] content = storage.downloadDocument(storagePath).readAllBytes();

        return Base64.getEncoder().encodeToString(content);
    }
}