package com.DronaPay.frm.HealthClaim;

import com.DronaPay.frm.HealthClaim.generic.services.AgentResultStorageService;
import com.DronaPay.frm.HealthClaim.generic.services.ObjectStorageService;
import com.DronaPay.frm.HealthClaim.generic.storage.StorageProvider;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.*;

/**
 * Document Type Splitter Delegate
 *
 * Takes document classifier output and splits multi-page PDFs by document type.
 * Groups pages by category (e.g., Pre-auth form, Identity Card, Diagnostic Report)
 * and creates separate PDFs for each type.
 *
 * Input: fileProcessMap with Document_ClassifierOutput
 * Output: splitDocumentVars (list of filenames), documentPaths updated with split docs
 */
@Slf4j
public class DocTypeSplitterDelegate implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("=== Doc Type Splitter Started ===");

        String ticketId = (String) execution.getVariable("TicketID");
        String tenantId = execution.getTenantId();

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

        // Group pages by document type across all uploaded files
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

        // Create split documents
        List<String> splitDocumentVars = new ArrayList<>();
        StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);

        for (Map.Entry<String, List<PageInfo>> entry : docTypePages.entrySet()) {
            String docType = entry.getKey();
            List<PageInfo> pages = entry.getValue();

            // Sanitize doc type for filename
            String sanitizedDocType = docType.replaceAll("[^a-zA-Z0-9]", "_").toLowerCase();
            String splitFilename = sanitizedDocType + ".pdf";

            log.info("Creating split document: {} with {} pages", splitFilename, pages.size());

            // Extract pages from source PDFs and merge
            PDDocument mergedDoc = new PDDocument();

            try {
                for (PageInfo pageInfo : pages) {
                    try (InputStream stream = storage.downloadDocument(pageInfo.storagePath);
                         PDDocument sourceDoc = PDDocument.load(stream)) {

                        // PDF pages are 0-indexed, but classifier returns 1-indexed
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
                        log.error("Error extracting page {} from {}",
                                pageInfo.pageNumber, pageInfo.filename, e);
                        // Continue with other pages
                    }
                }

                if (mergedDoc.getNumberOfPages() == 0) {
                    log.warn("No pages extracted for document type: {}, skipping", docType);
                    mergedDoc.close();
                    continue;
                }

                // Save merged document to MinIO
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                mergedDoc.save(baos);
                byte[] pdfBytes = baos.toByteArray();

                String splitStoragePath = tenantId + "/HealthClaim/" + ticketId +
                        "/split/" + splitFilename;
                storage.uploadDocument(splitStoragePath, pdfBytes, "application/pdf");

                // Add to split document list
                splitDocumentVars.add(splitFilename);

                // Update documentPaths with split document (so downstream agents can access it)
                documentPaths.put(splitFilename, splitStoragePath);

                // Initialize fileProcessMap entry for this split document
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

        // Set process variables
        execution.setVariable("splitDocumentVars", splitDocumentVars);
        execution.setVariable("documentPaths", documentPaths); // Updated with split docs
        execution.setVariable("fileProcessMap", fileProcessMap); // Updated with split doc entries

        log.info("=== Doc Type Splitter Completed: {} split documents created ===",
                splitDocumentVars.size());
        log.info("Split documents: {}", splitDocumentVars);
    }

    /**
     * Helper class to track page information
     */
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