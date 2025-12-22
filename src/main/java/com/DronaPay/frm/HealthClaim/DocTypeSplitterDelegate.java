package com.DronaPay.frm.HealthClaim;

import com.DronaPay.frm.HealthClaim.generic.config.WorkflowStageMapping;
import com.DronaPay.frm.HealthClaim.generic.services.ConfigurationService;
import com.DronaPay.frm.HealthClaim.generic.services.ObjectStorageService;
import com.DronaPay.frm.HealthClaim.generic.storage.StorageProvider;
import com.DronaPay.frm.HealthClaim.generic.utils.StoragePathBuilder;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.*;

@Slf4j
public class DocTypeSplitterDelegate implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("=== Doc Type Splitter Started ===");

        String tenantId = execution.getTenantId();
        String ticketId = String.valueOf(execution.getVariable("TicketID"));
        String workflowKey = StoragePathBuilder.getWorkflowType(execution);
        String taskName = StoragePathBuilder.getTaskName(execution);
        int stageNumber = WorkflowStageMapping.getStageNumber(workflowKey, taskName);

        if (stageNumber == -1) {
            log.warn("Stage number not found for task '{}', using previous + 1", taskName);
            stageNumber = StoragePathBuilder.getStageNumber(execution) + 1;
        }

        log.info("Stage {}: {}", stageNumber, taskName);

        @SuppressWarnings("unchecked")
        Map<String, String> documentPaths = (Map<String, String>) execution.getVariable("documentPaths");

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> fileProcessMap =
                (Map<String, Map<String, Object>>) execution.getVariable("fileProcessMap");

        StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);
        Properties props = ConfigurationService.getTenantProperties(tenantId);
        String rootFolder = props.getProperty("storage.minio.bucketName", "insurance-claims");

        // Get classification results from previous stage (Document_Classifier)
        Map<String, List<PageInfo>> docTypeMap = new HashMap<>();
        Map<String, PDDocument> openDocs = new HashMap<>();

        try {
            for (String filename : fileProcessMap.keySet()) {
                log.info("Processing classification for: {}", filename);

                Map<String, Object> fileResults = fileProcessMap.get(filename);
                Map<String, Object> classifierResult = (Map<String, Object>) fileResults.get("Document_Classifier");

                if (classifierResult == null) {
                    log.warn("No classification result found for {}, skipping", filename);
                    continue;
                }

                Map<String, Object> extractedData = (Map<String, Object>) classifierResult.get("extractedData");

                if (extractedData == null || !extractedData.containsKey("docClassifications")) {
                    log.warn("No docClassifications found for {}", filename);
                    continue;
                }

                String classificationsJson = (String) extractedData.get("docClassifications");
                JSONObject classifications = new JSONObject(classificationsJson);

                String storagePath = documentPaths.get(filename);
                InputStream pdfStream = storage.downloadDocument(storagePath);
                PDDocument document = PDDocument.load(pdfStream);
                openDocs.put(filename, document);

                // Parse classifications and map pages
                for (String key : classifications.keySet()) {
                    Object value = classifications.get(key);

                    if (value instanceof JSONObject) {
                        JSONObject pageClass = (JSONObject) value;
                        String docType = pageClass.optString("document_type", "unknown");
                        int pageNum = Integer.parseInt(key.replace("page_", ""));

                        docTypeMap.computeIfAbsent(docType, k -> new ArrayList<>())
                                .add(new PageInfo(filename, storagePath, pageNum));

                        log.debug("Page {} of {} classified as: {}", pageNum, filename, docType);
                    }
                }
            }

            if (docTypeMap.isEmpty()) {
                log.error("No documents were classified");
                throw new RuntimeException("Document classification failed - no results");
            }

            log.info("Document classification complete. Found {} document types", docTypeMap.size());

            // Create split documents and store in userdoc/processed/
            List<String> splitDocumentVars = new ArrayList<>();

            for (Map.Entry<String, List<PageInfo>> entry : docTypeMap.entrySet()) {
                String docType = entry.getKey();
                List<PageInfo> pages = entry.getValue();

                String splitFilename = docType.toLowerCase().replace(" ", "_") + ".pdf";

                // Merge pages
                PDFMergerUtility merger = new PDFMergerUtility();
                PDDocument mergedDoc = new PDDocument();

                try {
                    for (PageInfo pageInfo : pages) {
                        PDDocument sourceDoc = openDocs.get(pageInfo.filename);
                        mergedDoc.addPage(sourceDoc.getPage(pageInfo.pageNumber - 1));
                    }

                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    mergedDoc.save(baos);
                    byte[] pdfBytes = baos.toByteArray();

                    // Store in userdoc/processed/ folder (NEW STRUCTURE)
                    String splitStoragePath = StoragePathBuilder.buildUserProcessedPath(
                            rootFolder, tenantId, workflowKey, ticketId,
                            stageNumber, taskName, splitFilename
                    );

                    storage.uploadDocument(splitStoragePath, pdfBytes, "application/pdf");

                    splitDocumentVars.add(splitFilename);
                    documentPaths.put(splitFilename, splitStoragePath);
                    fileProcessMap.put(splitFilename, new HashMap<>());

                    log.info("Created split document: {} at {} ({} pages, {} bytes)",
                            splitFilename, splitStoragePath, pages.size(), pdfBytes.length);

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

        } finally {
            // Close all open documents
            for (PDDocument doc : openDocs.values()) {
                try {
                    doc.close();
                } catch (Exception e) {
                    log.warn("Error closing document", e);
                }
            }
        }
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