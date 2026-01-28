package com.DronaPay.frm.HealthClaim.generic.delegates;

import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.methods.*;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.Expression;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.bpm.engine.variable.value.ObjectValue;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class GenericApiCallDelegate implements JavaDelegate {

    // Inputs injected from the Element Template
    private Expression url;          // e.g., "${appproperties.springapi.url}/..."
    private Expression method;       // GET, POST, PUT, DELETE
    private Expression headers;      // JSON String: [{"key":"X-API-KEY", "value":"..."}]
    private Expression body;         // JSON String for payload
    private Expression outputVar;    // Name of variable to store result (e.g., "verificationResult")

    // Cache properties to avoid reloading file every time
    private static Properties appProperties;

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("=== Generic API Call Started ===");

        // 1. Resolve Inputs
        String rawUrl = (String) url.getValue(execution);
        String reqMethod = (String) method.getValue(execution);
        String reqHeaders = headers != null ? (String) headers.getValue(execution) : "[]";
        String reqBody = body != null ? (String) body.getValue(execution) : "";
        String targetVar = outputVar != null ? (String) outputVar.getValue(execution) : "apiResponse";

        // 2. Perform Placeholder Replacement
        String finalUrl = resolvePlaceholders(rawUrl, execution);
        String finalHeaders = resolvePlaceholders(reqHeaders, execution);
        String finalBody = resolvePlaceholders(reqBody, execution);

        log.info("Making {} request to: {}", reqMethod, finalUrl);

        // 3. Execute HTTP Request
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpRequestBase request = createRequest(reqMethod, finalUrl, finalBody);

            // Parse and add headers
            if (finalHeaders != null && !finalHeaders.isEmpty() && !finalHeaders.equals("[]")) {
                org.json.JSONArray headerArray = new org.json.JSONArray(finalHeaders);
                for (int i = 0; i < headerArray.length(); i++) {
                    JSONObject h = headerArray.getJSONObject(i);
                    request.addHeader(h.getString("key"), h.getString("value"));
                }
            }

            try (CloseableHttpResponse response = client.execute(request)) {
                int statusCode = response.getStatusLine().getStatusCode();
                String responseString = EntityUtils.toString(response.getEntity());

                log.info("API Response Status: {}", statusCode);

                // 4. Store Result
                // Store status code
                execution.setVariable(targetVar + "_statusCode", statusCode);

                // Store Body as Object (JSON)
                try {
                    ObjectValue respJson = Variables.objectValue(responseString)
                            .serializationDataFormat("application/json").create();
                    execution.setVariable(targetVar, respJson);
                } catch (Exception e) {
                    // Fallback to string if not JSON
                    execution.setVariable(targetVar, responseString);
                }
            }
        }
        log.info("=== Generic API Call Completed ===");
    }

    // --- Helper Methods ---

    /**
     * Replaces ${appproperties.X} and ${processVariable.Y} in a string.
     */
    private String resolvePlaceholders(String input, DelegateExecution execution) {
        if (input == null || input.isEmpty()) return input;

        // Regex to find patterns like ${...}
        Pattern pattern = Pattern.compile("\\$\\{([^}]+)\\}");
        Matcher matcher = pattern.matcher(input);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String token = matcher.group(1); // content inside ${}
            String replacement = "";

            if (token.startsWith("appproperties.")) {
                String key = token.substring("appproperties.".length());
                replacement = getAppProperty(key);
            } else if (token.startsWith("processVariable.")) {
                String key = token.substring("processVariable.".length());
                Object val = execution.getVariable(key);
                replacement = val != null ? val.toString() : "";
            } else {
                // Keep original if unrecognized
                replacement = "${" + token + "}";
            }

            // Escape $ and \ in replacement to avoid regex errors
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String getAppProperty(String key) {
        if (appProperties == null) {
            loadProperties();
        }
        return appProperties.getProperty(key, "");
    }

    private synchronized void loadProperties() {
        if (appProperties != null) return;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("application.properties")) {
            appProperties = new Properties();
            if (input == null) {
                log.error("Sorry, unable to find application.properties");
                return;
            }
            appProperties.load(input);
        } catch (Exception ex) {
            log.error("Error loading application.properties", ex);
        }
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