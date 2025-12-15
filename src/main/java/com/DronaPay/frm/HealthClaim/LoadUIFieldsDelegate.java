package com.DronaPay.frm.HealthClaim;

import com.DronaPay.frm.HealthClaim.generic.services.AgentResultStorageService;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;

@Slf4j
public class LoadUIFieldsDelegate implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("=== Load UI Fields Started ===");

        String tenantId = execution.getTenantId();
        String uiDisplayerMinioPath = (String) execution.getVariable("uiDisplayerMinioPath");

        if (uiDisplayerMinioPath == null || uiDisplayerMinioPath.trim().isEmpty()) {
            log.warn("No uiDisplayerMinioPath found");
            execution.setVariable("uiFieldsJson", "[]");
            return;
        }

        log.info("Retrieving UI_Displayer output from: {}", uiDisplayerMinioPath);

        // Retrieve from MinIO
        Map<String, Object> result = AgentResultStorageService.retrieveAgentResult(tenantId, uiDisplayerMinioPath);
        String rawResponse = (String) result.get("apiResponse");

        if (rawResponse == null || rawResponse.trim().isEmpty()) {
            log.error("Empty UI_Displayer response from MinIO");
            execution.setVariable("uiFieldsJson", "[]");
            return;
        }

        // Parse and filter fields
        JSONObject responseJson = new JSONObject(rawResponse);
        JSONArray answerArray = responseJson.getJSONArray("answer");
        JSONArray displayFields = new JSONArray();

        for (int i = 0; i < answerArray.length(); i++) {
            JSONObject field = answerArray.getJSONObject(i);
            String fetchStatus = field.optString("fetch_status", "No");
            Object valueObj = field.opt("value");

            // Only include fields with fetch_status="Yes" and non-null values
            if ("Yes".equalsIgnoreCase(fetchStatus) && valueObj != null && !JSONObject.NULL.equals(valueObj)) {
                JSONObject displayField = new JSONObject();
                displayField.put("label", field.getString("field_name"));
                displayField.put("value", valueObj.toString());
                displayField.put("docType", field.optString("doc_type", ""));
                displayFields.put(displayField);
            }
        }

        // Set as process variable
        String fieldsJson = displayFields.toString();
        execution.setVariable("uiFieldsJson", fieldsJson);

        log.info("Loaded {} UI fields for display", displayFields.length());
    }
}