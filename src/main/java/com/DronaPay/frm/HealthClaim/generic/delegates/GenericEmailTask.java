package com.DronaPay.frm.HealthClaim.generic.delegates;

import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.Expression;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Properties;

@Slf4j
public class GenericEmailTask implements JavaDelegate {

    private Expression templateId;
    private Expression recipientEmail;
    private Expression ccEmails;
    private Expression bccEmails;
    private Expression bodyParams;
    private Expression enableAutoResponse;
    private Expression autoResponseMessage;

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("=== Generic Email Task Started ===");

        String tenantId = execution.getTenantId();
        String ticketId = String.valueOf(execution.getVariable("TicketID"));
        log.info("TicketID: {}, TenantID: {}", ticketId, tenantId);

        // Load tenant properties
        Properties props = loadTenantProperties(tenantId);

        // Extract field values
        int templateIdValue = Integer.parseInt((String) templateId.getValue(execution));
        String recipient = resolveValue((String) recipientEmail.getValue(execution), execution);
        String cc = ccEmails != null ? resolveValue((String) ccEmails.getValue(execution), execution) : "";
        String bcc = bccEmails != null ? resolveValue((String) bccEmails.getValue(execution), execution) : "";
        String bodyParamsJson = bodyParams != null ? (String) bodyParams.getValue(execution) : "{}";
        boolean autoResponse = enableAutoResponse != null && Boolean.parseBoolean((String) enableAutoResponse.getValue(execution));
        String messageCorrelation = autoResponseMessage != null ? (String) autoResponseMessage.getValue(execution) : null;

        log.info("Template ID: {}, Recipient: {}, Auto-Response: {}", templateIdValue, recipient, autoResponse);

        // Build email request
        JSONObject emailRequest = buildEmailRequest(templateIdValue, recipient, cc, bcc, bodyParamsJson, tenantId, execution);

        // Send email
        sendEmail(emailRequest, tenantId, props);

        log.info("Email sent successfully");

        // Handle auto-response
        if (autoResponse && messageCorrelation != null) {
            handleAutoResponse(execution, messageCorrelation, tenantId);
        }

        log.info("=== Generic Email Task Completed ===");
    }

    private Properties loadTenantProperties(String tenantId) throws Exception {
        Properties props = new Properties();
        String propsFile = "application.properties_" + tenantId;
        props.load(Thread.currentThread().getContextClassLoader().getResourceAsStream(propsFile));
        return props;
    }

    private String resolveValue(String value, DelegateExecution execution) {
        if (value == null || value.trim().isEmpty()) {
            return "";
        }

        // If it's a variable reference like ${varName}, extract the variable
        if (value.startsWith("${") && value.endsWith("}")) {
            String varName = value.substring(2, value.length() - 1);
            Object varValue = execution.getVariable(varName);
            return varValue != null ? varValue.toString() : "";
        }

        return value;
    }

    private JSONObject buildEmailRequest(int templateId, String recipient, String cc, String bcc,
                                         String bodyParamsJson, String tenantId, DelegateExecution execution) {
        JSONObject request = new JSONObject();

        request.put("itenantId", Integer.parseInt(tenantId));
        request.put("templateid", templateId);

        // Recipients
        JSONArray toEmails = new JSONArray();
        if (!recipient.isEmpty()) {
            toEmails.put(recipient);
        }
        request.put("toEmail", toEmails);

        // CC
        JSONArray ccEmails = new JSONArray();
        if (!cc.isEmpty()) {
            for (String email : cc.split(",")) {
                ccEmails.put(email.trim());
            }
        }
        request.put("ccEmail", ccEmails);

        // BCC
        JSONArray bccEmails = new JSONArray();
        if (!bcc.isEmpty()) {
            for (String email : bcc.split(",")) {
                bccEmails.put(email.trim());
            }
        }
        request.put("bccEmail", bccEmails);

        // Body parameters
        JSONObject bodyParamsObj = parseBodyParams(bodyParamsJson, execution);
        request.put("bodyParams", bodyParamsObj);

        log.debug("Email request: {}", request.toString());

        return request;
    }

    private JSONObject parseBodyParams(String bodyParamsJson, DelegateExecution execution) {
        JSONObject params = new JSONObject();

        if (bodyParamsJson == null || bodyParamsJson.trim().isEmpty() || bodyParamsJson.equals("{}")) {
            return params;
        }

        try {
            JSONObject rawParams = new JSONObject(bodyParamsJson);

            for (String key : rawParams.keySet()) {
                String value = rawParams.getString(key);
                String resolvedValue = resolveValue(value, execution);
                params.put(key, resolvedValue);
            }
        } catch (Exception e) {
            log.error("Error parsing body params: {}", bodyParamsJson, e);
        }

        return params;
    }

    private void sendEmail(JSONObject emailRequest, String tenantId, Properties props) throws Exception {
        boolean emailEnabled = Boolean.parseBoolean(props.getProperty("email.enable", "false"));

        if (!emailEnabled) {
            log.info("Email sending disabled - skipping actual send");
            return;
        }

        String emailProvider = props.getProperty("email.provider", "api");

        // Add SMTP config if provider is smtp
        if ("smtp".equals(emailProvider)) {
            JSONObject smtpProps = new JSONObject();
            smtpProps.put("mail.smtp.host", props.getProperty("mail.smtp.host"));
            smtpProps.put("mail.smtp.port", props.getProperty("mail.smtp.port"));
            smtpProps.put("mail.username", props.getProperty("mail.username"));
            smtpProps.put("mail.password", props.getProperty("mail.password"));
            smtpProps.put("mail.sender", props.getProperty("mail.sender"));
            smtpProps.put("mail.smtp.auth", props.getProperty("mail.smtp.auth"));
            smtpProps.put("mail.smtp.connectiontimeout", props.getProperty("mail.smtp.connectiontimeout"));
            smtpProps.put("mail.smtp.timeout", props.getProperty("mail.smtp.timeout"));
            smtpProps.put("mail.smtp.writetimeout", props.getProperty("mail.smtp.writetimeout"));
            smtpProps.put("mail.smtp.starttls.enable", props.getProperty("mail.smtp.starttls.enable"));

            emailRequest.put("emailProvider", "smtp");
            emailRequest.put("providerProperties", smtpProps);
        }

        String uiserverUrl = props.getProperty("uiserver.url");
        String apiKey = props.getProperty("uiserver.apikey");
        String authType = props.getProperty("uiserver.auth.type", "apikey");

        String endpoint = uiserverUrl + "/api/v1/testing/email-service/send-email/tenant-id/" + tenantId;

        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpPost post = new HttpPost(endpoint);
            post.setHeader("Content-Type", "application/json");

            if ("apikey".equalsIgnoreCase(authType)) {
                post.setHeader("X-API-Key", apiKey);
            }

            post.setEntity(new StringEntity(emailRequest.toString(), StandardCharsets.UTF_8));

            try (CloseableHttpResponse response = client.execute(post)) {
                int statusCode = response.getStatusLine().getStatusCode();
                String responseBody = response.getEntity() != null ? EntityUtils.toString(response.getEntity()) : "";

                log.info("Email API response - Status: {}, Body: {}", statusCode, responseBody);

                if (statusCode != 200) {
                    log.error("Email sending failed with status: {}", statusCode);
                }
            }
        }
    }

    private void handleAutoResponse(DelegateExecution execution, String messageName, String tenantId) {
        Connection conn = null;

        try {
            conn = execution.getProcessEngine()
                    .getProcessEngineConfiguration()
                    .getDataSource()
                    .getConnection();

            RuntimeService runtimeService = execution.getProcessEngineServices().getRuntimeService();
            String businessKey = execution.getBusinessKey();
            String workflowKey = (String) execution.getVariable("workflowKey");
            if (workflowKey == null) {
                workflowKey = "HealthClaim"; // fallback
            }

            Connection finalConn = conn;
            String finalWorkflowKey = workflowKey;

            new Thread(() -> {
                try {
                    Thread.sleep(60 * 1000); // Wait 60 seconds

                    PreparedStatement stmt = finalConn.prepareStatement(
                            "SELECT activeflag FROM ui.templateresponse WHERE templatename = ?");
                    stmt.setString(1, finalWorkflowKey);

                    ResultSet rs = stmt.executeQuery();
                    String enabled = "N";
                    if (rs.next()) {
                        enabled = rs.getString("activeflag");
                    }

                    rs.close();
                    stmt.close();
                    finalConn.close();

                    if ("Y".equals(enabled)) {
                        log.info("Auto-response triggered - correlating message: {}", messageName);
                        runtimeService.createMessageCorrelation(messageName)
                                .processInstanceBusinessKey(businessKey)
                                .correlate();
                        log.info("Message correlated successfully");
                    } else {
                        log.info("Auto-response disabled in database");
                    }
                } catch (Exception e) {
                    log.error("Error in auto-response handler", e);
                }
            }).start();

            log.info("Auto-response handler thread spawned for message: {}", messageName);

        } catch (Exception e) {
            log.error("Error setting up auto-response", e);
            if (conn != null) {
                try {
                    conn.close();
                } catch (Exception ex) {
                    log.error("Error closing connection", ex);
                }
            }
        }
    }
}