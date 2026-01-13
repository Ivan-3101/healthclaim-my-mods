package com.DronaPay.frm.HealthClaim;

import com.DronaPay.frm.HealthClaim.generic.delegates.GenericAgentExecutorDelegate;
import lombok.extern.slf4j.Slf4j;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.json.JSONObject;

@Slf4j
public class IdentifyForgedDocuments implements JavaDelegate {

    private final GenericAgentExecutorDelegate genericDelegate = new GenericAgentExecutorDelegate();

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.debug("IdentifyForgedDocuments wrapper called - delegating to generic");

        // Build agent config for backward compatibility
        JSONObject agentConfig = new JSONObject();
        agentConfig.put("agentId", "forgeryagent");
        agentConfig.put("displayName", "Forgery Detection");
        agentConfig.put("enabled", true);
        agentConfig.put("critical", false);

        JSONObject config = new JSONObject();

        JSONObject inputMapping = new JSONObject();
        inputMapping.put("source", "documentVariable");
        inputMapping.put("transformation", "toBase64");
        config.put("inputMapping", inputMapping);

        JSONObject outputMapping = new JSONObject();
        JSONObject variablesToSet = new JSONObject();
        JSONObject isForgedMapping = new JSONObject();
        isForgedMapping.put("jsonPath", "/answer");
        isForgedMapping.put("transformation", "mapSuspiciousToBoolean");
        isForgedMapping.put("dataType", "boolean");
        variablesToSet.put("isForged", isForgedMapping);
        outputMapping.put("variablesToSet", variablesToSet);
        config.put("outputMapping", outputMapping);

        JSONObject errorHandling = new JSONObject();
        errorHandling.put("onFailure", "logAndContinue");
        errorHandling.put("continueOnError", true);
        config.put("errorHandling", errorHandling);

        agentConfig.put("config", config);

        execution.setVariable("currentAgentConfig", agentConfig);

        genericDelegate.execute(execution);

        log.debug("IdentifyForgedDocuments completed via generic delegate");
    }
}