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
     * Constructor that loads configuration from database (preferred)
     * Falls back to properties file if database config not found
     *
     * @param tenantid - Tenant ID
     * @param workflowConfig - Workflow configuration from database (optional)
     * @throws IOException
     */
    public APIServices(String tenantid, JSONObject workflowConfig) throws IOException {
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

        // Load external API configuration from database or fallback to properties
        loadExternalAPIConfig(workflowConfig, props);

        log.info("APIServices initialized - Spring API: {}, Agent API: {}",
                this.spring_api_url, this.ai_agent_url);
    }

    /**
     * Legacy constructor for backward compatibility
     * Loads all configuration from properties file
     *
     * @param tenantid - Tenant ID
     * @throws IOException
     */
    public APIServices(String tenantid) throws IOException {
        this(tenantid, null);
    }

    /**
     * Load external API configuration from database with fallback to properties
     */
    private void loadExternalAPIConfig(JSONObject workflowConfig, Properties props) {
        boolean loadedFromDB = false;

        // Try to load from database first
        if (workflowConfig != null && workflowConfig.has("externalAPIs")) {
            try {
                JSONObject externalAPIs = workflowConfig.getJSONObject("externalAPIs");

                // Load Spring API config
                if (externalAPIs.has("springAPI")) {
                    JSONObject springAPI = externalAPIs.getJSONObject("springAPI");
                    this.spring_api_url = springAPI.getString("baseUrl");
                    this.spring_api_key = springAPI.getString("apiKey");
                    loadedFromDB = true;
                    log.debug("Loaded Spring API config from database");
                }

                // Load Agent API config
                if (externalAPIs.has("agentAPI")) {
                    JSONObject agentAPI = externalAPIs.getJSONObject("agentAPI");
                    this.ai_agent_url = agentAPI.getString("baseUrl");
                    this.ai_agent_username = agentAPI.getString("username");
                    this.ai_agent_password = agentAPI.getString("password");
                    loadedFromDB = true;
                    log.debug("Loaded Agent API config from database");
                }

            } catch (Exception e) {
                log.warn("Failed to load external API config from database, falling back to properties", e);
                loadedFromDB = false;
            }
        }

        // Fallback to properties file if database config not found
        if (!loadedFromDB) {
            this.spring_api_url = props.getProperty("springapi.url");
            this.spring_api_key = props.getProperty("springapi.api.key");
            this.ai_agent_url = props.getProperty("ai.agent.url");
            this.ai_agent_username = props.getProperty("ai.agent.username");
            this.ai_agent_password = props.getProperty("ai.agent.password");
            log.debug("Loaded API config from properties file (fallback)");
        }
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
        // The endpoint from the PDF
        URI uri = new URI(String.format("%s/agent", this.ai_agent_url));
//        HAVE TO CHANGE THE ABOVE URL TO THE DEV ONE, BEFORE ILL DO THE TEST ON POSTMAN TO THE API
        HttpPost httpPost = new HttpPost(uri);
        httpPost.addHeader("Content-Type", "application/json");

        // Assuming the same authentication as the other AI agent
        String auth = this.ai_agent_username + ":" + this.ai_agent_password;
        String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
        String authHeader = "Basic " + encodedAuth;
        httpPost.setHeader("Authorization", authHeader);

        httpPost.setEntity(new StringEntity(body, StandardCharsets.UTF_8));
        CloseableHttpResponse response = client.execute(httpPost);

        return response;
    }

}