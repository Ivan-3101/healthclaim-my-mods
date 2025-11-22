package com.DronaPay.frm.HealthClaim.generic.services;

import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.variable.Variables;
import org.camunda.bpm.engine.variable.value.FileValue;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;

@Slf4j
public class DocumentProcessingService {

    /**
     * Process documents from the 'docs' variable and create FileValue objects
     * @param docsObject - the docs variable from process (can be List or String)
     * @return Map of filename -> FileValue
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
}