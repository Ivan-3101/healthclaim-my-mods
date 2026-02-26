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

/**
 * Generic HTTP API Call Delegate.
 *
 * Makes a configurable REST call and processes its response using a unified
 * outputMapping config — identical in structure and capability to GenericAgentDelegate.
 *
 * Output options:
 *   Step 8.  Always stores full response as a Camunda process variable ({outputVar}).
 *   Step 9.  Always stores full response as a MinIO file ({outputVar}_minioPath).
 *   Step 10. outputMapping (unified) — single JSON config that handles ALL of:
 *              PATTERN A — extract single path  → Camunda process variable
 *              PATTERN B — extract single path  → new MinIO file
 *              PATTERN C — merge multiple paths → Camunda process variable
 *              PATTERN D — merge multiple paths → new MinIO file
 *
 *   mergePaths sourceType options:
 *              "responseJson"    — (DEFAULT) the raw API response body
 *              "processVariable" — a Camunda process variable (string, JSON string, or object)
 *              "minioFile"       — an existing MinIO file (via sourcePath or variableName)
 *
 * outputMapping format examples:
 * ─────────────────────────────────────────────────────────────────────────────
 * PATTERN A — single path → process variable:
 *   "policyFound": { "storeIn": "processVariable", "dataType": "string", "path": "$.verified" }
 *
 * PATTERN B — single path → MinIO file:
 *   "attribsFile": { "storeIn": "objectStorage", "path": "$.attribs",
 *                    "targetPath": "1/${workflowKey}/${TicketID}/stage/attribs.json",
 *                    "targetVarName": "attribsMinioPath" }
 *
 * PATTERN C — merged paths → process variable:
 *   "summary": { "storeIn": "processVariable", "mergeVariables": true,
 *                "mergePaths": [
 *                  { "keyName": "holderName", "sourceType": "responseJson",    "path": "$.accountName" },
 *                  { "keyName": "riskScore",  "sourceType": "processVariable", "variableName": "RiskScore" },
 *                  { "keyName": "docData",    "sourceType": "minioFile",       "sourcePath": "1/${workflowKey}/${TicketID}/stage/doc.json", "path": "$.rawResponse" }
 *                ] }
 *
 * PATTERN D — merged paths → MinIO file:
 *   "report": { "storeIn": "objectStorage", "mergeVariables": true,
 *               "targetPath": "1/${workflowKey}/${TicketID}/stage/report.json",
 *               "targetVarName": "reportMinioPath",
 *               "mergePaths": [ ... same entry formats as Pattern C ... ] }
 *
 * Available root keys in responseJson source:
 *   All top-level keys from the raw API response body (e.g. $.verified, $.accountName, $.attribs)
 * ─────────────────────────────────────────────────────────────────────────────
 */
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
    private Expression outputMapping;     // Unified output config (Patterns A/B/C/D). Enter {} to skip.

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

                if ("$".equals(jsonPathExpr)) {
                    finalBody = fileContent;
                } else {
                    Configuration jacksonConfig = Configuration.builder()
                            .jsonProvider(new JacksonJsonProvider())
                            .mappingProvider(new JacksonMappingProvider())
                            .build();
                    Object extracted = JsonPath.using(jacksonConfig).parse(fileContent).read(jsonPathExpr);
                    finalBody = (extracted instanceof String) ? (String) extracted : new JSONObject((Map<?,?>) extracted).toString();
                }
            } catch (Exception e) {
                log.error("Failed to read MinIO body file '{}': {}", resolvedMinioBodyPath, e.getMessage());
                throw new BpmnError("STORAGE_ERROR", "Failed to read MinIO body file: " + e.getMessage());
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
        log.info("Request body being sent: {}", finalBody);

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

                // 10. UNIFIED OUTPUT MAPPING (Patterns A / B / C / D)
                String outputMappingStr = (outputMapping != null) ? (String) outputMapping.getValue(execution) : "{}";
                processOutputMapping(responseBody, outputMappingStr, tenantId, execution, props);
            }
        }

        log.info("=== Generic API Call Completed ===");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UNIFIED OUTPUT MAPPING
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Unified output mapping processor supporting 4 patterns.
     *
     * The source document (responseJson) is the raw API response body.
     * All JsonPaths are evaluated directly against that response.
     *
     * Descriptor fields:
     * ─────────────────────────────────────────────────────────────────────────
     *  storeIn        (required)  "processVariable" | "objectStorage"
     *  dataType       (optional)  "string" | "json"                    default: "json"
     *  path           (optional)  JsonPath into source JSON             default: "$"
     *                             (ignored when mergeVariables = true)
     *
     *  ── For storeIn = "objectStorage" ───────────────────────────────────────
     *  storageType    (optional)  "minio"                              default: "minio"
     *  targetPath     (required)  MinIO destination path. Supports ${var} placeholders.
     *  targetVarName  (optional)  Process variable receiving the resolved MinIO path.
     *
     *  ── For merging multiple paths ───────────────────────────────────────────
     *  mergeVariables (optional)  true | false                         default: false
     *  mergePaths     (required when mergeVariables=true)
     *                 JSON Array of entries. Each entry:
     *
     *                 keyName      (required) Key in the merged output object
     *                 sourceType   (optional) "responseJson" (DEFAULT) | "processVariable" | "minioFile"
     *                 dataType     (optional) "string" | "json"         default: "json"
     *                 path         (optional) JsonPath to extract        default: "$"
     *                                         (only applies to responseJson and minioFile)
     *
     *                 ── sourceType = "processVariable" ───────────────────────
     *                 variableName (required) Camunda process variable name.
     *                              Works for plain strings, JSON strings, and objects.
     *                              JSON strings are auto-parsed into nested objects.
     *                              dataType is ignored for this sourceType.
     *
     *                 ── sourceType = "minioFile" ─────────────────────────────
     *                 sourcePath   Explicit MinIO path. Supports ${var} placeholders.
     *                 variableName Name of process variable holding the MinIO path.
     *                              Use either sourcePath OR variableName, not both.
     * ─────────────────────────────────────────────────────────────────────────
     */
    private void processOutputMapping(String responseBody, String mappingStr,
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

        var responseDocumentContext = JsonPath.using(jacksonConfig).parse(responseBody);

        for (String outputKey : mapping.keySet()) {
            JSONObject descriptor = mapping.getJSONObject(outputKey);
            String storeIn  = descriptor.optString("storeIn", "processVariable");
            String dataType = descriptor.optString("dataType", "json");
            boolean isMerge = descriptor.optBoolean("mergeVariables", false);

            try {

                if (isMerge) {
                    // ── PATTERN C & D ─────────────────────────────────────────────────────
                    JSONArray mergePaths = descriptor.optJSONArray("mergePaths");
                    if (mergePaths == null || mergePaths.length() == 0) {
                        log.warn("outputMapping key '{}': mergeVariables=true but mergePaths is missing/empty — skipping",
                                outputKey);
                        continue;
                    }

                    JSONObject merged = new JSONObject();

                    for (int i = 0; i < mergePaths.length(); i++) {
                        JSONObject mp     = mergePaths.getJSONObject(i);
                        String keyName    = mp.optString("keyName", "key" + i);
                        String sourceType = mp.optString("sourceType", "responseJson");
                        String mergeType  = mp.optString("dataType", "json");
                        String mergePath  = mp.optString("path", "$");

                        Object val = null;

                        try {
                            if ("processVariable".equalsIgnoreCase(sourceType)) {
                                // ── SOURCE: Camunda process variable ──────────────────────
                                // Handles plain strings, JSON strings (auto-parsed), and objects.
                                // dataType is intentionally ignored — driven by actual variable content.
                                String variableName = mp.optString("variableName", "");
                                if (variableName.isEmpty()) {
                                    log.warn("Merge [{}] entry {}: sourceType=processVariable " +
                                            "but variableName is empty — skipping", outputKey, i);
                                    continue;
                                }
                                Object rawVal = execution.getVariable(variableName);
                                if (rawVal == null) {
                                    log.warn("Merge [{}] entry {}: processVariable '{}' is null — skipping",
                                            outputKey, i, variableName);
                                    continue;
                                }
                                if (rawVal instanceof String) {
                                    String strVal = ((String) rawVal).trim();
                                    if (strVal.startsWith("{")) {
                                        try {
                                            val = new JSONObject(strVal);
                                        } catch (Exception ignored) {
                                            val = strVal;
                                        }
                                    } else if (strVal.startsWith("[")) {
                                        try {
                                            val = new JSONArray(strVal);
                                        } catch (Exception ignored) {
                                            val = strVal;
                                        }
                                    } else {
                                        val = strVal;
                                    }
                                } else {
                                    val = rawVal;
                                }
                                log.debug("Merge [{}]: keyName='{}' sourceType=processVariable " +
                                                "variableName='{}' type={}",
                                        outputKey, keyName, variableName, val.getClass().getSimpleName());

                            } else if ("minioFile".equalsIgnoreCase(sourceType)) {
                                // ── SOURCE: Existing MinIO file ───────────────────────────
                                String resolvedMinioPath = resolveMinioSourcePath(
                                        mp, execution, props, outputKey, i);
                                if (resolvedMinioPath == null) continue;

                                String fileContent = downloadMinioFileAsString(
                                        resolvedMinioPath, tenantId, outputKey, i);
                                if (fileContent == null) continue;

                                val = extractFromJson(
                                        fileContent, mergePath, mergeType, jacksonConfig, outputKey, keyName);
                                if (val == null) continue;

                                log.debug("Merge [{}]: keyName='{}' sourceType=minioFile " +
                                        "path='{}' from '{}'", outputKey, keyName, mergePath, resolvedMinioPath);

                            } else {
                                // ── SOURCE: responseJson (DEFAULT) ────────────────────────
                                val = responseDocumentContext.read(mergePath);
                                val = coerceType(val, mergeType);
                                log.debug("Merge [{}]: keyName='{}' sourceType=responseJson " +
                                        "path='{}' → '{}'", outputKey, keyName, mergePath, val);
                            }

                        } catch (Exception e) {
                            log.warn("Merge [{}] entry {}: error resolving keyName='{}' — " +
                                    "skipping entry. Reason: {}", outputKey, i, keyName, e.getMessage());
                            continue;
                        }

                        // Add to merged object.
                        // Duplicate keyNames collect into a JSON Array.
                        if (merged.has(keyName)) {
                            Object existing = merged.get(keyName);
                            JSONArray arr = new JSONArray();
                            addToArray(arr, existing);
                            addToArray(arr, val);
                            merged.put(keyName, arr);
                        } else {
                            putJsonValue(merged, keyName, val);
                        }
                    }

                    // Route merged object to destination
                    if ("objectStorage".equalsIgnoreCase(storeIn)) {
                        // ── PATTERN D: Upload merged object as a MinIO file ───────────────
                        String targetPath    = descriptor.optString("targetPath", "");
                        String targetVarName = descriptor.optString("targetVarName", outputKey + "_minioPath");

                        if (targetPath.isEmpty()) {
                            log.warn("outputMapping key '{}': storeIn=objectStorage + mergeVariables=true " +
                                    "but targetPath is empty — skipping", outputKey);
                            continue;
                        }

                        String serialized         = merged.toString(2);
                        String resolvedTargetPath = resolvePlaceholders(targetPath, execution, props);
                        StorageProvider storage   = ObjectStorageService.getStorageProvider(tenantId);
                        storage.uploadDocument(resolvedTargetPath,
                                serialized.getBytes(StandardCharsets.UTF_8), "application/json");
                        execution.setVariable(targetVarName, resolvedTargetPath);
                        log.info("Output mapping (merge/objectStorage): merged {} paths → " +
                                "MinIO='{}' → var='{}'", mergePaths.length(), resolvedTargetPath, targetVarName);

                    } else {
                        // ── PATTERN C: Set merged object as Camunda process variable ──────
                        execution.setVariable(outputKey, merged.toString());
                        log.info("Output mapping (merge/processVariable): set '{}' as merged " +
                                "JSON object ({} paths)", outputKey, mergePaths.length());
                    }

                } else {
                    // ── PATTERN A & B ─────────────────────────────────────────────────────
                    // Reads from raw API response only.
                    String path = descriptor.optString("path", "$");
                    Object val;
                    try {
                        val = responseDocumentContext.read(path);
                    } catch (Exception e) {
                        log.warn("outputMapping key '{}': could not extract path '{}' — skipping",
                                outputKey, path);
                        continue;
                    }

                    val = coerceType(val, dataType);

                    if ("objectStorage".equalsIgnoreCase(storeIn)) {
                        // ── PATTERN B ──────────────────────────────────────────────────────
                        String targetPath    = descriptor.optString("targetPath", "");
                        String targetVarName = descriptor.optString("targetVarName", outputKey + "_minioPath");

                        if (targetPath.isEmpty()) {
                            log.warn("outputMapping key '{}': storeIn=objectStorage but " +
                                    "targetPath is empty — skipping", outputKey);
                            continue;
                        }

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
                        storage.uploadDocument(resolvedTargetPath,
                                serialized.getBytes(StandardCharsets.UTF_8), "application/json");
                        execution.setVariable(targetVarName, resolvedTargetPath);
                        log.info("Output mapping (objectStorage): path='{}' → MinIO='{}' → var='{}'",
                                path, resolvedTargetPath, targetVarName);

                    } else {
                        // ── PATTERN A ──────────────────────────────────────────────────────
                        execution.setVariable(outputKey, val);
                        log.info("Output mapping (processVariable): set '{}' from path '{}' = '{}'",
                                outputKey, path, val);
                    }
                }

            } catch (Exception e) {
                log.error("outputMapping key '{}': unexpected error — {}", outputKey, e.getMessage(), e);
                throw new BpmnError("OUTPUT_MAPPING_ERROR",
                        "Failed to process output mapping key '" + outputKey + "': " + e.getMessage());
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MINIO HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Resolves the MinIO source path for a mergePath entry with sourceType=minioFile.
     * Returns null (and logs a warning) if resolution fails.
     */
    private String resolveMinioSourcePath(JSONObject mp, DelegateExecution execution,
                                          Properties props, String outputKey, int entryIndex) {
        String sourcePath    = mp.optString("sourcePath", "");
        String variableName  = mp.optString("variableName", "");

        if (!sourcePath.isEmpty()) {
            return resolvePlaceholders(sourcePath, execution, props);
        } else if (!variableName.isEmpty()) {
            Object varVal = execution.getVariable(variableName);
            if (varVal == null) {
                log.warn("Merge [{}] entry {}: sourceType=minioFile variableName='{}' is null — skipping",
                        outputKey, entryIndex, variableName);
                return null;
            }
            return varVal.toString();
        } else {
            log.warn("Merge [{}] entry {}: sourceType=minioFile but neither sourcePath nor variableName provided — skipping",
                    outputKey, entryIndex);
            return null;
        }
    }

    /**
     * Downloads a MinIO file and returns its content as a String.
     * Returns null (and logs a warning) if download fails.
     */
    private String downloadMinioFileAsString(String minioPath, String tenantId,
                                             String outputKey, int entryIndex) {
        try {
            StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);
            byte[] bytes = storage.downloadDocument(minioPath).readAllBytes();
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Merge [{}] entry {}: failed to download MinIO file '{}' — skipping. Reason: {}",
                    outputKey, entryIndex, minioPath, e.getMessage());
            return null;
        }
    }

    /**
     * Extracts a value from a JSON string using JsonPath, auto-parses rawResponse if present,
     * then coerces the result to the requested dataType.
     * Returns null (and logs a warning) if extraction fails.
     */
    private Object extractFromJson(String jsonContent, String path, String dataType,
                                   Configuration jacksonConfig, String outputKey, String keyName) {
        try {
            // Auto-parse rawResponse field so paths like $.rawResponse.answer work correctly
            JSONObject parsed = new JSONObject(jsonContent);
            if (parsed.has("rawResponse")) {
                try {
                    String raw = parsed.getString("rawResponse");
                    parsed.put("rawResponse", new JSONObject(raw));
                    jsonContent = parsed.toString();
                } catch (Exception ignored) {
                    // rawResponse is already an object or not valid JSON — leave as-is
                }
            }

            Object val = JsonPath.using(jacksonConfig).parse(jsonContent).read(path);
            return coerceType(val, dataType);
        } catch (Exception e) {
            log.warn("outputMapping key '{}' keyName='{}': failed to extract path '{}' from file — skipping. Reason: {}",
                    outputKey, keyName, path, e.getMessage());
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TYPE COERCION
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Coerces the extracted value according to the requested dataType.
     *
     *  "string" — converts to String. If the value is a Map or List it is serialized to
     *             a compact JSON string. Whole-number doubles are coerced to Long to
     *             avoid type mismatches in Camunda form fields (e.g. RiskScore).
     *  "json"   — returns the value as-is (Map, List, Number, Boolean, String, etc.)
     */
    private Object coerceType(Object val, String dataType) {
        if (val == null) return null;

        if ("string".equalsIgnoreCase(dataType)) {
            if (val instanceof Map)  return new JSONObject((Map<?, ?>) val).toString();
            if (val instanceof List) return new JSONArray((List<?>) val).toString();
            return val.toString();
        }

        // "json" — numeric coercion: whole-number doubles → Long
        if (val instanceof Number) {
            double d = ((Number) val).doubleValue();
            if (d == Math.floor(d) && !Double.isInfinite(d)) {
                return ((Number) val).longValue();
            }
        }

        return val;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TYPE-SAFE JSON HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Type-safe put into a JSONObject.
     *
     * Explicitly dispatches by runtime type before calling the matching put() overload.
     * This avoids the "Ambiguous method call" compile error that occurs when passing
     * an Object to JSONObject.put() — the compiler cannot choose between overloads
     * like put(String, Map) and put(String, Collection) when the type is Object.
     */
    private void putJsonValue(JSONObject target, String key, Object val) throws Exception {
        if (val == null)                    target.put(key, JSONObject.NULL);
        else if (val instanceof JSONObject) target.put(key, (JSONObject) val);
        else if (val instanceof JSONArray)  target.put(key, (JSONArray) val);
        else if (val instanceof Map)        target.put(key, new JSONObject((Map<?, ?>) val));
        else if (val instanceof List)       target.put(key, new JSONArray((List<?>) val));
        else if (val instanceof Boolean)    target.put(key, (boolean) val);
        else if (val instanceof Integer)    target.put(key, (int) val);
        else if (val instanceof Long)       target.put(key, (long) val);
        else if (val instanceof Double)     target.put(key, (double) val);
        else                                target.put(key, val.toString());
    }

    /**
     * Type-safe add to a JSONArray.
     */
    private void addToArray(JSONArray arr, Object val) throws Exception {
        if (val == null)                    arr.put(JSONObject.NULL);
        else if (val instanceof JSONObject) arr.put((JSONObject) val);
        else if (val instanceof JSONArray)  arr.put((JSONArray) val);
        else if (val instanceof Map)        arr.put(new JSONObject((Map<?, ?>) val));
        else if (val instanceof List)       arr.put(new JSONArray((List<?>) val));
        else if (val instanceof Boolean)    arr.put((boolean) val);
        else if (val instanceof Integer)    arr.put((int) val);
        else if (val instanceof Long)       arr.put((long) val);
        else if (val instanceof Double)     arr.put((double) val);
        else                                arr.put(val.toString());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HTTP HELPERS
    // ─────────────────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────────────────
    // PLACEHOLDER RESOLUTION
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Resolves ${...} placeholders in a string.
     *
     * Supported patterns:
     *   ${fn.uuid}             → random UUID
     *   ${fn.isoTimestamp}     → current UTC ISO-8601 timestamp
     *   ${fn.randomTxnId}      → single uppercase letter + epoch millis
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
}