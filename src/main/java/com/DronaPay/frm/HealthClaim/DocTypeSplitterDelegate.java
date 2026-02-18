package com.DronaPay.frm.HealthClaim;

import com.DronaPay.generic.services.AgentResultStorageService;
import com.DronaPay.generic.services.ObjectStorageService;
import com.DronaPay.generic.storage.StorageProvider;
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
        String stageName = execution.getCurrentActivityId();

        @SuppressWarnings("unchecked")
        Map<String, String> documentPaths = (Map<String, String>) execution.getVariable("documentPaths");

        if (documentPaths == null || documentPaths.isEmpty()) {
            throw new RuntimeException("documentPaths is null or empty");
        }

        Map<String, List<PageInfo>> docTypePages = new LinkedHashMap<>();
        StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);

        // Get all original document filenames (Doc1.pdf, Doc2.pdf, etc)
        for (String filename : documentPaths.keySet()) {
            if (!filename.endsWith(".pdf")) continue;

            String storagePath = documentPaths.get(filename);

            // Build MinIO path for classifier result: 1/HealthClaim/TICKETID/Document_Classifier/FILENAME.json
            String classifierPath = tenantId + "/HealthClaim/" + ticketId + "/Document_Classifier/" + filename + ".json";

            try {
                Map<String, Object> result = AgentResultStorageService.retrieveAgentResult(tenantId, classifierPath);
                String apiResponse = (String) result.get("apiResponse");

                if (apiResponse == null || apiResponse.trim().isEmpty()) {
                    log.warn("No apiResponse for: {}, skipping", filename);
                    continue;
                }

                JSONObject response = new JSONObject(apiResponse);

                if (!response.has("answer")) {
                    log.warn("No 'answer' field for: {}, skipping", filename);
                    continue;
                }

                JSONArray answer = response.getJSONArray("answer");

                for (int i = 0; i < answer.length(); i++) {
                    JSONObject item = answer.getJSONObject(i);
                    String category = item.getString("category");
                    int pageNumber = item.getInt("page_number");

                    docTypePages.computeIfAbsent(category, k -> new ArrayList<>())
                            .add(new PageInfo(filename, storagePath, pageNumber));
                }

                log.info("Processed classifier result for: {}", filename);

            } catch (Exception e) {
                log.error("Failed to get classifier result for: {}", filename, e);
            }
        }

        if (docTypePages.isEmpty()) {
            log.error("No document types found after classification");
            throw new RuntimeException("Document classification produced no results");
        }

        log.info("Grouped pages into {} document types", docTypePages.size());

        List<String> splitDocumentVars = new ArrayList<>();
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> fileProcessMap =
                (Map<String, Map<String, Object>>) execution.getVariable("fileProcessMap");
        if (fileProcessMap == null) {
            fileProcessMap = new HashMap<>();
        }

        for (Map.Entry<String, List<PageInfo>> entry : docTypePages.entrySet()) {
            String docType = entry.getKey();
            List<PageInfo> pages = entry.getValue();

            String sanitizedDocType = docType.replaceAll("[^a-zA-Z0-9]", "_").toLowerCase();
            String splitFilename = sanitizedDocType + ".pdf";

            log.info("Creating split document: {} with {} pages", splitFilename, pages.size());

            PDDocument mergedDoc = new PDDocument();
            Map<String, PDDocument> openDocs = new HashMap<>();
            Map<String, InputStream> openStreams = new HashMap<>();

            try {
                for (PageInfo pageInfo : pages) {
                    if (!openDocs.containsKey(pageInfo.storagePath)) {
                        InputStream stream = storage.downloadDocument(pageInfo.storagePath);
                        PDDocument doc = PDDocument.load(stream);
                        openStreams.put(pageInfo.storagePath, stream);
                        openDocs.put(pageInfo.storagePath, doc);
                    }
                }

                for (PageInfo pageInfo : pages) {
                    PDDocument sourceDoc = openDocs.get(pageInfo.storagePath);
                    if (sourceDoc == null) continue;

                    int pageIndex = pageInfo.pageNumber - 1;
                    if (pageIndex >= 0 && pageIndex < sourceDoc.getNumberOfPages()) {
                        PDPage page = sourceDoc.getPage(pageIndex);
                        mergedDoc.addPage(page);
                    }
                }

                if (mergedDoc.getNumberOfPages() == 0) {
                    log.warn("No pages added for: {}, skipping", docType);
                    continue;
                }

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                mergedDoc.save(baos);
                byte[] pdfBytes = baos.toByteArray();
                baos.close();

                String splitStoragePath = tenantId + "/HealthClaim/" + ticketId + "/" + stageName + "/" + splitFilename;
                storage.uploadDocument(splitStoragePath, pdfBytes, "application/pdf");

                splitDocumentVars.add(splitFilename);
                documentPaths.put(splitFilename, splitStoragePath);
                fileProcessMap.put(splitFilename, new HashMap<>());

                log.info("Created: {} ({} pages, {} bytes)", splitFilename, mergedDoc.getNumberOfPages(), pdfBytes.length);

            } finally {
                mergedDoc.close();
                for (PDDocument doc : openDocs.values()) {
                    try { doc.close(); } catch (Exception e) {}
                }
                for (InputStream stream : openStreams.values()) {
                    try { stream.close(); } catch (Exception e) {}
                }
            }
        }

        if (splitDocumentVars.isEmpty()) {
            throw new RuntimeException("Failed to create any split documents");
        }

        execution.setVariable("splitDocumentVars", splitDocumentVars);
        execution.setVariable("documentPaths", documentPaths);
        execution.setVariable("fileProcessMap", fileProcessMap);

        log.info("=== Completed: {} split documents ===", splitDocumentVars.size());
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