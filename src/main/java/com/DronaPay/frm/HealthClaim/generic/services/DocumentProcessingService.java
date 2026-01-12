package com.DronaPay.frm.HealthClaim.generic.services;

import com.DronaPay.frm.HealthClaim.generic.storage.StorageProvider;
import lombok.extern.slf4j.Slf4j;
import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.bpm.engine.variable.value.FileValue;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.*;

@Slf4j
public class DocumentProcessingService {

    public static Map<String, String> processAndUploadDocuments(
            Object docsObject, String tenantId, String workflowKey, String ticketId,
            int stageNumber, String stageName) {

        Map<String, String> documentPaths = new HashMap<>();

        if (docsObject == null) {
            log.warn("No documents provided in 'docs' variable");
            return documentPaths;
        }

        try {
            StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);

            List<Map<String, Object>> docsList = convertToDocsList(docsObject);

            for (Map<String, Object> doc : docsList) {
                String filename = doc.get("filename").toString();
                String mimetype = doc.get("mimetype").toString();
                String base64Content = doc.get("content").toString();

                byte[] fileContent = Base64.getDecoder().decode(base64Content);

                String storagePath = String.format("%s/%s/%s/%d_%s/%s",
                        tenantId, workflowKey, ticketId, stageNumber, stageName, filename);

                String documentUrl = storage.uploadDocument(storagePath, fileContent, mimetype);
                documentPaths.put(filename, storagePath);

                log.info("Uploaded document: {} -> {} ({} bytes)",
                        filename, storagePath, fileContent.length);
            }

            log.info("Successfully uploaded {} documents to stage {}", documentPaths.size(), stageNumber);

        } catch (Exception e) {
            log.error("Error processing and uploading documents", e);
        }

        return documentPaths;
    }

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

                byte[] fileContent = Base64.getDecoder().decode(base64Content);

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

    public static FileValue downloadDocumentAsFileValue(String filename, String storagePath, String tenantId) throws Exception {
        StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);

        InputStream inputStream = storage.downloadDocument(storagePath);
        byte[] fileContent = inputStream.readAllBytes();

        return Variables.fileValue(filename)
                .file(fileContent)
                .mimeType("application/octet-stream")
                .create();
    }

    public static String getDocumentAsBase64(String filename, Map<String, String> documentPaths, String tenantId) throws Exception {
        String storagePath = documentPaths.get(filename);
        if (storagePath == null) {
            throw new RuntimeException("Storage path not found for: " + filename);
        }

        StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);
        InputStream inputStream = storage.downloadDocument(storagePath);
        byte[] fileContent = inputStream.readAllBytes();

        return Base64.getEncoder().encodeToString(fileContent);
    }

    public static Map<String, Map<String, Object>> initializeFileProcessMap(Set<String> filenames) {
        Map<String, Map<String, Object>> fileProcessMap = new HashMap<>();

        for (String filename : filenames) {
            fileProcessMap.put(filename, new HashMap<>());
        }

        log.debug("Initialized file process map for {} files", filenames.size());
        return fileProcessMap;
    }

    private static List<Map<String, Object>> convertToDocsList(Object docsObject) {
        List<Map<String, Object>> docsList = new ArrayList<>();

        if (docsObject instanceof List) {
            docsList = (List<Map<String, Object>>) docsObject;
        } else if (docsObject instanceof String) {
            String docsJson = (String) docsObject;
            JSONArray docsArray = new JSONArray(docsJson);

            for (int i = 0; i < docsArray.length(); i++) {
                JSONObject docObj = docsArray.getJSONObject(i);
                Map<String, Object> docMap = new HashMap<>();
                docObj.keySet().forEach(key -> docMap.put(key, docObj.get(key)));
                docsList.add(docMap);
            }
        }

        return docsList;
    }
}