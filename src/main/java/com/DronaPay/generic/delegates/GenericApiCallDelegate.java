package com.DronaPay.generic.delegates;

import com.DronaPay.generic.utils.TenantPropertiesUtil;
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

import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class GenericApiCallDelegate implements JavaDelegate {

    // 1. INPUTS (Injected from the Camunda Element Template)
    private Expression baseUrl;    // Dropdown key that maps to a property prefix (e.g., "springapi" -> "springapi.url")
    private Expression route;      // Specific API route, supports process variables (e.g., "/accounts/${processVariable.policy_id}")
    private Expression authType;   // Authentication method (e.g., "xApiKey" -> reads "springapi.api.key" from properties)
    private Expression method;     // HTTP Method (e.g., "GET", "POST")
    private Expression body;       // JSON Payload for POST/PUT requests. Use "NA" as placeholder when no body is needed.
    private Expression outputVar;  // Variable name to store the result (e.g., "verifyResponse")

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("=== Generic API Call Started ===");

        // 2. SETUP: Load Tenant-Specific Configuration
        // We check which tenant (e.g., "1") is running this workflow so we can load
        // the correct "application.properties_1" file.
        String tenantId = execution.getTenantId();
        if (tenantId == null || tenantId.isEmpty()) {
            throw new BpmnError("CONFIG_ERROR", "Tenant ID is missing from execution context.");
        }

        // This helper utility loads "application.properties_{tenantId}"
        Properties props = TenantPropertiesUtil.getTenantProps(tenantId);

        // 3. READ INPUTS: Get values from the BPMN Process I.E. THE VALUES ENTERED INSIDE THE TEMPLATE
        if (baseUrl == null || route == null) {
            throw new BpmnError("CONFIG_ERROR", "Missing required fields: 'baseUrl' and 'route'. Ensure the BPMN task uses the correct template.");
        }

        String baseUrlKey = (String) baseUrl.getValue(execution);  // e.g., "springapi"
        String routePath  = (String) route.getValue(execution);    // e.g., "/accounts/${processVariable.policy_id}"
        String authMethod = authType != null ? (String) authType.getValue(execution) : "none";
        String reqMethod  = method != null ? (String) method.getValue(execution) : "GET";
        String targetVar  = outputVar != null ? (String) outputVar.getValue(execution) : "apiResponse";

        // Read body from template. "NA" is the placeholder used when no body is needed (e.g., GET requests).
        // Treat "NA" the same as empty so no entity is attached to the request.
        String reqBody = body != null ? (String) body.getValue(execution) : "NA";
        if ("NA".equalsIgnoreCase(reqBody)) reqBody = "";

        // 4. CONSTRUCT URL
        // The baseUrlKey (e.g., "springapi") is used as a prefix to look up the base URL
        // from the tenant properties file. e.g., "springapi.url" = "https://main.dev.dronapay.net/springapi"
        String resolvedBase = props.getProperty(baseUrlKey + ".url");
        if (resolvedBase == null) {
            throw new BpmnError("CONFIG_ERROR", "Property not found: " + baseUrlKey + ".url");
        }

        // Normalize slashes so we don't end up with double slashes in the final URL
        if (resolvedBase.endsWith("/")) resolvedBase = resolvedBase.substring(0, resolvedBase.length() - 1);

        // Resolve any ${processVariable.x} placeholders inside the route
        // e.g., "/accounts/${processVariable.policy_id}" -> "/accounts/POL-12345"
        String resolvedRoute = resolvePlaceholders(routePath, execution, props);
        if (!resolvedRoute.startsWith("/")) resolvedRoute = "/" + resolvedRoute;

        String finalUrl  = resolvedBase + resolvedRoute;
        String finalBody = resolvePlaceholders(reqBody, execution, props);

        log.info("Making {} request to: {}", reqMethod, finalUrl);

        // 5. EXECUTE: Send the HTTP Request
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            // Build the GET/POST/PUT/DELETE request object
            HttpRequestBase request = createRequest(reqMethod, finalUrl, finalBody);

            // 6. AUTHENTICATION
            // Instead of a freeform headers JSON like the old template, auth is now handled
            // by the authType dropdown. "xApiKey" reads the key from properties automatically.
            // e.g., "springapi.api.key" = "4643a381-16e2-41c6-87ea-fac8df253dee"
            if ("xApiKey".equalsIgnoreCase(authMethod)) {
                String apiKey = props.getProperty(baseUrlKey + ".api.key");
                if (apiKey == null) {
                    throw new BpmnError("CONFIG_ERROR", "Property not found: " + baseUrlKey + ".api.key");
                }
                request.addHeader("x-api-key", apiKey);
            }

            request.addHeader("Content-Type", "application/json");

            // Fire the request and wait for response
            try (CloseableHttpResponse response = client.execute(request)) {
                int statusCode = response.getStatusLine().getStatusCode();
                String responseBody = EntityUtils.toString(response.getEntity());

                log.info("API Response Status: {}", statusCode);

                // 7. API FAIL LOGIC
                // If the API returns anything other than 200 OK (like 404 Not Found),
                // we immediately CRASH the workflow step using BpmnError.
                // It will create an incident in the Cockpit and stop execution.
                if (statusCode != 200) {
                    log.error("API Call Failed. Stopping Workflow. Status: {}, Body: {}", statusCode, responseBody);
                    throw new BpmnError("API_ERROR", "API Call failed with status code: " + statusCode);
                }

                // 8. API SUCCESS LOGIC: Store the Result
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

    private String resolvePlaceholders(String input, DelegateExecution execution, Properties props) {
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
                replacement = props != null ? props.getProperty(key, "") : "";
            } else if (token.startsWith("processVariable.")) {
                String key = token.substring("processVariable.".length());
                Object val = execution.getVariable(key);
                replacement = val != null ? val.toString() : "";
            } else {
                // Fallback: try to resolve as a direct process variable name
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