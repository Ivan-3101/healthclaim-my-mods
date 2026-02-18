package com.DronaPay.frm.HealthClaim;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import com.DronaPay.generic.utils.TenantPropertiesUtil;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.json.JSONObject;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class APIServices {

    private String uiserver_url;
    private String uiserver_api_key;
    private String uiserver_auth_type;
    private Boolean dummy_stub;
    private Boolean email_enable;
    private Map<String, String> smtpProps = new HashMap<>();
    private String emailProvider;
    private String spring_api_url;
    private String spring_api_key;
    private String ai_agent_url;
    private String ai_agent_username;
    private String ai_agent_password;

    /**
     * Constructor that loads configuration from database (REQUIRED)
     * NO fallback to properties file - will throw exception if config missing
     *
     * @param tenantid - Tenant ID
     * @param workflowConfig - Workflow configuration from database (REQUIRED)
     * @throws IOException
     * @throws IllegalArgumentException if external API config not found in database
     */
    public APIServices(String tenantid, JSONObject workflowConfig) throws IOException {
        if (workflowConfig == null) {
            throw new IllegalArgumentException("workflowConfig cannot be null - database configuration is required");
        }

        // Load tenant properties (for email, storage, etc.)
        Properties props = TenantPropertiesUtil.getTenantProps(tenantid);

        // Email and UI server settings (still from properties)
        this.uiserver_url = props.getProperty("uiserver.url");
        this.uiserver_api_key = props.getProperty("uiserver.apikey");
        this.uiserver_auth_type = props.getProperty("uiserver.auth.type");
        this.dummy_stub = Boolean.parseBoolean(props.getProperty("dummy.stub"));
        this.email_enable = Boolean.parseBoolean(props.getProperty("email.enable"));
        this.emailProvider = props.getProperty("email.provider");
        this.smtpProps.put("mail.smtp.host", props.getProperty("mail.smtp.host"));
        this.smtpProps.put("mail.smtp.port", props.getProperty("mail.smtp.port"));
        this.smtpProps.put("mail.username", props.getProperty("mail.username"));
        this.smtpProps.put("mail.password", props.getProperty("mail.password"));
        this.smtpProps.put("mail.sender", props.getProperty("mail.sender"));
        this.smtpProps.put("mail.smtp.auth", props.getProperty("mail.smtp.auth"));
        this.smtpProps.put("mail.smtp.connectiontimeout", props.getProperty("mail.smtp.connectiontimeout"));
        this.smtpProps.put("mail.smtp.timeout", props.getProperty("mail.smtp.timeout"));
        this.smtpProps.put("mail.smtp.writetimeout", props.getProperty("mail.smtp.writetimeout"));
        this.smtpProps.put("mail.smtp.starttls.enable", props.getProperty("mail.smtp.starttls.enable"));

        // Load external API configuration from database (NO FALLBACK)
        loadExternalAPIConfig(workflowConfig);

        log.info("APIServices initialized - Spring API: {}, Agent API: {}",
                this.spring_api_url, this.ai_agent_url);
    }

    /**
     * Load external API configuration from database - NO FALLBACK
     * Throws exception if configuration not found
     */
    private void loadExternalAPIConfig(JSONObject workflowConfig) {
        if (!workflowConfig.has("externalAPIs")) {
            throw new IllegalArgumentException(
                    "External API configuration not found in database. " +
                            "Please ensure 'externalAPIs' section exists in ui.workflowmasters.filterparams"
            );
        }

        JSONObject externalAPIs = workflowConfig.getJSONObject("externalAPIs");

        // Load Spring API config (REQUIRED)
        if (!externalAPIs.has("springAPI")) {
            throw new IllegalArgumentException(
                    "Spring API configuration not found in database. " +
                            "Please add 'springAPI' section to externalAPIs configuration"
            );
        }

        JSONObject springAPI = externalAPIs.getJSONObject("springAPI");

        if (!springAPI.has("baseUrl") || !springAPI.has("apiKey")) {
            throw new IllegalArgumentException(
                    "Spring API configuration incomplete. Required fields: baseUrl, apiKey"
            );
        }

        this.spring_api_url = springAPI.getString("baseUrl");
        this.spring_api_key = springAPI.getString("apiKey");
        log.info("Loaded Spring API config from database: {}", this.spring_api_url);

        // Load Agent API config (REQUIRED)
        if (!externalAPIs.has("agentAPI")) {
            throw new IllegalArgumentException(
                    "Agent API configuration not found in database. " +
                            "Please add 'agentAPI' section to externalAPIs configuration"
            );
        }

        JSONObject agentAPI = externalAPIs.getJSONObject("agentAPI");

        if (!agentAPI.has("baseUrl") || !agentAPI.has("username") || !agentAPI.has("password")) {
            throw new IllegalArgumentException(
                    "Agent API configuration incomplete. Required fields: baseUrl, username, password"
            );
        }

        this.ai_agent_url = agentAPI.getString("baseUrl");
        this.ai_agent_username = agentAPI.getString("username");
        this.ai_agent_password = agentAPI.getString("password");
        log.info("Loaded Agent API config from database: {}", this.ai_agent_url);
    }

    public void sendEmailViaUiserver(JSONObject emailReqBody, String tenantid) throws Exception {

        if (this.email_enable) {
            if (emailProvider.equals("smtp")) {
                emailReqBody.put("emailProvider", "smtp");
                emailReqBody.put("providerProperties", this.smtpProps);
            }
            log.info("Send Email initiated with req body " + emailReqBody);
            try (CloseableHttpClient client = HttpClients.createDefault()) {
                HttpPost httpPost = new HttpPost(
                        this.uiserver_url + "/api/v1/testing/email-service/send-email/tenant-id/" + tenantid);
                httpPost.addHeader("Content-Type", "application/json");
                if (this.uiserver_auth_type.equalsIgnoreCase("apikey")) {
                    httpPost.addHeader("X-API-Key", this.uiserver_api_key);
                } else {
                    httpPost.addHeader("Authorization", TokenUtil.getToken(tenantid));
                }
                httpPost.setEntity(new StringEntity(emailReqBody.toString()));
                try (CloseableHttpResponse response = client.execute(httpPost)) {
                    log.info(
                            "response from uiserver for email status code " + response.getStatusLine().getStatusCode());
                    String resBody = (response.getEntity() != null) ? EntityUtils.toString(response.getEntity()) : "";
                    log.info("response from uiserver for email body " + resBody);
                } catch (Exception e) {
                    log.error("Exception while sending email " + e);
                }
            } catch (Exception e) {
                log.error("Exception while sending email " + e);
            }
        }

    }

    public void dummyStub(String buissnessKey, String templateName, String tenantid)
            throws IOException, InterruptedException {
        if (dummy_stub) {
            log.info("Dummy stub called with template name " + templateName + " and buissnesskey " + buissnessKey);
            HttpRequest karixMailReq = HttpRequest.newBuilder()
                    .uri(URI.create(
                            this.uiserver_url + "/api/v1/dummy/ivr/risk-notification-ivr-call/" + buissnessKey + "/"
                                    + templateName))
                    .header("Content-Type", "application/json")
                    .header("X-API-Key", this.uiserver_api_key)
                    .header("Authorization", TokenUtil.getToken(tenantid))
                    .GET()
                    .build();

            HttpClient client = HttpClient.newHttpClient();

            client.sendAsync(karixMailReq, HttpResponse.BodyHandlers.ofString())
                    .thenApply(HttpResponse::body)
                    .thenAccept(log::info);

        }

    }

    public CloseableHttpResponse verifyMasterData(String policyId)
            throws IOException, URISyntaxException {

        CloseableHttpClient client = HttpClients.createDefault();
        URI uri = new URI(String.format("%s/accounts/%s", this.spring_api_url, policyId));
        log.info("url " + uri.toString());
        HttpGet httpGet = new HttpGet(uri.toString());

        httpGet.addHeader("X-API-Key", this.spring_api_key.trim());

        log.info("X-API-Key " + this.spring_api_key);

        CloseableHttpResponse response = client.execute(httpGet);

        return response;

    }

    public CloseableHttpResponse FWADecisioning(String body)
            throws IOException, URISyntaxException {

        log.info("FWA decision req body "+ body);

        CloseableHttpClient client = HttpClients.createDefault();
        URI uri = new URI(String.format("%s/score", this.spring_api_url));
        HttpPost httpPost = new HttpPost(uri);
        httpPost.addHeader("Content-Type", "application/json");

        httpPost.addHeader("X-API-Key", this.spring_api_key);

        httpPost.setEntity(new StringEntity(body));

        CloseableHttpResponse response = client.execute(httpPost);

        return response;

    }

    public CloseableHttpResponse claimCost(String body)
            throws IOException, URISyntaxException {

        log.info("Claim Cost req body "+ body);
        CloseableHttpClient client = HttpClients.createDefault();
        URI uri = new URI(String.format("%s/score", this.spring_api_url));
        HttpPost httpPost = new HttpPost(uri);
        httpPost.addHeader("Content-Type", "application/json");

        httpPost.addHeader("X-API-Key", this.spring_api_key);

        httpPost.setEntity(new StringEntity(body));

        CloseableHttpResponse response = client.execute(httpPost);

        return response;

    }

    public CloseableHttpResponse callAgent(String body) throws IOException, URISyntaxException {

        log.info("Call agent API Called ");

        CloseableHttpClient client = HttpClients.createDefault();
        URI uri = new URI(String.format("%s/agent", this.ai_agent_url));
        HttpPost httpPost = new HttpPost(uri);
        httpPost.addHeader("Content-Type", "application/json");
        String auth = this.ai_agent_username + ":" + this.ai_agent_password;
        String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
        String authHeader = "Basic " + encodedAuth;
        httpPost.setHeader("Authorization", authHeader);

        httpPost.setEntity(new StringEntity(body, StandardCharsets.UTF_8));
        CloseableHttpResponse response = client.execute(httpPost);

        return response;

    }

    public CloseableHttpResponse callPolicyComparatorAgent(String body) throws IOException, URISyntaxException {

        log.info("Call Policy Comparator Agent API Called with body " + body);

        CloseableHttpClient client = HttpClients.createDefault();
        URI uri = new URI(String.format("%s/agent", this.ai_agent_url));
        HttpPost httpPost = new HttpPost(uri);
        httpPost.addHeader("Content-Type", "application/json");

        String auth = this.ai_agent_username + ":" + this.ai_agent_password;
        String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
        String authHeader = "Basic " + encodedAuth;
        httpPost.setHeader("Authorization", authHeader);

        httpPost.setEntity(new StringEntity(body, StandardCharsets.UTF_8));
        CloseableHttpResponse response = client.execute(httpPost);

        return response;
    }

}