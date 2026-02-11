// GenericAgentDelegate.java - MODIFIED VERSION
// CHANGES FROM ORIGINAL:
// 1. REMOVED: private Expression url;
// 2. ADDED: private Expression baseUrl, route, authType
// 3. MODIFIED: execute() method to build URL from components
// 4. ADDED: buildUrl() and getAuthCredentials() helper methods

package com.DronaPay.frm.HealthClaim.generic.delegates;

import com.DronaPay.frm.HealthClaim.TenantPropertiesUtil;
import com.DronaPay.frm.HealthClaim.generic.services.AgentResultStorageService;
import com.DronaPay.frm.HealthClaim.generic.services.ObjectStorageService;
import com.DronaPay.frm.HealthClaim.generic.storage.StorageProvider;
import com.jayway.jsonpath.JsonPath;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.cibseven.bpm.engine.delegate.BpmnError;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.Expression;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class GenericAgentDelegate implements JavaDelegate {

    // ============ MODIFIED FIELDS ============
    // REMOVED: private Expression url;
    // ADDED: baseUrl, route, authType
    private Expression baseUrl;      // NEW: "dia", "springapi", etc.
    private Expression route;        // NEW: "agent", "policy", etc.
    private Expression authType;     // NEW: "basicAuth", "apiKey", etc.

    // UNCHANGED FIELDS
    private Expression method;
    private Expression agentId;
    private Expression inputParams;
    private Expression outputMapping;

    @Override
    public void execute(DelegateExecution execution) throws Exception {

        // STEP 1: SETUP
        String tenantId = execution.getTenantId();
        String rawAgentId = (agentId != null) ? (String) agentId.getValue(execution) : "unknown_agent";
        String currentAgentId = resolvePlaceholders(rawAgentId, execution, null);
        String stageName = execution.getCurrentActivityId();

        Object ticketIdObj = execution.getVariable("TicketID");
        String ticketId = (ticketIdObj != null) ? String.valueOf(ticketIdObj) : "UNKNOWN";

        log.info("=== Generic Agent Delegate v2 Started: {} (Stage: {}) TicketID: {} ===", currentAgentId, stageName, ticketId);

        // Load properties
        Properties props = TenantPropertiesUtil.getTenantProps(tenantId);

        // ============ MODIFIED URL AND AUTH CONSTRUCTION ============
        // OLD CODE:
        // String rawUrl = (url != null) ? (String) url.getValue(execution) : "${appproperties.agent.api.url}";
        // String finalUrl = resolvePlaceholders(rawUrl, execution, props);
        // String apiUser = props.getProperty("agent.api.username");
        // String apiPass = props.getProperty("agent.api.password");

        // NEW CODE:
        String baseUrlKey = (baseUrl != null) ? (String) baseUrl.getValue(execution) : "dia";
        String routeKey = (route != null) ? (String) route.getValue(execution) : "agent";
        String authTypeKey = (authType != null) ? (String) authType.getValue(execution) : "basicAuth";

        // Build URL from components
        String finalUrl = buildUrl(baseUrlKey, routeKey, props);

        // Get auth credentials based on authType
        Map<String, String> authCreds = getAuthCredentials(authTypeKey, props);
        String apiUser = authCreds.get("username");
        String apiPass = authCreds.get("password");
        // ============ END MODIFIED SECTION ============

        String reqMethod = (method != null) ? (String) method.getValue(execution) : "POST";

        // STEP 2: BUILD REQUEST PAYLOAD (UNCHANGED)
        String inputJsonStr = (inputParams != null) ? (String) inputParams.getValue(execution) : "[]";
        String resolvedInputJson = resolvePlaceholders(inputJsonStr, execution, props);
        JSONObject dataObject = buildDataObject(resolvedInputJson, tenantId);

        JSONObject requestBody = new JSONObject();
        requestBody.put("agentid", currentAgentId);
        requestBody.put("data", dataObject);

        // STEP 3: EXECUTE API CALL (UNCHANGED)
        log.info("Calling Agent API: {} [{}]", finalUrl, reqMethod);
        String responseBody = executeAgentCall(reqMethod, finalUrl, apiUser, apiPass, requestBody.toString());

        // STEP 4: AUDIT TRAIL (UNCHANGED)
        Map<String, Object> resultMap = AgentResultStorageService.buildResultMap(
                currentAgentId, 200, responseBody, new HashMap<>());

        Object attachmentObj = execution.getVariable("attachment");
        String filename = null;
        if (attachmentObj != null) {
            filename = attachmentObj.toString();
        }
        String resultFileName = (filename != null) ? filename : currentAgentId + "_result";

        String minioPath = AgentResultStorageService.storeAgentResult(
                tenantId, ticketId, stageName, resultFileName, resultMap);

        execution.setVariable(currentAgentId + "_MinioPath", minioPath);
        log.info("Agent Result saved to MinIO: {}", minioPath);

        // STEP 5: OUTPUT MAPPING (UNCHANGED)
        String outputMapStr = (outputMapping != null) ? (String) outputMapping.getValue(execution) : "{}";
        mapOutputs(responseBody, outputMapStr, execution);

        log.info("=== Generic Agent Delegate Completed ===");
    }

    // ============ NEW HELPER METHOD ============
    /**
     * Builds the full URL from base URL key and route key.
     */
    private String buildUrl(String baseUrlKey, String routeKey, Properties props) {
        String base;

        switch(baseUrlKey) {
            case "dia":
                // Use the hardcoded URL for now (can be moved to DB config later)
                base = "https://main.dev.dronapay.net/dia/";
                break;
            // Future: Add more base URLs
            // case "springapi":
            //     base = "https://main.dev.dronapay.net/springapi/";
            //     break;
            default:
                throw new IllegalArgumentException("Unknown baseUrl: " + baseUrlKey);
        }

        // routeKey is directly entered by user (e.g., "agent", "policy")
        // Just append it to the base URL
        String finalUrl = base + routeKey;
        log.info("Constructed URL: {} + {} = {}", base, routeKey, finalUrl);

        return finalUrl;
    }

    // ============ NEW HELPER METHOD ============
    /**
     * Gets authentication credentials based on auth type.
     */
    private Map<String, String> getAuthCredentials(String authType, Properties props) {
        Map<String, String> creds = new HashMap<>();

        switch(authType) {
            case "basicAuth":
                creds.put("username", props.getProperty("agent.api.username"));
                creds.put("password", props.getProperty("agent.api.password"));
                log.info("Using Basic Auth with username: {}", creds.get("username"));
                break;
            // Future: Add API Key auth
            // case "apiKey":
            //     creds.put("apiKey", props.getProperty("agent.api.key"));
            //     break;
            default:
                throw new IllegalArgumentException("Unknown authType: " + authType);
        }

        return creds;
    }

    // ============ ALL REMAINING METHODS UNCHANGED ============
    // (Keep all existing helper methods exactly as they are)

    private JSONObject buildDataObject(String inputJsonStr, String tenantId) throws Exception {
        JSONObject data = new JSONObject();
        JSONArray inputs = new JSONArray(inputJsonStr);
        StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);

        for (int i = 0; i < inputs.length(); i++) {
            JSONObject input = inputs.getJSONObject(i);
            String key = input.getString("key");
            String type = input.optString("type", "value");
            String value = input.optString("value", "");

            if (value.isEmpty()) continue;

            if ("minioFile".equalsIgnoreCase(type)) {
                try {
                    InputStream fileContent = storage.downloadDocument(value);
                    byte[] bytes = IOUtils.toByteArray(fileContent);
                    String base64 = Base64.getEncoder().encodeToString(bytes);
                    data.put(key, base64);
                } catch (Exception e) {
                    log.error("Failed to download/encode file at path: {}", value, e);
                    throw new BpmnError("FILE_ERROR", "Could not process file: " + value);
                }
            } else if ("minioJson".equalsIgnoreCase(type)) {
                try {
                    Map<String, Object> result = AgentResultStorageService.retrieveAgentResult(tenantId, value);
                    String apiResponse = (String) result.get("apiResponse");

                    if (apiResponse == null || apiResponse.trim().isEmpty()) {
                        throw new Exception("Empty apiResponse from: " + value);
                    }

                    String sourcePath = input.optString("sourceJsonPath", "");
                    if (!sourcePath.isEmpty()) {
                        Object extractedContent = JsonPath.read(apiResponse, sourcePath);
                        data.put(key, extractedContent);
                    } else {
                        JSONObject jsonContent = new JSONObject(apiResponse);
                        data.put(key, jsonContent);
                    }
                } catch (Exception e) {
                    log.error("Failed to download/parse JSON at path: {}", value, e);
                    throw new BpmnError("FILE_ERROR", "Could not process JSON: " + value);
                }
            } else {
                data.put(key, value);
            }
        }
        return data;
    }

    private String executeAgentCall(String method, String url, String user, String pass, String body) throws Exception {
        CredentialsProvider provider = new BasicCredentialsProvider();
        provider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(user, pass));

        try (CloseableHttpClient client = HttpClients.custom().setDefaultCredentialsProvider(provider).build()) {
            HttpRequestBase request;

            if ("GET".equalsIgnoreCase(method)) {
                request = new HttpGet(url);
            } else {
                HttpPost post = new HttpPost(url);
                post.setEntity(new StringEntity(body, StandardCharsets.UTF_8));
                post.setHeader("Content-Type", "application/json");
                request = post;
            }

            try (CloseableHttpResponse response = client.execute(request)) {
                int status = response.getStatusLine().getStatusCode();
                String respStr = EntityUtils.toString(response.getEntity());

                if (status != 200) {
                    throw new BpmnError("AGENT_ERROR", "Agent returned " + status + ": " + respStr);
                }
                return respStr;
            }
        }
    }

    private void mapOutputs(String responseBody, String outputMapStr, DelegateExecution execution) throws Exception {
        if (outputMapStr == null || outputMapStr.trim().isEmpty() || outputMapStr.equals("{}")) {
            return;
        }

        JSONObject outputMap = new JSONObject(outputMapStr);
        for (String varName : outputMap.keySet()) {
            String jsonPath = outputMap.getString(varName);
            try {
                Object value = JsonPath.read(responseBody, jsonPath);
                execution.setVariable(varName, value);
                log.info("Mapped output: {} = {}", varName, value);
            } catch (Exception e) {
                log.warn("Failed to map output variable '{}' using path '{}': {}", varName, jsonPath, e.getMessage());
            }
        }
    }

    private String resolvePlaceholders(String input, DelegateExecution execution, Properties props) {
        if (input == null) return null;

        Pattern pattern = Pattern.compile("\\$\\{([^}]+)}");
        Matcher matcher = pattern.matcher(input);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String placeholder = matcher.group(1);
            String replacement = null;

            if (execution.hasVariable(placeholder)) {
                Object val = execution.getVariable(placeholder);
                replacement = val != null ? val.toString() : "";
            } else if (props != null && placeholder.startsWith("appproperties.")) {
                String propKey = placeholder.substring("appproperties.".length());
                replacement = props.getProperty(propKey, "");
            }

            if (replacement == null) {
                replacement = "";
            }

            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}