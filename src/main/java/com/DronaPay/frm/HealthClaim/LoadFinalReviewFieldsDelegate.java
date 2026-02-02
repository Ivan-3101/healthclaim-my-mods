package com.DronaPay.frm.HealthClaim;

import com.DronaPay.frm.HealthClaim.generic.services.AgentResultStorageService;
import lombok.extern.slf4j.Slf4j;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;

@Slf4j
public class LoadFinalReviewFieldsDelegate implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("=== Load Final Review Fields Started ===");

        String tenantId = execution.getTenantId();

        // Get UI Displayer data (edited if available, otherwise original)
        String uiDisplayerData = getUIDisplayerData(execution, tenantId);

        if (uiDisplayerData != null) {
            JSONObject uiJson = new JSONObject(uiDisplayerData);
            JSONArray answerArray = uiJson.getJSONArray("answer");
            JSONArray displayFields = new JSONArray();

            for (int i = 0; i < answerArray.length(); i++) {
                JSONObject field = answerArray.getJSONObject(i);

                JSONObject displayField = new JSONObject();
                displayField.put("label", field.getString("field_name"));

                Object valueObj = field.opt("value");
                if (valueObj == null || JSONObject.NULL.equals(valueObj)) {
                    displayField.put("value", "");
                } else {
                    displayField.put("value", valueObj.toString());
                }

                displayField.put("docType", field.optString("doc_type", ""));
                displayField.put("fetchStatus", field.optString("fetch_status", "No"));

                displayFields.put(displayField);
            }

            execution.setVariable("finalReviewFields", displayFields.toString());
            log.info("Loaded {} UI fields for final review", displayFields.length());
        } else {
            execution.setVariable("finalReviewFields", "[]");
            log.warn("No UI displayer data found");
        }

        // DO NOT store the full output - paths are already set by the delegates
        log.info("Policy Coherence Path: {}", execution.getVariable("policyCoherenceMinioPath"));
        log.info("Medical Coherence Path: {}", execution.getVariable("medicalCoherenceMinioPath"));

        log.info("=== Load Final Review Fields Completed ===");
    }

    private String getUIDisplayerData(DelegateExecution execution, String tenantId) throws Exception {
        // First try edited version
        String editedFormMinioPath = (String) execution.getVariable("editedFormMinioPath");

        if (editedFormMinioPath != null && !editedFormMinioPath.trim().isEmpty()) {
            log.info("Using edited UI displayer data from: {}", editedFormMinioPath);
            Map<String, Object> result = AgentResultStorageService.retrieveAgentResult(tenantId, editedFormMinioPath);
            return (String) result.get("apiResponse");
        }

        // Fallback to original
//        String uiDisplayerMinioPath = (String) execution.getVariable("uiDisplayerMinioPath");
        String uiDisplayerMinioPath = (String) execution.getVariable("UI_Displayer_MinioPath");
        if (uiDisplayerMinioPath != null && !uiDisplayerMinioPath.trim().isEmpty()) {
            log.info("Using original UI displayer data from: {}", uiDisplayerMinioPath);
            Map<String, Object> result = AgentResultStorageService.retrieveAgentResult(tenantId, uiDisplayerMinioPath);
            return (String) result.get("apiResponse");
        }

        return null;
    }
}