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
import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.bpm.engine.variable.value.ObjectValue;

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

/**
 * A Generic Agent Delegate that executes external API calls based on a BPMN Element Template.
 * It handles:
 * 1. Dynamic URL construction (Base URL + Route)
 * 2. Authentication (Basic Auth)
 * 3. Input payload construction (including file downloads from MinIO)
 * 4. Storing results back to MinIO and mapping outputs to process variables.
 */
@Slf4j
public class GenericAgentDelegate implements JavaDelegate {

    // Fields injected from the BPMN Element Template (v2)
    private Expression baseUrl;      // Key for the base URL (e.g., "dia" -> "agent.api.url")
    private Expression route;        // Specific API route (e.g., "agent")
    private Expression authType;     // Authentication method (e.g., "basicAuth")

    // Standard configuration fields
    private Expression method;       // HTTP Method (POST/GET)
    private Expression agentId;      // Unique Identifier for this agent task
    private Expression inputParams;  // JSON Array defining input data structure
    private Expression outputMapping;// JSON Object defining response mapping

    @Override
    public void execute(DelegateExecution execution) throws Exception {

        // 1. Initialize Context
        String tenantId = execution.getTenantId();
        String rawAgentId = (agentId != null) ? (String) agentId.getValue(execution) : "unknown_agent";
        String currentAgentId = resolvePlaceholders(rawAgentId, execution, null);
        String stageName = execution.getCurrentActivityId();

        Object ticketIdObj = execution.getVariable("TicketID");
        String ticketId = (ticketIdObj != null) ? String.valueOf(ticketIdObj) : "UNKNOWN";

        log.info("=== Generic Agent Delegate Started: {} (Stage: {}) TicketID: {} ===", currentAgentId, stageName, ticketId);

        Properties props = TenantPropertiesUtil.getTenantProps(tenantId);

        // 2. Validate Required Configuration
        if (baseUrl == null || route == null) {
            throw new BpmnError("CONFIG_ERROR", "Missing required fields: 'baseUrl' and 'route'. Ensure the BPMN task uses the correct template.");
        }

        String baseUrlKey = (String) baseUrl.getValue(execution);
        String routePath = (String) route.getValue(execution);
        String authMethod = (authType != null) ? (String) authType.getValue(execution) : "none";

        // 3. Determine Property Prefix
        String propPrefix = baseUrlKey;
        if ("dia".equalsIgnoreCase(baseUrlKey)) {
            propPrefix = "agent.api";
        }

        // 4. Construct Full API URL
        String resolvedBaseUrl = props.getProperty(propPrefix + ".url");
        if (resolvedBaseUrl == null) {
            throw new BpmnError("CONFIG_ERROR", "Property not found: " + propPrefix + ".url");
        }

        if (resolvedBaseUrl.endsWith("/")) {
            resolvedBaseUrl = resolvedBaseUrl.substring(0, resolvedBaseUrl.length() - 1);
        }
        if (routePath.startsWith("/")) {
            routePath = routePath.substring(1);
        }

        String finalUrl = resolvedBaseUrl + "/" + routePath;

        // 5. Configure Authentication
        String apiUser = null;
        String apiPass = null;

        if ("basicAuth".equalsIgnoreCase(authMethod)) {
            apiUser = props.getProperty(propPrefix + ".username");
            apiPass = props.getProperty(propPrefix + ".password");

            // Fallback
            if (apiUser == null) apiUser = props.getProperty("ai.agent.username");
            if (apiPass == null) apiPass = props.getProperty("ai.agent.password");

            if (apiUser == null || apiPass == null) {
                log.warn("Auth enabled but credentials missing for prefix: {}", propPrefix);
            }
        }

        String reqMethod = (method != null) ? (String) method.getValue(execution) : "POST";

        // 6. Build Request Payload
        String inputJsonStr = (inputParams != null) ? (String) inputParams.getValue(execution) : "[]";
        String resolvedInputJson = resolvePlaceholders(inputJsonStr, execution, props);
        JSONObject dataObject = buildDataObject(resolvedInputJson, tenantId);

        JSONObject requestBody = new JSONObject();
        requestBody.put("agentid", currentAgentId);
        requestBody.put("data", dataObject);

        // 7. Execute External API Call
        log.info("Calling Agent API: {} [{}]", finalUrl, reqMethod);
        String responseBody = executeAgentCall(reqMethod, finalUrl, apiUser, apiPass, requestBody.toString());

        // 8. Store Results in MinIO
        Map<String, Object> resultMap = AgentResultStorageService.buildResultMap(
                currentAgentId, 200, responseBody, new HashMap<>());

        Object attachmentObj = execution.getVariable("attachment");
        String filename = (attachmentObj != null) ? attachmentObj.toString() : currentAgentId + "_result";
        String resultFileName = (filename != null && !filename.isEmpty()) ? filename : currentAgentId + "_result_" + System.currentTimeMillis();

        String minioPath = AgentResultStorageService.storeAgentResult(
                tenantId, ticketId, stageName, resultFileName, resultMap);

        execution.setVariable(currentAgentId + "_MinioPath", minioPath);
        log.info("Agent Result stored: {}", minioPath);

        // 9. Map Output Variables (Updated with FIX)
        String outputMapStr = (outputMapping != null) ? (String) outputMapping.getValue(execution) : "{}";
        mapOutputs(responseBody, outputMapStr, execution);

        log.info("=== Generic Agent Delegate Completed ===");
    }

    /**
     * Constructs the 'data' JSON object expected by the Agent API.
     */
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
                    log.error("Failed to download file from MinIO: {}", value, e);
                    throw new BpmnError("FILE_ERROR", "Could not process file: " + value);
                }
            }
            else if ("minioJson".equalsIgnoreCase(type)) {
                try {
                    Map<String, Object> result = AgentResultStorageService.retrieveAgentResult(tenantId, value);
                    String apiResponse = (String) result.get("apiResponse");

                    if (apiResponse == null) throw new Exception("Empty apiResponse");

                    String sourcePath = input.optString("sourceJsonPath", "");
                    if (!sourcePath.isEmpty()) {
                        Object extractedContent = JsonPath.read(apiResponse, sourcePath);
                        data.put(key, extractedContent);
                    } else {
                        JSONObject jsonContent = new JSONObject(apiResponse);
                        data.put(key, jsonContent);
                    }
                } catch (Exception e) {
                    log.error("Failed to process previous Agent Result: {}", value, e);
                    throw new BpmnError("FILE_ERROR", "Could not process JSON: " + value);
                }
            }
            else {
                data.put(key, value);
            }
        }
        return data;
    }

    /**
     * Executes the HTTP request.
     */
    private String executeAgentCall(String method, String url, String user, String pass, String body) throws Exception {
        CredentialsProvider provider = new BasicCredentialsProvider();
        if (user != null && pass != null) {
            provider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(user, pass));
        }

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

    /**
     * Maps fields from the API response to Process Variables.
     * UPDATED: Uses Variables.objectValue(...).serializationDataFormat("application/json")
     * to prevent "Cannot load class" errors in Cockpit.
     */
    private void mapOutputs(String responseBody, String mappingStr, DelegateExecution execution) {
        if (mappingStr == null || mappingStr.equals("{}")) return;
        JSONObject mapping = new JSONObject(mappingStr);
        Object jsonDoc = com.jayway.jsonpath.Configuration.defaultConfiguration().jsonProvider().parse(responseBody);

        for (String varName : mapping.keySet()) {
            String path = mapping.getString(varName);
            try {
                Object val = JsonPath.read(jsonDoc, path);

                // --- FIX START: Force serialization as JSON ---
                if (val != null) {
                    ObjectValue typedValue = Variables.objectValue(val)
                            .serializationDataFormat("application/json")
                            .create();
                    execution.setVariable(varName, typedValue);
                } else {
                    execution.setVariable(varName, null);
                }
                // --- FIX END ---

            } catch (Exception e) {
                log.warn("Could not extract path '{}' for variable '{}'. Field might be missing in response.", path, varName);
            }
        }
    }

    private String resolvePlaceholders(String input, DelegateExecution execution, Properties props) {
        if (input == null) return null;
        Pattern pattern = Pattern.compile("\\$\\{([^}]+)\\}");
        Matcher matcher = pattern.matcher(input);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String token = matcher.group(1).trim();
            String replacement = "";

            if (token.startsWith("appproperties.") && props != null) {
                String key = token.substring("appproperties.".length());
                replacement = props.getProperty(key, "");
            } else if (token.contains("[") && token.endsWith("]")) {
                replacement = resolveMapValue(token, execution);
            } else {
                String varName = token.startsWith("processVariable.") ? token.substring("processVariable.".length()) : token;
                Object val = execution.getVariable(varName);
                replacement = (val != null) ? val.toString() : "${" + token + "}";
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String resolveMapValue(String token, DelegateExecution execution) {
        try {
            int bracketIndex = token.indexOf("[");
            String mapName = token.substring(0, bracketIndex);
            String keyVarName = token.substring(bracketIndex + 1, token.length() - 1);
            Object mapObj = execution.getVariable(mapName);
            Object keyValObj = execution.getVariable(keyVarName);
            String resolvedKey = (keyValObj != null) ? keyValObj.toString() : keyVarName;

            if (mapObj instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) mapObj;
                Object value = map.get(resolvedKey);
                return value != null ? value.toString() : "";
            }
        } catch (Exception e) { }
        return "${" + token + "}";
    }
}