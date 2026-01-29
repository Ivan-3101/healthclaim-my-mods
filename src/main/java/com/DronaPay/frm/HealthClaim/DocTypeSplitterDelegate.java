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

                        // FIX: Check for BOTH rawResponse and apiResponse keys
                        if (resMap.containsKey("rawResponse")) {
                            rawResponse = resMap.get("rawResponse").toString();
                        } else if (resMap.containsKey("apiResponse")) {
                            rawResponse = resMap.get("apiResponse").toString();
                            log.debug("Using 'apiResponse' key for {}", filename);
                        } else {
                            log.warn("Result Map for {} contains keys: {}", filename, resMap.keySet());
                        }
                    } else if (resultObj instanceof JSONObject) {
                        JSONObject resJson = (JSONObject) resultObj;

                        // FIX: Check for BOTH rawResponse and apiResponse keys
                        if (resJson.has("rawResponse")) {
                            rawResponse = resJson.getString("rawResponse");
                        } else if (resJson.has("apiResponse")) {
                            rawResponse = resJson.getString("apiResponse");
                            log.debug("Using 'apiResponse' key for {}", filename);
                        }
                    }

                    if (rawResponse != null) {
                        response = new JSONObject(rawResponse);
                        log.info("Successfully retrieved classifier result for: {}", filename);
                    } else {
                        log.warn("Retrieved result for {} but neither 'rawResponse' nor 'apiResponse' key was found. Object type: {}",
                                filename, resultObj.getClass().getName());
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

            String sanitizedDocType = docType.replaceAll("[^a-zA-Z0-9_-]", "_");
            PDDocument outputDoc = new PDDocument();

            for (PageInfo pageInfo : pages) {
                try (InputStream inputStream = storage.downloadDocument(pageInfo.storagePath);
                     PDDocument sourceDoc = PDDocument.load(inputStream)) {

                    if (pageInfo.pageNumber > 0 && pageInfo.pageNumber <= sourceDoc.getNumberOfPages()) {
                        outputDoc.addPage(sourceDoc.getPage(pageInfo.pageNumber - 1));
                    } else {
                        log.warn("Invalid page number {} for document {}", pageInfo.pageNumber, pageInfo.filename);
                    }
                }
            }

            if (outputDoc.getNumberOfPages() > 0) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                outputDoc.save(baos);
                outputDoc.close();

                byte[] pdfBytes = baos.toByteArray();
                String splitPath = String.format("1/HealthClaim/%s/%s/%s.pdf",
                        ticketId, currentStage, sanitizedDocType);

                storage.uploadDocument(splitPath, pdfBytes, "application/pdf");
                log.info("Created split document: {} ({} pages)", splitPath, pages.size());

                String varName = "split_" + sanitizedDocType;
                execution.setVariable(varName, splitPath);
                splitDocumentVars.add(varName);
            }
        }

        execution.setVariable("splitDocumentPaths", splitDocumentVars);
        log.info("=== Doc Type Splitter Completed: {} documents created ===", splitDocumentVars.size());
    }

    private static class PageInfo {
        String filename;
        String storagePath;
        int pageNumber;

        PageInfo(String filename, String storagePath, int pageNumber) {
            this.filename = filename;
            this.storagePath = storagePath;
            this.pageNumber = pageNumber;
        }
    }
}