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

    // These fields are injected directly from the BPMN Element Template (generic-agent.json)
    private Expression url;          // The API endpoint (e.g., ${appproperties.agent.api.url})
    private Expression method;       // POST or GET
    private Expression agentId;      // Unique Identifier (e.g., "UI_Displayer", "Submission_Validator")
    private Expression inputParams;  // JSON Array defining what data to send to the agent
    private Expression outputMapping;// JSON Object defining how to map response fields back to variables

    @Override
    public void execute(DelegateExecution execution) throws Exception {

        // STEP 1: SETUP
        String tenantId = execution.getTenantId();
        String rawAgentId = (agentId != null) ? (String) agentId.getValue(execution) : "unknown_agent";

        // Resolve placeholders in Agent ID (e.g., if ID depends on a variable)
        String currentAgentId = resolvePlaceholders(rawAgentId, execution, null);
        String stageName = execution.getCurrentActivityId();

        // Retrieve TicketID for logging and storage paths
        Object ticketIdObj = execution.getVariable("TicketID");
        String ticketId = (ticketIdObj != null) ? String.valueOf(ticketIdObj) : "UNKNOWN";

        log.info("=== Generic Agent Delegate Started: {} (Stage: {}) TicketID: {} ===", currentAgentId, stageName, ticketId);

        // Load environment-specific properties (Dev/QA/Prod URLs)
        Properties props = TenantPropertiesUtil.getTenantProps(tenantId);
        String rawUrl = (url != null) ? (String) url.getValue(execution) : "${appproperties.agent.api.url}";
        String finalUrl = resolvePlaceholders(rawUrl, execution, props); // Resolves ${appproperties...}
        String reqMethod = (method != null) ? (String) method.getValue(execution) : "POST";
        String apiUser = props.getProperty("agent.api.username");
        String apiPass = props.getProperty("agent.api.password");

        // STEP 2: BUILD REQUEST PAYLOAD
        // Read input configuration from the template
        String inputJsonStr = (inputParams != null) ? (String) inputParams.getValue(execution) : "[]";

        // Resolve variables inside the input configuration (e.g., ${fhirConsolidatorMinioPath})
        String resolvedInputJson = resolvePlaceholders(inputJsonStr, execution, props);

        // Construct the 'data' object. This method handles file downloads and JSON extraction.
        JSONObject dataObject = buildDataObject(resolvedInputJson, tenantId);

        // Wrap the payload in the standard envelope: { "agentid": "...", "data": { ... } }
        JSONObject requestBody = new JSONObject();
        requestBody.put("agentid", currentAgentId);
        requestBody.put("data", dataObject);

        // STEP 3: EXECUTE API CALL
        log.info("Calling Agent API: {} [{}]", finalUrl, reqMethod);

        // Log the full request for debugging (Crucial for verifying data structure)
        log.debug(">>> Request Body for {}: {}", currentAgentId, requestBody);

        String responseBody = executeAgentCall(reqMethod, finalUrl, apiUser, apiPass, requestBody.toString());

        // STEP 4: AUDIT TRAIL
        Map<String, Object> resultMap = AgentResultStorageService.buildResultMap(
                currentAgentId, 200, responseBody, new HashMap<>());

        // Determine filename (use attachment name if inside a loop, else generic name)
        Object attachmentObj = execution.getVariable("attachment");
        String filename = null;
        if (attachmentObj != null) {
            filename = attachmentObj.toString();
        }
        String resultFileName = (filename != null) ? filename : currentAgentId + "_result";

        String minioPath = AgentResultStorageService.storeAgentResult(
                tenantId, ticketId, stageName, resultFileName, resultMap);

        // Save the MinIO path to a Process Variable so downstream tasks can find it.
        // Naming Convention: [AgentID]_MinioPath (e.g., "UI_Displayer_MinioPath")
        execution.setVariable(currentAgentId + "_MinioPath", minioPath);
        log.info("Agent Result saved to MinIO: {}", minioPath);

        // STEP 5: OUTPUT MAPPING
        // Map specific fields from the JSON response directly to Process Variables
        // based on the 'Output Mapping' configuration in the template.
        String outputMapStr = (outputMapping != null) ? (String) outputMapping.getValue(execution) : "{}";
        mapOutputs(responseBody, outputMapStr, execution);

        log.info("=== Generic Agent Delegate Completed ===");
    }

    /**
     * Builds the 'data' object for the API request.
     * Handles complex types like 'minioFile' (Base64 encoding) and 'minioJson' (Parsing).
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

            // CASE 1: File from MinIO (e.g., for OCR or Vision agents)
            if ("minioFile".equalsIgnoreCase(type)) {
                try {
                    InputStream fileContent = storage.downloadDocument(value);
                    byte[] bytes = IOUtils.toByteArray(fileContent);
                    // Convert binary file to Base64 string for JSON transport
                    String base64 = Base64.getEncoder().encodeToString(bytes);
                    data.put(key, base64);
                } catch (Exception e) {
                    log.error("Failed to download/encode file at path: {}", value, e);
                    throw new BpmnError("FILE_ERROR", "Could not process file: " + value);
                }
            }
            // CASE 2: JSON from MinIO (e.g., passing output of one agent to another)
            else if ("minioJson".equalsIgnoreCase(type)) {
                try {
                    Map<String, Object> result = AgentResultStorageService.retrieveAgentResult(tenantId, value);
                    String apiResponse = (String) result.get("apiResponse");

                    if (apiResponse == null || apiResponse.trim().isEmpty()) {
                        throw new Exception("Empty apiResponse from: " + value);
                    }

                    // Solving the "Double Nesting" problem.
                    // If 'sourceJsonPath' is present, we extract ONLY that part of the JSON.
                    String sourcePath = input.optString("sourceJsonPath", "");

                    if (!sourcePath.isEmpty()) {
                        // Extract specific subtree (e.g., "$.doc_fhir")
                        Object extractedContent = JsonPath.read(apiResponse, sourcePath);
                        data.put(key, extractedContent);
                    } else {
                        // Default: Embed the entire JSON object
                        JSONObject jsonContent = new JSONObject(apiResponse);
                        data.put(key, jsonContent);
                    }

                } catch (Exception e) {
                    log.error("Failed to download/parse JSON at path: {}", value, e);
                    throw new BpmnError("FILE_ERROR", "Could not process JSON: " + value);
                }
            }
            // CASE 3: Simple Value (String, Number, Boolean)
            else {
                data.put(key, value);
            }
        }
        return data;
    }

    /**
     * Executes the HTTP request to the external Agent API.
     */
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

    /**
     * Maps fields from the Agent's JSON response to Camunda Process Variables.
     * Uses JSONPath expressions defined in the template.
     */
    private void mapOutputs(String responseBody, String mappingStr, DelegateExecution execution) {
        if (mappingStr == null || mappingStr.equals("{}")) return;

        JSONObject mapping = new JSONObject(mappingStr);
        Object jsonDoc = com.jayway.jsonpath.Configuration.defaultConfiguration().jsonProvider().parse(responseBody);

        for (String varName : mapping.keySet()) {
            String path = mapping.getString(varName); // e.g., "$.answer.risk_score"
            try {
                Object val = JsonPath.read(jsonDoc, path);
                execution.setVariable(varName, val);
            } catch (Exception e) {
                log.warn("Could not extract path '{}' for variable '{}' (Field might be missing)", path, varName);
            }
        }
    }

    /**
     * Replaces placeholders like ${variable} or ${appproperties.key} with actual values.
     */
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
            }
            else if (token.contains("[") && token.endsWith("]")) {
                replacement = resolveMapValue(token, execution);
            }
            else {
                String varName = token.startsWith("processVariable.")
                        ? token.substring("processVariable.".length()) : token;
                Object val = execution.getVariable(varName);
                if (val != null) {
                    replacement = val.toString();
                } else {
                    replacement = "${" + token + "}"; // Keep original if not found
                }
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    // Helper to resolve Map lookups like ${myMap[key]}
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