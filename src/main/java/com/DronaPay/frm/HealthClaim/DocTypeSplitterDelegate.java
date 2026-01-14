package com.DronaPay.frm.HealthClaim;

import com.DronaPay.frm.HealthClaim.generic.services.AgentResultStorageService;
import com.DronaPay.frm.HealthClaim.generic.services.ObjectStorageService;
import com.DronaPay.frm.HealthClaim.generic.storage.StorageProvider;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
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
        String stageName = "DocTypeSplitter";

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> fileProcessMap =
                (Map<String, Map<String, Object>>) execution.getVariable("fileProcessMap");

        @SuppressWarnings("unchecked")
        Map<String, String> documentPaths =
                (Map<String, String>) execution.getVariable("documentPaths");

        if (fileProcessMap == null || fileProcessMap.isEmpty()) {
            log.error("No fileProcessMap found");
            throw new RuntimeException("fileProcessMap is null or empty");
        }

        Map<String, List<PageInfo>> docTypePages = new LinkedHashMap<>();

        for (String filename : fileProcessMap.keySet()) {
            Map<String, Object> fileResults = fileProcessMap.get(filename);

            if (!fileResults.containsKey("Document_ClassifierOutput")) {
                log.warn("No classifier output for file: {}, skipping", filename);
                continue;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> classifierOutput =
                    (Map<String, Object>) fileResults.get("Document_ClassifierOutput");

            String apiCall = (String) classifierOutput.get("apiCall");
            if (!"success".equals(apiCall)) {
                log.warn("Classifier failed for file: {}, skipping", filename);
                continue;
            }

            String minioPath = (String) classifierOutput.get("minioPath");
            String storagePath = documentPaths.get(filename);

            if (minioPath == null || storagePath == null) {
                log.warn("Missing minioPath or storagePath for file: {}, skipping", filename);
                continue;
            }

            Map<String, Object> result =
                    AgentResultStorageService.retrieveAgentResult(tenantId, minioPath);
            String apiResponse = (String) result.get("apiResponse");

            JSONObject response = new JSONObject(apiResponse);

            if (!response.has("answer")) {
                log.warn("No 'answer' field in classifier response for: {}", filename);
                continue;
            }

            JSONArray answer = response.getJSONArray("answer");

            for (int i = 0; i < answer.length(); i++) {
                JSONObject item = answer.getJSONObject(i);
                String category = item.getString("category");
                int pageNumber = item.getInt("page_number");

                docTypePages.computeIfAbsent(category, k -> new ArrayList<>())
                        .add(new PageInfo(filename, storagePath, pageNumber));

                log.debug("Classified page {} of {} as {}", pageNumber, filename, category);
            }
        }

        if (docTypePages.isEmpty()) {
            log.error("No document types found after classification");
            throw new RuntimeException("Document classification produced no results");
        }

        log.info("Grouped pages into {} document types", docTypePages.size());

        List<String> splitDocumentVars = new ArrayList<>();
        StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);

        for (Map.Entry<String, List<PageInfo>> entry : docTypePages.entrySet()) {
            String docType = entry.getKey();
            List<PageInfo> pages = entry.getValue();

            String sanitizedDocType = docType.replaceAll("[^a-zA-Z0-9]", "_").toLowerCase();
            String splitFilename = sanitizedDocType + ".pdf";

            log.info("Creating split document: {} with {} pages", splitFilename, pages.size());

            PDDocument mergedDoc = new PDDocument();

            try {
                Map<String, PDDocument> openDocs = new HashMap<>();

                for (PageInfo pageInfo : pages) {
                    if (!openDocs.containsKey(pageInfo.storagePath)) {
                        try {
                            InputStream stream = storage.downloadDocument(pageInfo.storagePath);
                            PDDocument doc = PDDocument.load(stream);
                            stream.close();
                            openDocs.put(pageInfo.storagePath, doc);
                            log.debug("Loaded source document: {}", pageInfo.filename);
                        } catch (Exception e) {
                            log.error("Error loading document {}", pageInfo.filename, e);
                        }
                    }
                }

                for (PageInfo pageInfo : pages) {
                    PDDocument sourceDoc = openDocs.get(pageInfo.storagePath);
                    if (sourceDoc == null) {
                        log.warn("Source document not loaded for {}", pageInfo.filename);
                        continue;
                    }

                    try {
                        int pageIndex = pageInfo.pageNumber - 1;

                        if (pageIndex >= 0 && pageIndex < sourceDoc.getNumberOfPages()) {
                            PDPage page = sourceDoc.getPage(pageIndex);
                            mergedDoc.addPage(page);

                            log.debug("Added page {} from {} to {}",
                                    pageInfo.pageNumber, pageInfo.filename, splitFilename);
                        } else {
                            log.warn("Page {} out of range in {} (total pages: {})",
                                    pageInfo.pageNumber, pageInfo.filename,
                                    sourceDoc.getNumberOfPages());
                        }
                    } catch (Exception e) {
                        log.error("Error adding page {} from {}",
                                pageInfo.pageNumber, pageInfo.filename, e);
                    }
                }

                if (mergedDoc.getNumberOfPages() == 0) {
                    log.warn("No pages added for document type: {}, skipping", docType);
                    for (PDDocument doc : openDocs.values()) {
                        try {
                            doc.close();
                        } catch (Exception e) {
                            log.warn("Error closing document", e);
                        }
                    }
                    continue;
                }

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                mergedDoc.save(baos);
                byte[] pdfBytes = baos.toByteArray();
                baos.close();

                for (PDDocument doc : openDocs.values()) {
                    try {
                        doc.close();
                    } catch (Exception e) {
                        log.warn("Error closing document", e);
                    }
                }

                String splitStoragePath = tenantId + "/HealthClaim/" + ticketId +
                        "/" + stageName + "/" + splitFilename;
                storage.uploadDocument(splitStoragePath, pdfBytes, "application/pdf");

                splitDocumentVars.add(splitFilename);
                documentPaths.put(splitFilename, splitStoragePath);
                fileProcessMap.put(splitFilename, new HashMap<>());

                log.info("Created split document: {} at {} ({} pages, {} bytes)",
                        splitFilename, splitStoragePath, mergedDoc.getNumberOfPages(),
                        pdfBytes.length);

            } finally {
                mergedDoc.close();
            }
        }

        if (splitDocumentVars.isEmpty()) {
            log.error("No split documents were created");
            throw new RuntimeException("Failed to create any split documents");
        }

        execution.setVariable("splitDocumentVars", splitDocumentVars);
        execution.setVariable("documentPaths", documentPaths);
        execution.setVariable("fileProcessMap", fileProcessMap);

        log.info("=== Doc Type Splitter Completed: {} split documents created ===",
                splitDocumentVars.size());
        log.info("Split documents: {}", splitDocumentVars);
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