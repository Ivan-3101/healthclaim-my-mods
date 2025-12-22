package com.DronaPay.frm.HealthClaim;

import com.DronaPay.frm.HealthClaim.generic.services.AgentResultStorageService;
import com.DronaPay.frm.HealthClaim.generic.services.ObjectStorageService;
import com.DronaPay.frm.HealthClaim.generic.storage.StoragePathBuilder;
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
        String workflowKey = (String) execution.getVariable("WorkflowKey");
        if (workflowKey == null) {
            workflowKey = "HealthClaim";
        }

        String taskName = "DocTypeSplitter";

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

        StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);

        // Group pages by document type using classifier output
        Map<String, List<PageInfo>> docTypePages = new LinkedHashMap<>();

        // Process each file's classifier output
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

            // Retrieve classifier result from MinIO
            Map<String, Object> result =
                    AgentResultStorageService.retrieveAgentResult(tenantId, minioPath);
            String apiResponse = (String) result.get("apiResponse");

            JSONObject response = new JSONObject(apiResponse);

            if (!response.has("answer")) {
                log.warn("No 'answer' field in classifier response for: {}", filename);
                continue;
            }

            JSONArray answer = response.getJSONArray("answer");

            // Group pages by category
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

        // Create split documents and store in NEW structure
        List<String> splitDocumentVars = new ArrayList<>();
        Map<String, PDDocument> openDocs = new HashMap<>();

        try {
            for (Map.Entry<String, List<PageInfo>> entry : docTypePages.entrySet()) {
                String docType = entry.getKey();
                List<PageInfo> pages = entry.getValue();

                // Create safe filename
                String splitFilename = docType.toLowerCase()
                        .replaceAll("[^a-z0-9_-]", "_") + ".pdf";

                log.info("Creating split document: {} ({} pages)", splitFilename, pages.size());

                PDDocument mergedDoc = new PDDocument();

                try {
                    // Merge pages into single document
                    for (PageInfo page : pages) {
                        // Get or load the source document
                        PDDocument sourceDoc = openDocs.get(page.storagePath);
                        if (sourceDoc == null) {
                            InputStream sourceStream = storage.downloadDocument(page.storagePath);
                            sourceDoc = PDDocument.load(sourceStream);
                            sourceStream.close();
                            openDocs.put(page.storagePath, sourceDoc);
                        }

                        // Extract page (pages are 0-indexed in PDFBox, but 1-indexed in classifier)
                        int pageIndex = page.pageNumber - 1;
                        if (pageIndex >= 0 && pageIndex < sourceDoc.getNumberOfPages()) {
                            PDPage extractedPage = sourceDoc.getPage(pageIndex);
                            mergedDoc.addPage(extractedPage);
                        } else {
                            log.warn("Page {} out of bounds for {}", page.pageNumber, page.filename);
                        }
                    }

                    // Save merged document to byte array
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    mergedDoc.save(baos);
                    byte[] pdfBytes = baos.toByteArray();
                    baos.close();

                    // Upload to MinIO using NEW structure
                    // Path: insurance-claims/{tenantId}/{WorkflowType}/{TicketID}/5_DocTypeSplitter/userdoc/processed/{splitFilename}
                    String splitStoragePath = StoragePathBuilder.buildUserProcessedPath(
                            tenantId, workflowKey, ticketId, taskName, splitFilename
                    );

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

        } finally {
            // Close all source documents
            for (PDDocument doc : openDocs.values()) {
                try {
                    doc.close();
                } catch (Exception e) {
                    log.warn("Error closing document", e);
                }
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