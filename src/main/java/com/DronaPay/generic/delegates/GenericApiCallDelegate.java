package com.DronaPay.generic.delegates;

import com.DronaPay.generic.services.ObjectStorageService;
import com.DronaPay.generic.storage.StorageProvider;
import com.DronaPay.generic.utils.TenantPropertiesUtil;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.spi.json.JacksonJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.methods.*;
import org.apache.http.entity.StringEntity;
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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class GenericApiCallDelegate implements JavaDelegate {

    // INPUTS (Injected from the Camunda Element Template)
    private Expression baseUrl;           // Dropdown key → property prefix (e.g. "springapi" → "springapi.url")
    private Expression route;             // API route, supports ${processVariable.x} (e.g. "/accounts/${processVariable.policy_id}")
    private Expression authType;          // Auth method (e.g. "xApiKey")
    private Expression method;            // HTTP Method (GET/POST/PUT/DELETE)
    private Expression body;              // Direct JSON body for POST/PUT. Use "NA" when not needed or when using minioBodyPath.
    private Expression minioBodyPath;     // Process variable holding a MinIO path to use as request body. Overrides body. "NA" to skip.
    private Expression minioBodyJsonPath; // JsonPath to extract from the MinIO file. "$" = entire file.
    private Expression outputVar;         // Process variable name for full response. Also derives {outputVar}_minioPath.
    private Expression outputMapping;     // JSON Object — extract JsonPath values from response → process variables
    private Expression minioExtract;      // JSON Array — extract JsonPath values from response → new MinIO files

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("=== Generic API Call Started ===");

        // 1. SETUP
        String tenantId = execution.getTenantId();
        if (tenantId == null || tenantId.isEmpty()) {
            throw new BpmnError("CONFIG_ERROR", "Tenant ID is missing from execution context.");
        }

        Properties props = TenantPropertiesUtil.getTenantProps(tenantId);

        // 2. READ FIELD INPUTS
        String baseUrlKey = baseUrl   != null ? (String) baseUrl.getValue(execution)   : "springapi";
        String routePath  = route     != null ? (String) route.getValue(execution)     : "/";
        String authMethod = authType  != null ? (String) authType.getValue(execution)  : "xApiKey";
        String reqMethod  = method    != null ? (String) method.getValue(execution)    : "GET";
        String targetVar  = outputVar != null ? (String) outputVar.getValue(execution) : "apiResponse";

        // 3. BUILD REQUEST BODY
        String finalBody = null;

        String rawMinioBodyPath = minioBodyPath != null ? (String) minioBodyPath.getValue(execution) : "NA";

        if (!"NA".equalsIgnoreCase(rawMinioBodyPath.trim())) {
            String resolvedMinioBodyPath = resolvePlaceholders(rawMinioBodyPath, execution, props);
            String jsonPathExpr = minioBodyJsonPath != null ? (String) minioBodyJsonPath.getValue(execution) : "$";

            log.info("Using MinIO file as request body. Path: {}, JsonPath: {}", resolvedMinioBodyPath, jsonPathExpr);

            try {
                StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);
                byte[] fileBytes = storage.downloadDocument(resolvedMinioBodyPath).readAllBytes();
                String fileContent = new String(fileBytes, StandardCharsets.UTF_8);

                if ("$".equals(jsonPathExpr.trim())) {
                    finalBody = fileContent;
                } else {
                    Configuration jacksonConfig = Configuration.builder()
                            .jsonProvider(new JacksonJsonProvider())
                            .mappingProvider(new JacksonMappingProvider())
                            .build();
                    Object extracted = JsonPath.using(jacksonConfig).parse(fileContent).read(jsonPathExpr);

                    if (extracted instanceof Map) {
                        finalBody = new JSONObject((Map<?, ?>) extracted).toString();
                    } else if (extracted instanceof List) {
                        finalBody = new JSONArray((List<?>) extracted).toString();
                    } else {
                        finalBody = new JSONObject().put("value", extracted).toString();
                    }
                }
                log.info("MinIO body resolved ({} bytes)", finalBody.length());
            } catch (Exception e) {
                log.error("Failed to load request body from MinIO path '{}': {}", resolvedMinioBodyPath, e.getMessage());
                throw new BpmnError("FILE_ERROR", "Could not load request body from MinIO: " + resolvedMinioBodyPath);
            }
        } else {
            String reqBody = body != null ? (String) body.getValue(execution) : "NA";
            if (!"NA".equalsIgnoreCase(reqBody)) {
                finalBody = resolvePlaceholders(reqBody, execution, props);
            }
        }

        // 4. CONSTRUCT URL
        String resolvedBase = props.getProperty(baseUrlKey + ".url");
        if (resolvedBase == null) {
            throw new BpmnError("CONFIG_ERROR", "Property not found: " + baseUrlKey + ".url");
        }

        if (resolvedBase.endsWith("/")) resolvedBase = resolvedBase.substring(0, resolvedBase.length() - 1);

        String resolvedRoute = resolvePlaceholders(routePath, execution, props);
        if (!resolvedRoute.startsWith("/")) resolvedRoute = "/" + resolvedRoute;

        String finalUrl = resolvedBase + resolvedRoute;
        log.info("Making {} request to: {}", reqMethod, finalUrl);
        log.info("Request body being sent: {}", finalBody); // DEBUG - uncomment to trace request bodies

        // 5. EXECUTE HTTP REQUEST
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpRequestBase request = createRequest(reqMethod, finalUrl, finalBody);

            // 6. AUTHENTICATION
            if ("xApiKey".equalsIgnoreCase(authMethod)) {
                String apiKey = props.getProperty(baseUrlKey + ".api.key");
                if (apiKey == null) {
                    throw new BpmnError("CONFIG_ERROR", "Property not found: " + baseUrlKey + ".api.key");
                }
                request.addHeader("x-api-key", apiKey);
            }

            request.addHeader("Content-Type", "application/json");

            try (CloseableHttpResponse response = client.execute(request)) {
                int statusCode = response.getStatusLine().getStatusCode();
                String responseBody = EntityUtils.toString(response.getEntity());

                log.info("API Response Status: {}", statusCode);

                // 7. FAIL LOGIC
                if (statusCode != 200) {
                    log.error("API Call Failed. Status: {}, Body: {}", statusCode, responseBody);
                    throw new BpmnError("API_ERROR", "API Call failed with status code: " + statusCode);
                }

                // 8. STORE FULL RESPONSE AS PROCESS VARIABLE (always happens)
                execution.setVariable(targetVar + "_statusCode", statusCode);
                try {
                    ObjectValue respJson = Variables.objectValue(responseBody)
                            .serializationDataFormat("application/json").create();
                    execution.setVariable(targetVar, respJson);
                } catch (Exception e) {
                    execution.setVariable(targetVar, responseBody);
                }

                // 9. STORE FULL RESPONSE IN MINIO (always happens)
                // Path: {tenantId}/{workflowKey}/{ticketId}/{stageName}/{outputVar}.json
                // Sets: {outputVar}_minioPath
                try {
                    String ticketId = execution.getVariable("TicketID") != null
                            ? String.valueOf(execution.getVariable("TicketID"))
                            : "UNKNOWN";
                    String workflowKey = execution.getProcessDefinitionId().split(":")[0];
                    String stageName = execution.getCurrentActivityId();

                    String minioPath = tenantId + "/" + workflowKey + "/" + ticketId + "/" + stageName + "/" + targetVar + ".json";

                    StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);
                    byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
                    storage.uploadDocument(minioPath, bytes, "application/json");

                    execution.setVariable(targetVar + "_minioPath", minioPath);
                    log.info("Response stored to MinIO: {} → var '{}'", minioPath, targetVar + "_minioPath");
                } catch (Exception e) {
                    log.error("Failed to store API response to MinIO: {}", e.getMessage());
                    throw new BpmnError("STORAGE_ERROR", "Failed to store API response to MinIO: " + e.getMessage());
                }

                // 10. OUTPUT MAPPING — Extract JsonPath values from response → process variables
                String outputMappingStr = (outputMapping != null) ? (String) outputMapping.getValue(execution) : "{}";
                mapOutputs(responseBody, outputMappingStr, execution);

                // 11. MINIO EXTRACT — Extract JsonPath values from response → new MinIO files
                String minioExtractStr = (minioExtract != null) ? (String) minioExtract.getValue(execution) : "[]";
                extractToMinioFiles(responseBody, minioExtractStr, tenantId, execution, props);
            }
        }

        log.info("=== Generic API Call Completed ===");
    }

    /**
     * Extracts JsonPath values from the response body and sets them as process variables.
     * Input format: {"processVarName": "$.jsonPath", ...}
     *
     * Numeric coercion: whole numbers are stored as Long, not Double.
     * Required for Camunda form fields that expect long (e.g. RiskScore).
     */
    private void mapOutputs(String responseBody, String mappingStr, DelegateExecution execution) {
        if (mappingStr == null || mappingStr.trim().equals("{}")) return;

        Configuration jacksonConfig = Configuration.builder()
                .jsonProvider(new JacksonJsonProvider())
                .mappingProvider(new JacksonMappingProvider())
                .build();

        JSONObject mapping = new JSONObject(mappingStr);
        var documentContext = JsonPath.using(jacksonConfig).parse(responseBody);

        for (String varName : mapping.keySet()) {
            String path = mapping.getString(varName);
            try {
                Object val = documentContext.read(path);

                // Coerce whole-number doubles to Long to avoid type mismatches in Camunda UI forms
                if (val instanceof Number) {
                    double d = ((Number) val).doubleValue();
                    if (d == Math.floor(d) && !Double.isInfinite(d)) {
                        val = ((Number) val).longValue();
                    } else {
                        val = d;
                    }
                }

                execution.setVariable(varName, val);
                log.info("Output mapping: set '{}' = '{}' from path '{}'", varName, val, path);
            } catch (Exception e) {
                log.warn("Output mapping: could not extract path '{}' for variable '{}' — skipping", path, varName);
            }
        }
    }

    /**
     * Extracts JsonPath values from the response body and saves each as a new MinIO file.
     * Input format (JSON Array):
     * [
     *   {
     *     "sourceJsonPath": "$.attribs",
     *     "targetPath":     "1/${workflowKey}/${TicketID}/stage/attribs.json",
     *     "targetVarName":  "attribsMinioPath"
     *   }
     * ]
     */
    private void extractToMinioFiles(String responseBody, String extractStr,
                                     String tenantId, DelegateExecution execution, Properties props) {
        if (extractStr == null || extractStr.trim().equals("[]")) return;

        JSONArray extractions;
        try {
            extractions = new JSONArray(extractStr);
        } catch (Exception e) {
            log.warn("minioExtract is not a valid JSON array — skipping. Value: {}", extractStr);
            return;
        }

        if (extractions.length() == 0) return;

        Configuration jacksonConfig = Configuration.builder()
                .jsonProvider(new JacksonJsonProvider())
                .mappingProvider(new JacksonMappingProvider())
                .build();

        var documentContext = JsonPath.using(jacksonConfig).parse(responseBody);

        StorageProvider storage;
        try {
            storage = ObjectStorageService.getStorageProvider(tenantId);
        } catch (Exception e) {
            log.error("MinIO extract: Could not get storage provider", e);
            throw new BpmnError("STORAGE_ERROR", "Could not get storage provider: " + e.getMessage());
        }

        for (int i = 0; i < extractions.length(); i++) {
            JSONObject entry = extractions.getJSONObject(i);
            String sourceJsonPath = entry.optString("sourceJsonPath", "");
            String targetPath     = entry.optString("targetPath", "");
            String targetVarName  = entry.optString("targetVarName", "");

            if (sourceJsonPath.isEmpty() || targetPath.isEmpty() || targetVarName.isEmpty()) {
                log.warn("MinIO extract: Entry {} is missing required fields — skipping", i);
                continue;
            }

            Object extracted;
            try {
                extracted = documentContext.read(sourceJsonPath);
            } catch (Exception e) {
                log.warn("MinIO extract: Could not extract path '{}' for entry {} — skipping", sourceJsonPath, i);
                continue;
            }

            String serialized;
            if (extracted instanceof Map) {
                serialized = new JSONObject((Map<?, ?>) extracted).toString(2);
            } else if (extracted instanceof List) {
                serialized = new JSONArray((List<?>) extracted).toString(2);
            } else {
                serialized = new JSONObject().put("value", extracted).toString(2);
            }

            byte[] bytes = serialized.getBytes(StandardCharsets.UTF_8);
            String resolvedTargetPath = resolvePlaceholders(targetPath, execution, props);

            try {
                storage.uploadDocument(resolvedTargetPath, bytes, "application/json");
                execution.setVariable(targetVarName, resolvedTargetPath);
                log.info("MinIO extract: '{}' → '{}' → var '{}'", sourceJsonPath, resolvedTargetPath, targetVarName);
            } catch (Exception e) {
                log.error("MinIO extract: Failed to upload to path '{}': {}", resolvedTargetPath, e.getMessage());
                throw new BpmnError("STORAGE_ERROR", "Failed to store extracted MinIO file: " + resolvedTargetPath);
            }
        }
    }

    // --- Helper Methods ---

    /**
     * Resolves placeholders in a string. Supported patterns:
     *
     *   ${fn.uuid}             → random UUID (for reqid fields)
     *   ${fn.isoTimestamp}     → current UTC ISO-8601 timestamp (for ts fields)
     *   ${fn.randomTxnId}      → single uppercase letter + epoch millis (for txn.id fields)
     *   ${appproperties.key}   → value from tenant application.properties
     *   ${processVariable.key} → value from Camunda process variable
     *   ${key}                 → shorthand for processVariable
     */
    private String resolvePlaceholders(String input, DelegateExecution execution, Properties props) {
        if (input == null || input.isEmpty()) return input;

        Pattern pattern = Pattern.compile("\\$\\{([^}]+)\\}");
        Matcher matcher = pattern.matcher(input);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String token = matcher.group(1);
            String replacement = "";

            if (token.startsWith("fn.")) {
                String fnName = token.substring("fn.".length());
                switch (fnName) {
                    case "uuid":
                        replacement = UUID.randomUUID().toString();
                        break;
                    case "isoTimestamp":
                        replacement = Instant.now().toString();
                        break;
                    case "randomTxnId":
                        replacement = (char) ('A' + new Random().nextInt(26)) + Long.toString(Instant.now().toEpochMilli());
                        break;
                    default:
                        log.warn("Unknown fn token: '{}'", fnName);
                        replacement = "";
                }
            } else if (token.startsWith("appproperties.")) {
                String key = token.substring("appproperties.".length());
                replacement = props != null ? props.getProperty(key, "") : "";
            } else if (token.startsWith("processVariable.")) {
                String key = token.substring("processVariable.".length());
                Object val = execution.getVariable(key);
                replacement = val != null ? val.toString() : "";
            } else {
                Object val = execution.getVariable(token);
                replacement = val != null ? val.toString() : "${" + token + "}";
            }

            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private HttpRequestBase createRequest(String method, String url, String body) throws Exception {
        switch (method.toUpperCase()) {
            case "POST":
                HttpPost post = new HttpPost(url);
                if (body != null && !body.isEmpty()) post.setEntity(new StringEntity(body));
                return post;
            case "PUT":
                HttpPut put = new HttpPut(url);
                if (body != null && !body.isEmpty()) put.setEntity(new StringEntity(body));
                return put;
            case "DELETE":
                return new HttpDelete(url);
            case "GET":
            default:
                return new HttpGet(url);
        }
    }
}