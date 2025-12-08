package com.DronaPay.frm.HealthClaim;

import com.DronaPay.frm.HealthClaim.generic.delegates.GenericAgentExecutorDelegate;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.json.JSONObject;

/**
 * Document Classifier Wrapper Delegate
 * Wraps GenericAgentExecutorDelegate for backward compatibility
 */
@Slf4j
public class DocumentClassifierDelegate implements JavaDelegate {

    private final GenericAgentExecutorDelegate genericDelegate = new GenericAgentExecutorDelegate();

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.debug("DocumentClassifier wrapper called - delegating to generic");

        // Build agent config for backward compatibility
        JSONObject agentConfig = new JSONObject();
        agentConfig.put("agentId", "Document_Classifier");
        agentConfig.put("displayName", "Document Classifier");
        agentConfig.put("enabled", true);
        agentConfig.put("critical", false);

        JSONObject config = new JSONObject();

        // Input mapping - uses document from multi-instance loop
        JSONObject inputMapping = new JSONObject();
        inputMapping.put("source", "documentVariable");
        inputMapping.put("transformation", "toBase64");
        config.put("inputMapping", inputMapping);

        // Output mapping - stores full response in MinIO
        JSONObject outputMapping = new JSONObject();
        JSONObject variablesToSet = new JSONObject();

        // No need to set large response as process variable
        // Full response is stored in MinIO automatically

        outputMapping.put("variablesToSet", variablesToSet);
        config.put("outputMapping", outputMapping);

        // Error handling
        JSONObject errorHandling = new JSONObject();
        errorHandling.put("onFailure", "logAndContinue");
        errorHandling.put("continueOnError", true);
        config.put("errorHandling", errorHandling);

        agentConfig.put("config", config);

        // Set as current agent config
        execution.setVariable("currentAgentConfig", agentConfig);

        // Execute via generic delegate
        genericDelegate.execute(execution);

        log.debug("DocumentClassifier completed via generic delegate");
    }
}