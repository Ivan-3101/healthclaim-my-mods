package com.DronaPay.frm.HealthClaim;

import com.DronaPay.frm.HealthClaim.generic.services.AgentResultStorageService;
import com.DronaPay.frm.HealthClaim.generic.services.ObjectStorageService;
import com.DronaPay.frm.HealthClaim.generic.storage.StorageProvider;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.*;

@Slf4j
public class DocTypeSplitterDelegate implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("=== Doc Type Splitter Started ===");

        String ticketId = String.valueOf(execution.getVariable("TicketID"));
        String tenantId = execution.getTenantId();
        String currentStage = execution.getCurrentActivityId();

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> fileProcessMap =
                (Map<String, Map<String, Object>>) execution.getVariable("fileProcessMap");

        @SuppressWarnings("unchecked")
        Map<String, String> documentPaths =
                (Map<String, String>) execution.getVariable("documentPaths");

        if (fileProcessMap == null || fileProcessMap.isEmpty()) {
            throw new RuntimeException("fileProcessMap is null or empty");
        }

        Map<String, List<PageInfo>> docTypePages = new LinkedHashMap<>();

        // Iterate over keys (filenames like Doc1.pdf)
        for (String filename : fileProcessMap.keySet()) {
            JSONObject response = null;

            try {
                // Try retrieving the generic result file.
                // If GenericAgentDelegate saves as 'Doc1.pdf', StorageService likely adds '.json', making it 'Doc1.pdf.json'
                Object resultObj = AgentResultStorageService.retrieveAgentResult(
                        tenantId, ticketId, "Document_Classifier", filename); // .json is auto-appended by Service typically

                if (resultObj != null) {
                    String rawResponse = null;

                    // Handle both Map (from Jackson) and JSONObject (from org.json) to be safe
                    if (resultObj instanceof Map) {
                        Map<?, ?> resMap = (Map<?, ?>) resultObj;
                        if (resMap.containsKey("rawResponse")) {
                            rawResponse = resMap.get("rawResponse").toString();
                        } else {
                            log.warn("Result Map for {} contains keys: {}", filename, resMap.keySet());
                        }
                    } else if (resultObj instanceof JSONObject) {
                        JSONObject resJson = (JSONObject) resultObj;
                        if (resJson.has("rawResponse")) {
                            rawResponse = resJson.getString("rawResponse");
                        }
                    }

                    if (rawResponse != null) {
                        response = new JSONObject(rawResponse);
                        log.info("Successfully retrieved classifier result for: {}", filename);
                    } else {
                        log.warn("Retrieved result for {} but 'rawResponse' key was missing. Object type: {}", filename, resultObj.getClass().getName());
                    }
                }
            } catch (Exception e) {
                log.warn("Could not retrieve classifier result from MinIO for: {}", filename, e);
            }

            if (response == null || !response.has("answer")) {
                log.warn("No valid classifier output found for file: {}, skipping", filename);
                continue;
            }

            JSONArray answer = response.getJSONArray("answer");

            for (int i = 0; i < answer.length(); i++) {
                JSONObject item = answer.getJSONObject(i);
                String category = item.getString("category");
                int pageNumber = item.getInt("page_number");
                String storagePath = documentPaths.get(filename);

                if (storagePath != null) {
                    docTypePages.computeIfAbsent(category, k -> new ArrayList<>())
                            .add(new PageInfo(filename, storagePath, pageNumber));
                }
            }
        }

        if (docTypePages.isEmpty()) {
            log.error("No document types found after classification");
            throw new RuntimeException("Document classification produced no results");
        }

        log.info("Grouped pages into {} document types", docTypePages.size());

        // --- PDF SPLITTING LOGIC ---
        List<String> splitDocumentVars = new ArrayList<>();
        StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);

        for (Map.Entry<String, List<PageInfo>> entry : docTypePages.entrySet()) {
            String docType = entry.getKey();
            List<PageInfo> pages = entry.getValue();

            String sanitizedDocType = docType.replaceAll("[^a-zA-Z0-9]", "_").toLowerCase();
            String splitFilename = sanitizedDocType + ".pdf";

            PDDocument mergedDoc = new PDDocument();
            Map<String, PDDocument> openDocs = new HashMap<>();

            try {
                for (PageInfo pageInfo : pages) {
                    if (!openDocs.containsKey(pageInfo.storagePath)) {
                        try {
                            InputStream stream = storage.downloadDocument(pageInfo.storagePath);
                            PDDocument doc = PDDocument.load(stream);
                            stream.close();
                            openDocs.put(pageInfo.storagePath, doc);
                        } catch (Exception e) {
                            log.error("Error loading document {}", pageInfo.filename, e);
                        }
                    }
                }

                for (PageInfo pageInfo : pages) {
                    PDDocument sourceDoc = openDocs.get(pageInfo.storagePath);
                    if (sourceDoc != null) {
                        try {
                            int pageIndex = pageInfo.pageNumber - 1;
                            if (pageIndex >= 0 && pageIndex < sourceDoc.getNumberOfPages()) {
                                mergedDoc.addPage(sourceDoc.getPage(pageIndex));
                            }
                        } catch (Exception e) {
                            log.error("Error adding page", e);
                        }
                    }
                }

                if (mergedDoc.getNumberOfPages() > 0) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    mergedDoc.save(baos);
                    byte[] pdfBytes = baos.toByteArray();
                    baos.close();

                    String splitStoragePath = tenantId + "/HealthClaim/" + ticketId + "/" + currentStage + "/" + splitFilename;
                    storage.uploadDocument(splitStoragePath, pdfBytes, "application/pdf");

                    // Store Object for Multi-Instance compatibility
                    Map<String, String> splitDocInfo = new HashMap<>();
                    splitDocInfo.put("name", splitFilename);
                    splitDocInfo.put("type", docType);
                    // Add filename to list (as String, or handle as Map if your next stage expects Map)
                    splitDocumentVars.add(splitFilename);

                    documentPaths.put(splitFilename, splitStoragePath);
                    fileProcessMap.put(splitFilename, new HashMap<>());
                }
            } finally {
                mergedDoc.close();
                for (PDDocument doc : openDocs.values()) doc.close();
            }
        }

        if (splitDocumentVars.isEmpty()) {
            throw new RuntimeException("Failed to create any split documents");
        }

        execution.setVariable("splitDocumentVars", splitDocumentVars);
        execution.setVariable("documentPaths", documentPaths);
        execution.setVariable("fileProcessMap", fileProcessMap);

        log.info("=== Doc Type Splitter Completed ===");
    }

    private static class PageInfo {
        final String filename;
        final String storagePath;
        final int pageNumber;

        PageInfo(String filename, String storagePath, int pageNumber) {
            this.filename = filename;
            this.storagePath = storagePath;
            this.pageNumber = pageNumber;
        }
    }
}