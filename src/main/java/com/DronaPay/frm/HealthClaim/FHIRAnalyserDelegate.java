package com.DronaPay.frm.HealthClaim;

import com.DronaPay.frm.HealthClaim.generic.services.AgentResultStorageService;
import com.DronaPay.frm.HealthClaim.generic.services.ConfigurationService;
import com.DronaPay.frm.HealthClaim.APIServices;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.util.EntityUtils;
import org.cibseven.bpm.engine.delegate.BpmnError;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.json.JSONObject;

import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class FHIRAnalyserDelegate implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("=== FHIR Analyser Started ===");

        String ticketId = String.valueOf(execution.getVariable("TicketID"));
        String tenantId = execution.getTenantId();

        // 1. Get Consolidated FHIR
        String consolidatedFhirStr = (String) execution.getVariable("consolidatedFhir");

        // Robustness: If variable missing, try fetching from MinIO (Stage 8)
        if (consolidatedFhirStr == null) {
            log.info("consolidatedFhir variable missing, checking MinIO...");
            String path = (String) execution.getVariable("fhirConsolidatorMinioPath");

            // Fallback path if variable is also missing
            if (path == null) {
                path = String.format("%s/HealthClaim/%s/8_FHIR_Consolidator/consolidated_fhir.json",
                        tenantId, ticketId);
            }

            try {
                Map<String, Object> result = AgentResultStorageService.retrieveAgentResult(tenantId, path);
                String apiRespStr = (String) result.get("apiResponse");
                if (apiRespStr != null) {
                    JSONObject apiResp = new JSONObject(apiRespStr);
                    if (apiResp.has("answer")) {
                        consolidatedFhirStr = apiResp.getJSONObject("answer").toString();
                    }
                }
            } catch (Exception e) {
                log.error("Failed to retrieve consolidated FHIR from MinIO", e);
            }

            if (consolidatedFhirStr == null) {
                throw new BpmnError("DATA_MISSING", "Consolidated FHIR data not found");
            }
        }

        JSONObject consolidatedFhir = new JSONObject(consolidatedFhirStr);

        // 2. Prepare Request
        JSONObject requestBody = new JSONObject();
        JSONObject data = new JSONObject();
        data.put("consolidated_fhir", consolidatedFhir);
        requestBody.put("data", data);
        requestBody.put("agentid", "fhirAnalyser");

        // 3. Call API
        Connection conn = execution.getProcessEngine().getProcessEngineConfiguration().getDataSource().getConnection();
        JSONObject workflowConfig = ConfigurationService.loadWorkflowConfig("HealthClaim", tenantId, conn);
        conn.close();

        APIServices apiServices = new APIServices(tenantId, workflowConfig);
        CloseableHttpResponse response = apiServices.callAgent(requestBody.toString());
        String resp = EntityUtils.toString(response.getEntity());
        int statusCode = response.getStatusLine().getStatusCode();

        if (statusCode != 200) {
            throw new BpmnError("fhirAnalyserFailed", "Status: " + statusCode);
        }

        // 4. Extract Key Findings
        JSONObject responseJson = new JSONObject(resp);
        Map<String, Object> extractedData = new HashMap<>();
        if (responseJson.has("answer")) {
            JSONObject answer = responseJson.getJSONObject("answer");
            if (answer.has("medical_necessity")) {
                execution.setVariable("medical_necessity", answer.get("medical_necessity").toString());
            }
        }

        // 5. Store Result in MinIO
        Map<String, Object> fullResult = AgentResultStorageService.buildResultMap(
                "fhirAnalyser", statusCode, resp, extractedData);

        // CHANGED: Store in "9_FHIR_Analyser"
        String storedPath = AgentResultStorageService.storeAgentResultInStage(
                tenantId, ticketId, "analysis", "9_FHIR_Analyser", fullResult);

        log.info("FHIR Analysis completed. Stored at: {}", storedPath);

        execution.setVariable("fhirAnalyserMinioPath", storedPath);
    }
}