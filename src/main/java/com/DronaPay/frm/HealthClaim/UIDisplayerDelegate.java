package com.DronaPay.frm.HealthClaim;

import com.DronaPay.frm.HealthClaim.generic.services.ObjectStorageService;
import com.DronaPay.frm.HealthClaim.generic.storage.StorageProvider;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class UIDisplayerDelegate implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("=== UI Displayer Started ===");

        String ticketId = String.valueOf(execution.getVariable("TicketID"));
        String tenantId = execution.getTenantId();

        @SuppressWarnings("unchecked")
        List<String> splitDocumentVars = (List<String>) execution.getVariable("splitDocumentVars");

        if (splitDocumentVars == null || splitDocumentVars.isEmpty()) {
            log.warn("No split documents found");
            execution.setVariable("uiDisplayData", new HashMap<>());
            return;
        }

        StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);
        Map<String, String> uiFields = new HashMap<>();

        // Read each OCR JSON file from MinIO
        for (String filename : splitDocumentVars) {
            String doctype = filename.substring(0, filename.lastIndexOf("."));
            String jsonPath = tenantId + "/HealthClaim/" + ticketId + "/ocr/" + doctype + ".json";

            try {
                InputStream jsonStream = storage.downloadDocument(jsonPath);
                byte[] jsonBytes = jsonStream.readAllBytes();
                jsonStream.close();

                String jsonString = new String(jsonBytes, StandardCharsets.UTF_8);
                JSONObject ocrResponse = new JSONObject(jsonString);

                if (ocrResponse.has("answer")) {
                    JSONObject answer = ocrResponse.getJSONObject("answer");

                    // Add doctype header
                    uiFields.put("--- " + doctype + " ---", "");

                    // Extract fields from answer - CHANGED: doctype -> doc_type
                    if (answer.has("doc_type")) {
                        uiFields.put(doctype + " - Document Type", answer.getString("doc_type"));
                    } else if (answer.has("doctype")) {
                        // Backward compatibility: support old format
                        uiFields.put(doctype + " - Document Type", answer.getString("doctype"));
                    }

                    if (answer.has("ocr_text")) {
                        String ocrText = answer.getString("ocr_text");
                        // Truncate if too long
                        if (ocrText.length() > 500) {
                            ocrText = ocrText.substring(0, 500) + "...";
                        }
                        uiFields.put(doctype + " - OCR Text", ocrText);
                    }
                }

                log.info("Loaded OCR data for: {}", doctype);

            } catch (Exception e) {
                log.error("Failed to load OCR data for: {}", doctype, e);
                uiFields.put(doctype + " - Error", "Failed to load OCR data");
            }
        }

        // Set process variables
        execution.setVariable("uiDisplayData", uiFields);
        setIndividualFormVariables(execution, uiFields);

        log.info("=== UI Displayer Completed with {} fields ===", uiFields.size());
    }

    private void setIndividualFormVariables(DelegateExecution execution, Map<String, String> uiFields) {
        for (Map.Entry<String, String> entry : uiFields.entrySet()) {
            String key = entry.getKey().replaceAll("[^a-zA-Z0-9_]", "_");
            execution.setVariable(key, entry.getValue());
        }
    }
}