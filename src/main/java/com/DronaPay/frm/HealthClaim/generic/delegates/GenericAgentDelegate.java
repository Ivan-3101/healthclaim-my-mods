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

    private Expression url;
    private Expression method;
    private Expression agentId;
    private Expression inputParams;
    private Expression outputMapping;

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        String tenantId = execution.getTenantId();

        String rawAgentId = (agentId != null) ? (String) agentId.getValue(execution) : "unknown_agent";
        String currentAgentId = resolvePlaceholders(rawAgentId, execution, null);
        String stageName = execution.getCurrentActivityId();

        Object ticketIdObj = execution.getVariable("TicketID");
        String ticketId = (ticketIdObj != null) ? String.valueOf(ticketIdObj) : "UNKNOWN";

        log.info("=== Generic Agent Delegate Started: {} (Stage: {}) TicketID: {} ===", currentAgentId, stageName, ticketId);

        Properties props = TenantPropertiesUtil.getTenantProps(tenantId);
        String rawUrl = (url != null) ? (String) url.getValue(execution) : "${appproperties.agent.api.url}";
        String finalUrl = resolvePlaceholders(rawUrl, execution, props);
        String reqMethod = (method != null) ? (String) method.getValue(execution) : "POST";

        String apiUser = props.getProperty("agent.api.username");
        String apiPass = props.getProperty("agent.api.password");

        String inputJsonStr = (inputParams != null) ? (String) inputParams.getValue(execution) : "[]";
        String resolvedInputJson = resolvePlaceholders(inputJsonStr, execution, props);
        JSONObject dataObject = buildDataObject(resolvedInputJson, tenantId);

        JSONObject requestBody = new JSONObject();
        requestBody.put("agentid", currentAgentId);
        requestBody.put("data", dataObject);

        log.info("Calling Agent API: {} [{}]", finalUrl, reqMethod);
        String responseBody = executeAgentCall(reqMethod, finalUrl, apiUser, apiPass, requestBody.toString());

        Map<String, Object> resultMap = AgentResultStorageService.buildResultMap(
                currentAgentId, 200, responseBody, new HashMap<>());

        // --- FILENAME FIX START ---
        // We do NOT append .json manually anymore, as the StorageService likely adds it.
        String resultFileName = getResultFileName(execution, currentAgentId);
        // --- FILENAME FIX END ---

        String minioPath = AgentResultStorageService.storeAgentResult(
                tenantId, ticketId, stageName, resultFileName, resultMap);

        execution.setVariable(currentAgentId + "_MinioPath", minioPath);
        log.info("Agent Result saved to MinIO: {}", minioPath);

        String outputMapStr = (outputMapping != null) ? (String) outputMapping.getValue(execution) : "{}";
        mapOutputs(responseBody, outputMapStr, execution);

        log.info("=== Generic Agent Delegate Completed ===");
    }

    private String getResultFileName(DelegateExecution execution, String agentId) {
        String baseName = null;
        Object attachmentObj = execution.getVariable("attachment");

        if (attachmentObj != null) {
            if (attachmentObj instanceof String) {
                baseName = (String) attachmentObj;
            } else if (attachmentObj instanceof Map) {
                Map<?, ?> attMap = (Map<?, ?>) attachmentObj;
                if (attMap.containsKey("name")) {
                    baseName = attMap.get("name").toString();
                } else if (attMap.containsKey("fileName")) {
                    baseName = attMap.get("fileName").toString();
                }
            }
        }

        // Return the clean name (e.g. "Doc1.pdf").
        // Let the Storage Service handle the extension to avoid "Doc1.pdf.json.json"
        if (baseName != null && !baseName.isEmpty()) {
            return baseName;
        }

        // Fallback: Use Loop Counter
        Object loopCounter = execution.getVariable("loopCounter");
        if (loopCounter != null) {
            return agentId + "_result_" + loopCounter;
        }

        return agentId + "_result";
    }

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
                    InputStream fileContent = storage.downloadDocument(value);
                    String jsonContent = IOUtils.toString(fileContent, StandardCharsets.UTF_8);

                    String sourcePath = input.optString("sourceJsonPath", "");
                    if (!sourcePath.isEmpty()) {
                        Object extractedValue = JsonPath.read(jsonContent, sourcePath);
                        data.put(key, extractedValue);
                    } else {
                        data.put(key, new JSONObject(jsonContent));
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

    private void mapOutputs(String responseBody, String mappingStr, DelegateExecution execution) {
        if (mappingStr == null || mappingStr.equals("{}")) return;
        JSONObject mapping = new JSONObject(mappingStr);
        Object jsonDoc = com.jayway.jsonpath.Configuration.defaultConfiguration().jsonProvider().parse(responseBody);

        for (String varName : mapping.keySet()) {
            String path = mapping.getString(varName);
            try {
                Object val = JsonPath.read(jsonDoc, path);
                execution.setVariable(varName, val);
            } catch (Exception e) {
                log.warn("Could not extract path '{}' for variable '{}'", path, varName);
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
                if (val != null) {
                    replacement = val.toString();
                } else {
                    replacement = "${" + token + "}";
                }
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
        } catch (Exception e) {
            log.warn("Failed to resolve map expression: {}", token);
        }
        return "${" + token + "}";
    }
}