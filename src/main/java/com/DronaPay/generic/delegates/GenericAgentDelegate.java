package com.DronaPay.generic.delegates;

import com.DronaPay.generic.utils.TenantPropertiesUtil;
import com.DronaPay.generic.services.AgentResultStorageService;
import com.DronaPay.generic.services.ObjectStorageService;
import com.DronaPay.generic.storage.StorageProvider;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.spi.json.JacksonJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
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
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Generic Agent Delegate that executes external API calls based on a BPMN Element Template.
 *
 * It handles:
 * 1. Dynamic URL construction (Base URL + Route)
 * 2. Authentication (Basic Auth)
 * 3. Input payload construction (including file downloads from MinIO)
 * 4. Storing results back to MinIO and mapping outputs via a unified outputMapping config.
 *
 * Output options:
 *   3a. Always stores full result as MinIO JSON (default, always happens)
 *   3b. outputMapping (unified) — single JSON config that handles BOTH:
 *         - storing extracted values as Camunda process variables  (storeIn: "processVariable")
 *         - storing extracted values as new MinIO files             (storeIn: "objectStorage")
 *         - merging multiple JsonPaths into one JSON object         (mergeVariables: true)
 */
@Slf4j
public class GenericAgentDelegate implements JavaDelegate {

    // Fields injected from the BPMN Element Template
    private Expression baseUrl;        // Key for the base URL (e.g., "dia" -> "agent.api.url")
    private Expression route;          // Specific API route (e.g., "agent")
    private Expression authType;       // Authentication method (e.g., "basicAuth")
    private Expression method;         // HTTP Method (POST/GET)
    private Expression agentId;        // Unique Identifier for this agent task
    private Expression inputParams;    // JSON Array defining input data structure
    private Expression outputMapping;  // Unified output mapping (replaces old outputMapping + outputMinioExtract)

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

        // 8. Store Full Result in MinIO (3a — always happens)
        Map<String, Object> resultMap = AgentResultStorageService.buildResultMap(
                currentAgentId, 200, responseBody, new HashMap<>());

        Object attachmentObj = execution.getVariable("attachment");
        String filename = (attachmentObj != null) ? attachmentObj.toString() : currentAgentId + "_result";
        String resultFileName = (filename != null && !filename.isEmpty()) ? filename : currentAgentId + "_result_" + System.currentTimeMillis();

        String minioPath = AgentResultStorageService.storeAgentResult(
                tenantId, ticketId, stageName, resultFileName, resultMap);

        execution.setVariable(currentAgentId + "_MinioPath", minioPath);
        log.info("Agent Result stored: {}", minioPath);

        // Build parsed full result JSON — used by the unified output mapping below.
        // rawResponse is stored as an escaped JSON string inside resultMap — parse it
        // into a real JSONObject so that paths like $.rawResponse.answer work correctly.
        JSONObject resultJson = new JSONObject(resultMap);
        if (resultJson.has("rawResponse")) {
            try {
                String raw = resultJson.getString("rawResponse");
                resultJson.put("rawResponse", new JSONObject(raw));
            } catch (Exception e) {
                log.warn("rawResponse is not valid JSON for agent '{}', keeping as string", currentAgentId);
            }
        }
        String fullResultJson = resultJson.toString();

        // 9. Unified Output Mapping (3b)
        // Handles both process variable storage and object storage in one config block.
        // See processOutputMapping() below for full format documentation.
        String outputMapStr = (outputMapping != null) ? (String) outputMapping.getValue(execution) : "{}";
        processOutputMapping(fullResultJson, outputMapStr, tenantId, execution, props);

        log.info("=== Generic Agent Delegate Completed ===");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UNIFIED OUTPUT MAPPING
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Unified output mapping processor (replaces old mapOutputs + extractToMinioFiles).
     *
     * The "outputMapping" field is a JSON Object where:
     *   - Each top-level KEY   = the output variable name (or MinIO targetVarName for objectStorage)
     *   - Each top-level VALUE = a descriptor object with the fields below
     *
     * Descriptor fields:
     * ─────────────────────────────────────────────────────────────────────────
     *  storeIn        (required)  "processVariable" | "objectStorage"
     *  dataType       (optional)  "string" | "json"                   default: "json"
     *  path           (optional)  JsonPath into fullResultJson         default: "$"
     *                             (ignored when mergeVariables = true)
     *
     *  — For storeIn = "objectStorage" only —
     *  storageType    (optional)  "minio"                             default: "minio"
     *  targetPath     (required)  MinIO destination path. Supports ${var} placeholders.
     *  targetVarName  (optional)  Process variable to receive the resolved MinIO path.
     *                             Defaults to the top-level key name.
     *
     *  — For merging multiple paths into one object —
     *  mergeVariables (optional)  true | false                        default: false
     *  mergePaths     (required when mergeVariables=true)
     *                 JSON Array of { keyName, dataType, path }
     *                 Each entry is extracted and placed under keyName in the merged object.
     *                 If the same keyName appears more than once, values are collected
     *                 into a JSON Array under that key.
     * ─────────────────────────────────────────────────────────────────────────
     *
     * PATTERN A — store single JsonPath value as Camunda process variable:
     * {
     *   "isForged": {
     *     "storeIn": "processVariable",
     *     "dataType": "string",
     *     "path": "$.rawResponse.answer"
     *   }
     * }
     *
     * PATTERN B — extract JsonPath value and upload as a new MinIO file:
     * {
     *   "forgeryResultFile": {
     *     "storeIn": "objectStorage",
     *     "storageType": "minio",
     *     "dataType": "json",
     *     "path": "$",
     *     "targetPath": "${TicketID}/forgery/result.json",
     *     "targetVarName": "forgeryResultMinioPath"
     *   }
     * }
     *
     * PATTERN C — merge multiple JsonPaths into one JSON object, store as process variable:
     * {
     *   "mergedSummary": {
     *     "storeIn": "processVariable",
     *     "dataType": "json",
     *     "mergeVariables": true,
     *     "mergePaths": [
     *       { "keyName": "answer", "dataType": "string", "path": "$.rawResponse.answer" },
     *       { "keyName": "score",  "dataType": "json",   "path": "$.rawResponse.score"  }
     *     ]
     *   }
     * }
     *
     * Available root keys in fullResultJson (from AgentResultStorageService.buildResultMap):
     *   agentId, statusCode, success, rawResponse (parsed object), extractedData, timestamp
     */
    private void processOutputMapping(String fullResultJson, String mappingStr,
                                      String tenantId, DelegateExecution execution, Properties props) {
        if (mappingStr == null || mappingStr.trim().equals("{}")) {
            log.debug("outputMapping is empty — skipping.");
            return;
        }

        JSONObject mapping;
        try {
            mapping = new JSONObject(mappingStr);
        } catch (Exception e) {
            log.error("outputMapping is not valid JSON — skipping. Value: {}", mappingStr);
            return;
        }

        Configuration jacksonConfig = Configuration.builder()
                .jsonProvider(new JacksonJsonProvider())
                .mappingProvider(new JacksonMappingProvider())
                .build();

        var documentContext = JsonPath.using(jacksonConfig).parse(fullResultJson);

        for (String outputKey : mapping.keySet()) {
            JSONObject descriptor = mapping.getJSONObject(outputKey);
            String storeIn   = descriptor.optString("storeIn", "processVariable");
            String dataType  = descriptor.optString("dataType", "json");
            boolean isMerge  = descriptor.optBoolean("mergeVariables", false);

            try {

                // ── PATTERN C: Merge multiple paths into one JSON object ───────────────
                if (isMerge) {
                    JSONArray mergePaths = descriptor.optJSONArray("mergePaths");
                    if (mergePaths == null || mergePaths.length() == 0) {
                        log.warn("outputMapping key '{}': mergeVariables=true but mergePaths is missing/empty — skipping", outputKey);
                        continue;
                    }

                    JSONObject merged = new JSONObject();
                    for (int i = 0; i < mergePaths.length(); i++) {
                        JSONObject mp      = mergePaths.getJSONObject(i);
                        String keyName     = mp.optString("keyName", "key" + i);
                        String mergePath   = mp.optString("path", "$");
                        String mergeType   = mp.optString("dataType", "json");

                        try {
                            Object val = documentContext.read(mergePath);
                            val = coerceType(val, mergeType);

                            // If the same keyName is used more than once, collect into a JSON Array
                            if (merged.has(keyName)) {
                                Object existing = merged.get(keyName);
                                JSONArray arr;
                                if (existing instanceof JSONArray) {
                                    arr = (JSONArray) existing;
                                } else {
                                    arr = new JSONArray();
                                    arr.put(existing);
                                }
                                arr.put(val);
                                merged.put(keyName, arr);
                            } else {
                                merged.put(keyName, val);
                            }
                            log.debug("Merge [{}]: keyName='{}' path='{}' → '{}'", outputKey, keyName, mergePath, val);
                        } catch (Exception e) {
                            log.warn("Merge [{}]: could not extract path '{}' for keyName '{}' — skipping entry", outputKey, mergePath, keyName);
                        }
                    }

                    execution.setVariable(outputKey, merged.toString());
                    log.info("Output mapping (merge/processVariable): set '{}' as merged JSON object", outputKey);
                    continue;
                }

                // ── PATTERN A & B: Single path extraction ─────────────────────────────
                String path = descriptor.optString("path", "$");
                Object val;
                try {
                    val = documentContext.read(path);
                } catch (Exception e) {
                    log.warn("outputMapping key '{}': could not extract path '{}' — skipping", outputKey, path);
                    continue;
                }

                val = coerceType(val, dataType);

                if ("objectStorage".equalsIgnoreCase(storeIn)) {

                    // ── PATTERN B: Upload extracted value as a new MinIO file ──────────
                    String targetPath    = descriptor.optString("targetPath", "");
                    String targetVarName = descriptor.optString("targetVarName", outputKey + "_minioPath");

                    if (targetPath.isEmpty()) {
                        log.warn("outputMapping key '{}': storeIn=objectStorage but targetPath is empty — skipping", outputKey);
                        continue;
                    }

                    // Serialize the extracted value to a JSON string for upload
                    String serialized;
                    if (val instanceof Map) {
                        serialized = new JSONObject((Map<?, ?>) val).toString(2);
                    } else if (val instanceof List) {
                        serialized = new JSONArray((List<?>) val).toString(2);
                    } else {
                        serialized = (val != null) ? val.toString() : "null";
                    }

                    String resolvedTargetPath = resolvePlaceholders(targetPath, execution, props);
                    StorageProvider storage   = ObjectStorageService.getStorageProvider(tenantId);
                    storage.uploadDocument(resolvedTargetPath, serialized.getBytes(StandardCharsets.UTF_8), "application/json");
                    execution.setVariable(targetVarName, resolvedTargetPath);
                    log.info("Output mapping (objectStorage): path='{}' → MinIO='{}' → var='{}'", path, resolvedTargetPath, targetVarName);

                } else {

                    // ── PATTERN A: Set as Camunda process variable ─────────────────────
                    execution.setVariable(outputKey, val);
                    log.info("Output mapping (processVariable): set '{}' from path '{}' = '{}'", outputKey, path, val);
                }

            } catch (Exception e) {
                log.error("outputMapping key '{}': unexpected error — {}", outputKey, e.getMessage(), e);
                throw new BpmnError("OUTPUT_MAPPING_ERROR",
                        "Failed to process output mapping key '" + outputKey + "': " + e.getMessage());
            }
        }
    }

    /**
     * Coerces an extracted JsonPath value to the declared dataType.
     *
     * "string" — calls toString() on anything (Maps/Lists become their JSON string representation)
     * "json"   — keeps Maps and Lists as-is so Camunda serializes them via Jackson;
     *            wraps primitives in a JSONObject {"value": <val>} when the caller
     *            needs a JSON object but got a primitive (only relevant for objectStorage uploads)
     */
    private Object coerceType(Object val, String dataType) {
        if (val == null) return null;
        if ("string".equalsIgnoreCase(dataType)) {
            if (val instanceof Map) {
                return new JSONObject((Map<?, ?>) val).toString();
            } else if (val instanceof List) {
                return new JSONArray((List<?>) val).toString();
            }
            return val.toString();
        }
        // "json" — return as-is; Camunda / JSONObject serialization handles Maps and Lists
        return val;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HELPER METHODS (unchanged from original)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Constructs the 'data' JSON object expected by the Agent API.
     */
    private JSONObject buildDataObject(String inputJsonStr, String tenantId) throws Exception {
        JSONObject data = new JSONObject();
        JSONArray inputs = new JSONArray(inputJsonStr);
        StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);

        for (int i = 0; i < inputs.length(); i++) {
            JSONObject input = inputs.getJSONObject(i);
            String key   = input.getString("key");
            String type  = input.optString("type", "value");
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
            } else if ("minioJson".equalsIgnoreCase(type)) {
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
            } else {
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
     * Resolves ${variable} and ${appproperties.key} placeholders in a string.
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

    /**
     * Resolves map access tokens like documentPaths[attachment] from process variables.
     */
    private String resolveMapValue(String token, DelegateExecution execution) {
        try {
            int bracketIndex = token.indexOf("[");
            String mapName   = token.substring(0, bracketIndex);
            String keyVarName = token.substring(bracketIndex + 1, token.length() - 1);
            Object mapObj    = execution.getVariable(mapName);
            Object keyValObj = execution.getVariable(keyVarName);
            String resolvedKey = (keyValObj != null) ? keyValObj.toString() : keyVarName;

            if (mapObj instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) mapObj;
                Object value = map.get(resolvedKey);
                return value != null ? value.toString() : "";
            }
        } catch (Exception e) {
            // ignore — return placeholder unchanged
        }
        return "${" + token + "}";
    }
}