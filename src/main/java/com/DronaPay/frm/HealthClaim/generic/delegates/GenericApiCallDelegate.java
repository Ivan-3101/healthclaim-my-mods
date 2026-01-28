package com.DronaPay.frm.HealthClaim.generic.delegates;

import com.DronaPay.frm.HealthClaim.TenantPropertiesUtil;
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
import org.json.JSONObject;

import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class GenericApiCallDelegate implements JavaDelegate {

    // 1. INPUTS (Injected from the Camunda Element Template)
    private Expression url;          // e.g., "${appproperties.url}/accounts/${processVariable.id}"
    private Expression method;       // e.g., "GET", "POST"
    private Expression headers;      // e.g., [{"key":"Authorization", "value":"..."}]
    private Expression body;         // JSON Payload for POST/PUT
    private Expression outputVar;    // Variable name to store the result (e.g., "verifyResponse")

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("=== Generic API Call Started ===");

        // 2. SETUP: Load Tenant-Specific Configuration
        // We check which tenant (e.g., "1") is running this workflow so we can load
        // the correct "application.properties_1" file.
        String tenantId = execution.getTenantId();
        Properties tenantProps = null;

        if (tenantId != null && !tenantId.isEmpty()) {
            try {
                // This helper utility loads "application.properties_{tenantId}"
                tenantProps = TenantPropertiesUtil.getTenantProps(tenantId);
            } catch (Exception e) {
                log.error("Failed to load properties for tenant: " + tenantId, e);
            }
        }

        // 3. READ INPUTS: Get values from the BPMN Process I.E. THE VALUES ENTERED INSIDE THE TEMPLATE
        String rawUrl = (String) url.getValue(execution);
        String reqMethod = (String) method.getValue(execution);
        String reqHeaders = headers != null ? (String) headers.getValue(execution) : "[]";
        String reqBody = body != null ? (String) body.getValue(execution) : "";
        String targetVar = outputVar != null ? (String) outputVar.getValue(execution) : "apiResponse";

        // 4. RESOLVING PLACEHOLDERS
        // We take strings like "${appproperties.api.key}" and replace them with real values
        // from the properties file or process variables.
        String finalUrl = resolvePlaceholders(rawUrl, execution, tenantProps);
        String finalHeaders = resolvePlaceholders(reqHeaders, execution, tenantProps);
        String finalBody = resolvePlaceholders(reqBody, execution, tenantProps);

        log.info("Making {} request to: {}", reqMethod, finalUrl);


        // 5. EXECUTE: Send the HTTP Request
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            // Build the GET/POST request object
            HttpRequestBase request = createRequest(reqMethod, finalUrl, finalBody);

            // Add Headers (parsing the JSON string into actual HTTP headers)
            if (finalHeaders != null && !finalHeaders.isEmpty() && !finalHeaders.equals("[]")) {
                org.json.JSONArray headerArray = new org.json.JSONArray(finalHeaders);
                for (int i = 0; i < headerArray.length(); i++) {
                    JSONObject h = headerArray.getJSONObject(i);
                    request.addHeader(h.getString("key"), h.getString("value"));
                }
            }

            // Fire the request and wait for response
            try (CloseableHttpResponse response = client.execute(request)) {
                int statusCode = response.getStatusLine().getStatusCode();
                String responseBody = EntityUtils.toString(response.getEntity());

                log.info("API Response Status: {}", statusCode);

                // 6. API FAIL LOGIC
                // If the API returns anything other than 200 OK (like 404 Not Found),
                // we immediately CRASH the workflow step using BpmnError.
                if (statusCode != 200) {
                    log.error("API Call Failed. Stopping Workflow. Status: {}", statusCode);

                    // Throwing an exception
                    // It will create an incident in the Cockpit and end execution.
                    throw new BpmnError("API_ERROR", "API Call failed with status code: " + statusCode);
                }

                // 7. API SUCCESS LOGIC: Store the Result
                // Reached here, since the status was 200.
                // Save Status Code
                execution.setVariable(targetVar + "_statusCode", statusCode);

                // Save Body (Try to save as JSON object, fallback to String)
                try {
                    ObjectValue respJson = Variables.objectValue(responseBody)
                            .serializationDataFormat("application/json").create();
                    execution.setVariable(targetVar, respJson);
                } catch (Exception e) {
                    execution.setVariable(targetVar, responseBody);
                }
            }
        }
        log.info("=== Generic API Call Completed ===");
    }

    // --- Helper Methods
    private String resolvePlaceholders(String input, DelegateExecution execution, Properties tenantProps) {
        if (input == null || input.isEmpty()) return input;

        // Regex pattern to find ${...}
        Pattern pattern = Pattern.compile("\\$\\{([^}]+)\\}");
        Matcher matcher = pattern.matcher(input);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String token = matcher.group(1);
            String replacement = "";

            // Check if it's a Property (from file) or Variable (from process)
            if (token.startsWith("appproperties.")) {
                String key = token.substring("appproperties.".length());
                if (tenantProps != null) {
                    replacement = tenantProps.getProperty(key, "");
                }
            } else if (token.startsWith("processVariable.")) {
                String key = token.substring("processVariable.".length());
                Object val = execution.getVariable(key);
                replacement = val != null ? val.toString() : "";
            } else {
                replacement = "${" + token + "}"; // Keep unknown tokens
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
                if (!body.isEmpty()) post.setEntity(new StringEntity(body));
                return post;
            case "PUT":
                HttpPut put = new HttpPut(url);
                if (!body.isEmpty()) put.setEntity(new StringEntity(body));
                return put;
            case "DELETE":
                return new HttpDelete(url);
            case "GET":
            default:
                return new HttpGet(url);
        }
    }
}