package com.DronaPay.frm.HealthClaim;

import com.DronaPay.frm.HealthClaim.generic.services.AgentResultStorageService;
import com.DronaPay.frm.HealthClaim.generic.services.ConfigurationService;
import com.DronaPay.frm.HealthClaim.generic.services.ObjectStorageService;
import com.DronaPay.frm.HealthClaim.generic.storage.StorageProvider;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.util.EntityUtils;
import org.cibseven.bpm.engine.delegate.BpmnError;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class UIDisplayerDelegate implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("=== UI Displayer Started ===");

        String tenantId = execution.getTenantId();
        String ticketId = String.valueOf(execution.getVariable("TicketID"));

        log.info("TicketID: {}, TenantID: {}", ticketId, tenantId);

        Connection conn = execution.getProcessEngine()
                .getProcessEngineConfiguration()
                .getDataSource()
                .getConnection();

        JSONObject workflowConfig = ConfigurationService.loadWorkflowConfig("HealthClaim", tenantId, conn);
        conn.close();

        String consolidatorMinioPath = (String) execution.getVariable("fhirConsolidatorMinioPath");

        if (consolidatorMinioPath == null || consolidatorMinioPath.trim().isEmpty()) {
            log.error("No fhirConsolidatorMinioPath found in process variables");
            throw new BpmnError("uiDisplayerFailed", "Missing fhirConsolidatorMinioPath");
        }

        log.info("Retrieving consolidated FHIR request from MinIO: {}", consolidatorMinioPath);

        StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);
        InputStream stream = storage.downloadDocument(consolidatorMinioPath);
        byte[] content = stream.readAllBytes();
        String jsonString = new String(content, StandardCharsets.UTF_8);

        JSONObject consolidatedJson = new JSONObject(jsonString);
        consolidatedJson.put("agentid", "UI_Displayer");
        String modifiedRequest = consolidatedJson.toString();

        if (modifiedRequest == null || modifiedRequest.trim().isEmpty()) {
            log.error("Empty consolidated request retrieved from MinIO");
            throw new BpmnError("uiDisplayerFailed", "Empty consolidated FHIR request in MinIO");
        }

        log.info("Retrieved consolidated FHIR request ({} bytes) from MinIO", modifiedRequest.length());
        log.info("Modified agentid to UI_Displayer");

        APIServices apiServices = new APIServices(tenantId, workflowConfig);
        CloseableHttpResponse response = apiServices.callAgent(modifiedRequest);

        String resp = EntityUtils.toString(response.getEntity());
        int statusCode = response.getStatusLine().getStatusCode();

        log.info("UI_Displayer API status: {}", statusCode);
        log.debug("UI_Displayer API response: {}", resp);

        if (statusCode != 200) {
            log.error("UI_Displayer agent failed with status: {}", statusCode);
            throw new BpmnError("uiDisplayerFailed", "UI_Displayer agent failed with status: " + statusCode);
        }

        Map<String, Object> fullResult = AgentResultStorageService.buildResultMap(
                "UI_Displayer", statusCode, resp, new HashMap<>());

        String uiDisplayerMinioPath = AgentResultStorageService.storeAgentResultStageWise(
                tenantId, ticketId, "consolidated", "UI_Displayer", fullResult);

        log.info("Stored UI_Displayer result at: {}", uiDisplayerMinioPath);

        execution.setVariable("uiDisplayerMinioPath", uiDisplayerMinioPath);
        execution.setVariable("uiDisplayerSuccess", true);

        log.info("=== UI Displayer Completed ===");
    }
}