package com.DronaPay.frm.HealthClaim;

import com.DronaPay.frm.HealthClaim.generic.services.ObjectStorageService;
import com.DronaPay.frm.HealthClaim.generic.storage.StorageProvider;
import lombok.extern.slf4j.Slf4j;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.*;

@Slf4j
public class DocTypeSplitterDelegate implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("=== Doc Type Splitter Started ===");

        String ticketId = String.valueOf(execution.getVariable("TicketID"));
        String tenantId = execution.getTenantId();
        List<String> attachmentVars = (List<String>) execution.getVariable("attachmentVars");

        if (attachmentVars == null || attachmentVars.isEmpty()) {
            throw new RuntimeException("No attachments found to process");
        }

        // Read classification results from MinIO (stage 3)
        StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);

        Map<String, List<Map<String, Object>>> documentTypes = new HashMap<>();
        List<String> splitDocumentVars = new ArrayList<>();

        for (String filename : attachmentVars) {
            // Build path to classifier result
            String classifierPath = String.format("%s/HealthClaim/%s/3_Doc_Classifier/Document_Classifier_%s.json",
                    tenantId, ticketId, filename);

            try {
                InputStream classifierStream = storage.downloadDocument(classifierPath);
                String classifierJson = new String(classifierStream.readAllBytes());
                JSONObject classifierResult = new JSONObject(classifierJson);

                // Extract the classification data
                if (classifierResult.has("extractedData")) {
                    JSONObject extractedData = classifierResult.getJSONObject("extractedData");

                    if (extractedData.has("Document_Classifier")) {
                        Object classifierData = extractedData.get("Document_Classifier");

                        // Convert to List<Map> for processing
                        List<Map<String, Object>> classifications = new ArrayList<>();

                        if (classifierData instanceof List) {
                            List<?> dataList = (List<?>) classifierData;
                            for (Object item : dataList) {
                                if (item instanceof Map) {
                                    classifications.add((Map<String, Object>) item);
                                }
                            }
                        } else if (classifierData instanceof JSONArray) {
                            JSONArray dataArray = (JSONArray) classifierData;
                            for (int i = 0; i < dataArray.length(); i++) {
                                JSONObject item = dataArray.getJSONObject(i);
                                Map<String, Object> map = new HashMap<>();
                                for (String key : item.keySet()) {
                                    map.put(key, item.get(key));
                                }
                                classifications.add(map);
                            }
                        }

                        if (!classifications.isEmpty()) {
                            documentTypes.put(filename, classifications);
                            // Add to split document vars list for multi-instance loop
                            splitDocumentVars.add(filename);
                            log.info("Loaded {} classifications for {}", classifications.size(), filename);
                        } else {
                            log.warn("No classifier output for file: {}, skipping", filename);
                        }
                    } else {
                        log.warn("No Document_Classifier data in extractedData for: {}", filename);
                    }
                } else {
                    log.warn("No extractedData in classifier result for: {}", filename);
                }

            } catch (Exception e) {
                log.error("Failed to load classifier result for {}: {}", filename, e.getMessage());
                log.warn("No classifier output for file: {}, skipping", filename);
            }
        }

        if (documentTypes.isEmpty()) {
            log.error("No document types found after classification");
            throw new RuntimeException("Document classification produced no results");
        }

        // Set BOTH variables for backward compatibility
        execution.setVariable("documentTypes", documentTypes);
        execution.setVariable("splitDocumentVars", splitDocumentVars);

        log.info("=== Doc Type Splitter Completed: {} files classified ===", documentTypes.size());
        log.debug("splitDocumentVars: {}", splitDocumentVars);
    }
}