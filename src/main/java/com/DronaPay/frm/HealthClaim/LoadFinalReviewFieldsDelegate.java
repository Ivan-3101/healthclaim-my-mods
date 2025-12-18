package com.DronaPay.frm.HealthClaim;

import com.DronaPay.frm.HealthClaim.generic.services.AgentResultStorageService;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;

@Slf4j
public class LoadFinalReviewFieldsDelegate implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("=== Load Final Review Fields Started ===");

        String tenantId = execution.getTenantId();

        // 1. Get UI Displayer data (edited if available, otherwise original)
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

        // 2. Get Policy Coherence output
        String policyCoherenceMinioPath = (String) execution.getVariable("policyCoherenceMinioPath");
        if (policyCoherenceMinioPath != null && !policyCoherenceMinioPath.trim().isEmpty()) {
            Map<String, Object> policyResult = AgentResultStorageService.retrieveAgentResult(tenantId, policyCoherenceMinioPath);
            String policyResponse = (String) policyResult.get("apiResponse");

            if (policyResponse != null && !policyResponse.trim().isEmpty()) {
                JSONObject policyJson = new JSONObject(policyResponse);
                execution.setVariable("policyCoherenceOutput", policyJson.toString());
                log.info("Loaded Policy Coherence output");
            }
        } else {
            execution.setVariable("policyCoherenceOutput", "{}");
            log.warn("No Policy Coherence output found");
        }

        // 3. Get Medical Coherence output
        String medicalCoherenceMinioPath = (String) execution.getVariable("medicalCoherenceMinioPath");
        if (medicalCoherenceMinioPath != null && !medicalCoherenceMinioPath.trim().isEmpty()) {
            Map<String, Object> medicalResult = AgentResultStorageService.retrieveAgentResult(tenantId, medicalCoherenceMinioPath);
            String medicalResponse = (String) medicalResult.get("apiResponse");

            if (medicalResponse != null && !medicalResponse.trim().isEmpty()) {
                JSONObject medicalJson = new JSONObject(medicalResponse);
                execution.setVariable("medicalCoherenceOutput", medicalJson.toString());
                log.info("Loaded Medical Coherence output");
            }
        } else {
            execution.setVariable("medicalCoherenceOutput", "{}");
            log.warn("No Medical Coherence output found");
        }

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
        String uiDisplayerMinioPath = (String) execution.getVariable("uiDisplayerMinioPath");

        if (uiDisplayerMinioPath != null && !uiDisplayerMinioPath.trim().isEmpty()) {
            log.info("Using original UI displayer data from: {}", uiDisplayerMinioPath);
            Map<String, Object> result = AgentResultStorageService.retrieveAgentResult(tenantId, uiDisplayerMinioPath);
            return (String) result.get("apiResponse");
        }

        return null;
    }
}