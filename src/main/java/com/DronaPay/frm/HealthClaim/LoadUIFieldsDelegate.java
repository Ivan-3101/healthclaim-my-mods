package com.DronaPay.frm.HealthClaim;

import com.DronaPay.frm.HealthClaim.generic.services.AgentResultStorageService;
import lombok.extern.slf4j.Slf4j;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
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

        // Parse and include ALL fields (even with fetch_status="No" or null values)
        JSONObject responseJson = new JSONObject(rawResponse);
        JSONArray answerArray = responseJson.getJSONArray("answer");
        JSONArray displayFields = new JSONArray();

        for (int i = 0; i < answerArray.length(); i++) {
            JSONObject field = answerArray.getJSONObject(i);
            String fetchStatus = field.optString("fetch_status", "No");
            Object valueObj = field.opt("value");

            // Include ALL fields regardless of fetch_status or value
            JSONObject displayField = new JSONObject();
            displayField.put("label", field.getString("field_name"));

            // Handle null values - display empty string
            if (valueObj == null || JSONObject.NULL.equals(valueObj)) {
                displayField.put("value", "");
            } else {
                displayField.put("value", valueObj.toString());
            }

            displayField.put("docType", field.optString("doc_type", ""));
            displayField.put("fetchStatus", fetchStatus);

            displayFields.put(displayField);
        }

        // Set as process variable
        String fieldsJson = displayFields.toString();
        execution.setVariable("uiFieldsJson", fieldsJson);

        log.info("Loaded {} UI fields for display (all fields included)", displayFields.length());
    }
}