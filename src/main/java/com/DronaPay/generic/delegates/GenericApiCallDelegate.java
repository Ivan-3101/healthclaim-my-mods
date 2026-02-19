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
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class GenericApiCallDelegate implements JavaDelegate {

    // INPUTS (Injected from the Camunda Element Template)
    private Expression baseUrl;       // Dropdown key that maps to a property prefix (e.g., "springapi" -> "springapi.url")
    private Expression route;         // Specific API route, supports process variables (e.g., "/accounts/${processVariable.policy_id}")
    private Expression authType;      // Authentication method (e.g., "xApiKey" -> reads "springapi.api.key" from properties)
    private Expression method;        // HTTP Method (e.g., "GET", "POST")
    private Expression body;          // JSON Payload for POST/PUT requests. Use "NA" as placeholder when no body is needed.
    private Expression outputVar;     // Variable name to store the full response. Also derives {outputVar}_minioPath.
    private Expression outputMapping; // 3d: JSON Object - extract JsonPath values from response → process variables
    private Expression minioExtract;  // 3c: JSON Array - extract JsonPath values from response → new MinIO files

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("=== Generic API Call Started ===");

        // 1. SETUP
        String tenantId = execution.getTenantId();
        if (tenantId == null || tenantId.isEmpty()) {
            throw new BpmnError("CONFIG_ERROR", "Tenant ID is missing from execution context.");
        }

        Properties props = TenantPropertiesUtil.getTenantProps(tenantId);

        // 2. READ INPUTS
        if (baseUrl == null || route == null) {
            throw new BpmnError("CONFIG_ERROR", "Missing required fields: 'baseUrl' and 'route'. Ensure the BPMN task uses the correct template.");
        }

        String baseUrlKey = (String) baseUrl.getValue(execution);
        String routePath  = (String) route.getValue(execution);
        String authMethod = authType != null ? (String) authType.getValue(execution) : "none";
        String reqMethod  = method != null ? (String) method.getValue(execution) : "GET";
        String targetVar  = outputVar != null ? (String) outputVar.getValue(execution) : "apiResponse";

        String reqBody = body != null ? (String) body.getValue(execution) : "NA";
        if ("NA".equalsIgnoreCase(reqBody)) reqBody = "";

        // 3. CONSTRUCT URL
        String resolvedBase = props.getProperty(baseUrlKey + ".url");
        if (resolvedBase == null) {
            throw new BpmnError("CONFIG_ERROR", "Property not found: " + baseUrlKey + ".url");
        }

        if (resolvedBase.endsWith("/")) resolvedBase = resolvedBase.substring(0, resolvedBase.length() - 1);

        String resolvedRoute = resolvePlaceholders(routePath, execution, props);
        if (!resolvedRoute.startsWith("/")) resolvedRoute = "/" + resolvedRoute;

        String finalUrl  = resolvedBase + resolvedRoute;
        String finalBody = resolvePlaceholders(reqBody, execution, props);

        log.info("Making {} request to: {}", reqMethod, finalUrl);

        // 4. EXECUTE HTTP REQUEST
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpRequestBase request = createRequest(reqMethod, finalUrl, finalBody);

            // 5. AUTHENTICATION
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

                // 6. FAIL LOGIC
                if (statusCode != 200) {
                    log.error("API Call Failed. Status: {}, Body: {}", statusCode, responseBody);
                    throw new BpmnError("API_ERROR", "API Call failed with status code: " + statusCode);
                }

                // 7. STORE FULL RESPONSE AS PROCESS VARIABLE (always happens)
                execution.setVariable(targetVar + "_statusCode", statusCode);
                try {
                    ObjectValue respJson = Variables.objectValue(responseBody)
                            .serializationDataFormat("application/json").create();
                    execution.setVariable(targetVar, respJson);
                } catch (Exception e) {
                    execution.setVariable(targetVar, responseBody);
                }

                // 8. STORE FULL RESPONSE IN MINIO (3b - always happens)
                // Path: {tenantId}/{workflowKey}/{ticketId}/{stageName}/{outputVar}.json
                // Sets: {outputVar}_minioPath
                String minioPath;
                try {
                    String ticketId = execution.getVariable("TicketID") != null
                            ? String.valueOf(execution.getVariable("TicketID"))
                            : "UNKNOWN";
                    String workflowKey = execution.getProcessDefinitionId().split(":")[0];
                    String stageName = execution.getCurrentActivityId();

                    minioPath = tenantId + "/" + workflowKey + "/" + ticketId + "/" + stageName + "/" + targetVar + ".json";

                    StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);
                    byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
                    storage.uploadDocument(minioPath, bytes, "application/json");

                    execution.setVariable(targetVar + "_minioPath", minioPath);
                    log.info("3b: Response stored to MinIO: {} → var '{}'", minioPath, targetVar + "_minioPath");
                } catch (Exception e) {
                    log.error("3b: Failed to store API response to MinIO: {}", e.getMessage());
                    throw new BpmnError("STORAGE_ERROR", "Failed to store API response to MinIO: " + e.getMessage());
                }

                // 9. OUTPUT MAPPING (3d) - Extract JsonPath values from response → process variables
                // Example: {"policyFound": "$.policyFound", "holderName": "$.name"}
                String outputMappingStr = (outputMapping != null) ? (String) outputMapping.getValue(execution) : "{}";
                mapOutputs(responseBody, outputMappingStr, execution);

                // 10. MINIO EXTRACT (3c) - Extract JsonPath values from response → new MinIO files
                // Example: [{"sourceJsonPath": "$.attribs", "targetPath": "${TicketID}/attribs.json", "targetVarName": "attribsMinioPath"}]
                String minioExtractStr = (minioExtract != null) ? (String) minioExtract.getValue(execution) : "[]";
                extractToMinioFiles(responseBody, minioExtractStr, tenantId, execution, props);
            }
        }

        log.info("=== Generic API Call Completed ===");
    }

    /**
     * 3d: Extracts JsonPath values from the response body and sets them as process variables.
     * Input format: {"processVarName": "$.jsonPath", ...}
     * Example: {"policyFound": "$.policyFound", "holderName": "$.name"}
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
                execution.setVariable(varName, val);
                log.info("3d: Set process variable '{}' = '{}' from path '{}'", varName, val, path);
            } catch (Exception e) {
                log.warn("3d: Could not extract path '{}' for variable '{}' — skipping", path, varName);
            }
        }
    }

    /**
     * 3c: Extracts JsonPath values from the response body and saves each as a new MinIO file.
     * Input format (JSON Array):
     * [
     *   {
     *     "sourceJsonPath": "$.attribs.hni",        // JsonPath into the response body
     *     "targetPath":     "${TicketID}/hni.json",  // MinIO path, supports ${var} placeholders
     *     "targetVarName":  "hniMinioPath"           // process variable to store the resulting MinIO path
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
            log.warn("3c: minioExtract is not a valid JSON array — skipping. Value: {}", extractStr);
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
            log.error("3c: Could not get storage provider", e);
            throw new BpmnError("STORAGE_ERROR", "Could not get storage provider: " + e.getMessage());
        }

        for (int i = 0; i < extractions.length(); i++) {
            JSONObject entry = extractions.getJSONObject(i);
            String sourceJsonPath = entry.optString("sourceJsonPath", "");
            String targetPath     = entry.optString("targetPath", "");
            String targetVarName  = entry.optString("targetVarName", "");

            if (sourceJsonPath.isEmpty() || targetPath.isEmpty() || targetVarName.isEmpty()) {
                log.warn("3c: Entry {} is missing sourceJsonPath, targetPath, or targetVarName — skipping", i);
                continue;
            }

            Object extracted;
            try {
                extracted = documentContext.read(sourceJsonPath);
            } catch (Exception e) {
                log.warn("3c: Could not extract path '{}' for entry {} — skipping", sourceJsonPath, i);
                continue;
            }

            // Serialize extracted value to JSON string
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
                log.info("3c: Extracted '{}' → MinIO '{}' → var '{}'", sourceJsonPath, resolvedTargetPath, targetVarName);
            } catch (Exception e) {
                log.error("3c: Failed to upload to MinIO path '{}': {}", resolvedTargetPath, e.getMessage());
                throw new BpmnError("STORAGE_ERROR", "Failed to store extracted MinIO file: " + resolvedTargetPath);
            }
        }
    }

    // --- Helper Methods ---

    private String resolvePlaceholders(String input, DelegateExecution execution, Properties props) {
        if (input == null || input.isEmpty()) return input;

        Pattern pattern = Pattern.compile("\\$\\{([^}]+)\\}");
        Matcher matcher = pattern.matcher(input);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String token = matcher.group(1);
            String replacement = "";

            if (token.startsWith("appproperties.")) {
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