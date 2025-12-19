package com.DronaPay.frm.HealthClaim.generic.delegates;

import com.DronaPay.frm.HealthClaim.APIServices;
import com.DronaPay.frm.HealthClaim.generic.services.ConfigurationService;
import lombok.extern.slf4j.Slf4j;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.json.JSONArray;
import org.json.JSONObject;

import java.sql.Connection;
import java.util.Properties;

/**
 * Generic Email Delegate - Handles all email types through configuration
 *
 * Input Variables Required:
 * - emailType: String (e.g., "approval", "rejection", "requestInfo", "reminder", "missingInfo")
 * - workflowKey: String (e.g., "HealthClaim", "MotorClaim") - optional, defaults to "HealthClaim"
 *
 * Process Variables Used:
 * - TicketID: For logging
 * - sender_email: Recipient email address
 * - holder_name: Policy holder name for email body
 * - policy_id: Policy ID for email body
 */
@Slf4j
public class GenericEmailDelegate implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("=== Generic Email Delegate Started ===");
        log.info("TicketID: {}", execution.getVariable("TicketID"));

        String tenantId = execution.getTenantId();

        // 1. Get email type from input parameter
        String emailType = (String) execution.getVariable("emailType");
        if (emailType == null || emailType.isEmpty()) {
            throw new IllegalArgumentException("emailType parameter is required");
        }

        // 2. Get workflow key (optional, defaults to HealthClaim)
        String workflowKey = (String) execution.getVariable("workflowKey");
        if (workflowKey == null || workflowKey.isEmpty()) {
            workflowKey = "HealthClaim";
        }

        log.info("Sending email - Type: {}, Workflow: {}, Tenant: {}", emailType, workflowKey, tenantId);

        // 3. Load workflow configuration from database
        Connection conn = execution.getProcessEngine()
                .getProcessEngineConfiguration()
                .getDataSource()
                .getConnection();

        JSONObject workflowConfig = ConfigurationService.loadWorkflowConfig(workflowKey, tenantId, conn);
        conn.close();

        // 4. Load email configuration
        EmailConfig config = loadEmailConfiguration(emailType, tenantId);

        // 5. Build email request body
        JSONObject reqBody = buildEmailRequest(config, execution, tenantId);

        // 6. Send email (with workflowConfig)
        APIServices apiServices = new APIServices(tenantId, workflowConfig);
        apiServices.sendEmailViaUiserver(reqBody, tenantId);

        log.info("Email sent successfully - Type: {}, Template ID: {}", emailType, config.templateId);
        log.info("=== Generic Email Delegate Completed ===");
    }

    /**
     * Load email configuration from tenant properties
     * Configuration format in application.properties_{tenantId}:
     *
     * email.approval.templateId=15
     * email.rejection.templateId=14
     * email.requestInfo.templateId=11
     * email.reminder.templateId=12
     * email.missingInfo.templateId=13
     *
     * email.recipientVariable=sender_email
     * email.bodyParam.name=holder_name
     * email.bodyParam.policyId=policy_id
     */
    private EmailConfig loadEmailConfiguration(String emailType, String tenantId) throws Exception {
        Properties props = ConfigurationService.getTenantProperties(tenantId);

        EmailConfig config = new EmailConfig();

        // Get template ID for this email type
        String templateIdKey = "email." + emailType + ".templateId";
        String templateIdStr = props.getProperty(templateIdKey);

        if (templateIdStr == null) {
            throw new IllegalArgumentException(
                    "Email template ID not found in properties for type: " + emailType +
                            " (looking for key: " + templateIdKey + ")"
            );
        }

        config.templateId = Integer.parseInt(templateIdStr);

        // Get recipient variable name (default: sender_email)
        config.recipientVariable = props.getProperty("email.recipientVariable", "sender_email");

        // Get body parameter mappings (default: name -> holder_name, policyId -> policy_id)
        config.bodyParamName = props.getProperty("email.bodyParam.name", "holder_name");
        config.bodyParamPolicyId = props.getProperty("email.bodyParam.policyId", "policy_id");

        log.debug("Loaded email config - Type: {}, Template ID: {}", emailType, config.templateId);

        return config;
    }

    /**
     * Build email request JSON for UI server API
     */
    private JSONObject buildEmailRequest(EmailConfig config, DelegateExecution execution, String tenantId) {
        JSONObject reqBody = new JSONObject();

        // Tenant ID
        reqBody.put("itenantId", Integer.parseInt(tenantId));

        // Template ID
        reqBody.put("templateid", config.templateId);

        // Recipients
        JSONArray toEmail = new JSONArray();
        String recipientEmail = (String) execution.getVariable(config.recipientVariable);
        if (recipientEmail != null && !recipientEmail.isEmpty()) {
            toEmail.put(recipientEmail);
        } else {
            log.warn("Recipient email variable '{}' is null or empty", config.recipientVariable);
        }
        reqBody.put("toEmail", toEmail);

        // CC and BCC (empty for now)
        reqBody.put("ccEmail", new JSONArray());
        reqBody.put("bccEmail", new JSONArray());

        // Body parameters
        JSONObject bodyParams = new JSONObject();

        String holderName = (String) execution.getVariable(config.bodyParamName);
        if (holderName != null) {
            bodyParams.put("name", holderName);
        }

        String policyId = (String) execution.getVariable(config.bodyParamPolicyId);
        if (policyId != null) {
            bodyParams.put("policyId", policyId);
        }

        reqBody.put("bodyParams", bodyParams);

        log.debug("Built email request - Template: {}, Recipient: {}", config.templateId, recipientEmail);

        return reqBody;
    }

    /**
     * Configuration holder class
     */
    private static class EmailConfig {
        int templateId;
        String recipientVariable;
        String bodyParamName;
        String bodyParamPolicyId;
    }
}