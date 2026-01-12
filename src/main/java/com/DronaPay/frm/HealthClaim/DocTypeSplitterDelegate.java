package com.DronaPay.frm.HealthClaim;

import com.DronaPay.frm.HealthClaim.generic.services.AgentResultStorageService;
import com.DronaPay.frm.HealthClaim.generic.services.ObjectStorageService;
import com.DronaPay.frm.HealthClaim.generic.storage.StorageProvider;
import com.DronaPay.frm.HealthClaim.generic.utils.StageHelper;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Document Type Splitter Delegate with Stage Support
 * Groups pages by document type from classifier results
 * Merges pages into new PDFs per document type
 */
@Slf4j
public class DocTypeSplitterDelegate implements JavaDelegate {

    @Override
    @SuppressWarnings("unchecked")
    public void execute(DelegateExecution execution) throws Exception {
        log.info("=== Doc Type Splitter Started ===");

        // Get stage number and task name
        int stageNumber = StageHelper.getOrIncrementStage(execution);
        String taskName = execution.getCurrentActivityName();
        log.info("Stage {}: {}", stageNumber, taskName);

        String tenantId = execution.getTenantId();
        String ticketId = String.valueOf(execution.getVariable("TicketID"));

        // Get document paths and file process map
        Map<String, String> documentPaths = (Map<String, String>) execution.getVariable("documentPaths");
        Map<String, Map<String, Object>> fileProcessMap =
                (Map<String, Map<String, Object>>) execution.getVariable("fileProcessMap");

        if (documentPaths == null || fileProcessMap == null) {
            throw new RuntimeException("Missing documentPaths or fileProcessMap");
        }

        // Get classifier results from MinIO and group pages by document type
        Map<String, List<PageInfo>> documentTypeGroups = new HashMap<>();

        for (String filename : documentPaths.keySet()) {
            Map<String, Object> fileResults = fileProcessMap.get(filename);
            if (fileResults == null || !fileResults.containsKey("Document_Classifier")) {
                log.warn("No classifier result for: {}", filename);
                continue;
            }

            Map<String, Object> classifierResult = (Map<String, Object>) fileResults.get("Document_Classifier");
            String minioPath = (String) classifierResult.get("minioPath");

            // Retrieve full classifier response from MinIO
            Map<String, Object> classifierData = AgentResultStorageService.retrieveAgentResultByPath(
                    tenantId, minioPath);

            // Extract page classifications
            if (classifierData.containsKey("response")) {
                Map<String, Object> response = (Map<String, Object>) classifierData.get("response");
                if (response.containsKey("answer")) {
                    Map<String, Object> answer = (Map<String, Object>) response.get("answer");

                    for (String key : answer.keySet()) {
                        if (key.startsWith("page_")) {
                            int pageNumber = Integer.parseInt(key.substring(5));
                            String docType = answer.get(key).toString();

                            documentTypeGroups
                                    .computeIfAbsent(docType, k -> new ArrayList<>())
                                    .add(new PageInfo(filename, documentPaths.get(filename), pageNumber));
                        }
                    }
                }
            }
        }

        log.info("Found {} document types to split", documentTypeGroups.size());

        // Create split documents
        StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);
        List<String> splitDocumentVars = new ArrayList<>();

        // Build stage folder name for split documents
        String stageFolderName = StageHelper.buildStageFolderName(stageNumber, taskName);

        for (Map.Entry<String, List<PageInfo>> entry : documentTypeGroups.entrySet()) {
            String docType = entry.getKey();
            List<PageInfo> pages = entry.getValue();

            log.info("Processing document type '{}' with {} pages", docType, pages.size());

            // Sort pages by original page number
            pages.sort(Comparator.comparingInt(p -> p.pageNumber));

            // Merge pages into single PDF
            PDFMergerUtility merger = new PDFMergerUtility();
            PDDocument mergedDoc = new PDDocument();

            Map<String, PDDocument> openDocs = new HashMap<>();

            try {
                for (PageInfo pageInfo : pages) {
                    // Load document if not already loaded
                    if (!openDocs.containsKey(pageInfo.filename)) {
                        InputStream docStream = storage.downloadDocument(pageInfo.storagePath);
                        PDDocument doc = PDDocument.load(docStream);
                        openDocs.put(pageInfo.filename, doc);
                    }

                    PDDocument sourceDoc = openDocs.get(pageInfo.filename);
                    // Page numbers are 1-indexed from classifier, but PDF pages are 0-indexed
                    int pageIndex = pageInfo.pageNumber - 1;

                    if (pageIndex >= 0 && pageIndex < sourceDoc.getNumberOfPages()) {
                        mergedDoc.addPage(sourceDoc.getPage(pageIndex));
                    } else {
                        log.warn("Page {} out of bounds for {}", pageInfo.pageNumber, pageInfo.filename);
                    }
                }

                // Save merged PDF to byte array
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                mergedDoc.save(baos);
                byte[] pdfBytes = baos.toByteArray();

                // Generate filename
                String splitFilename = docType.toLowerCase().replaceAll("[^a-z0-9]", "_") + ".pdf";

                // Close all source documents
                for (PDDocument doc : openDocs.values()) {
                    try {
                        doc.close();
                    } catch (Exception e) {
                        log.warn("Error closing document", e);
                    }
                }

                // Upload to MinIO with stage-based path
                String splitStoragePath = String.format("%s/HealthClaim/%s/%s/%s",
                        tenantId, ticketId, stageFolderName, splitFilename);

                storage.uploadDocument(splitStoragePath, pdfBytes, "application/pdf");

                splitDocumentVars.add(splitFilename);
                documentPaths.put(splitFilename, splitStoragePath);
                fileProcessMap.put(splitFilename, new HashMap<>());

                log.info("Created split document: {} at stage {} ({} pages, {} bytes)",
                        splitFilename, stageNumber, mergedDoc.getNumberOfPages(), pdfBytes.length);

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

        log.info("=== Doc Type Splitter Completed: {} split documents created at stage {} ===",
                splitDocumentVars.size(), stageNumber);
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