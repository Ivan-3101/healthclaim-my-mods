package com.DronaPay.frm.HealthClaim;

import com.DronaPay.frm.HealthClaim.generic.delegates.GenericAgentExecutorDelegate;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.json.JSONObject;

@Slf4j
public class OCROnDoc implements JavaDelegate {

    private final GenericAgentExecutorDelegate genericDelegate = new GenericAgentExecutorDelegate();

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.debug("OCROnDoc wrapper called - delegating to generic");

        JSONObject agentConfig = new JSONObject();
        agentConfig.put("agentId", "openaiVision");
        agentConfig.put("displayName", "OCR Extraction");
        agentConfig.put("enabled", true);
        agentConfig.put("critical", true);

        JSONObject config = new JSONObject();

        JSONObject inputMapping = new JSONObject();
        inputMapping.put("source", "documentVariable");
        inputMapping.put("transformation", "toBase64");
        config.put("inputMapping", inputMapping);

        JSONObject outputMapping = new JSONObject();
        JSONObject variablesToSet = new JSONObject();
        JSONObject ocrTextMapping = new JSONObject();
        ocrTextMapping.put("jsonPath", "/answer");
        ocrTextMapping.put("dataType", "string");
        variablesToSet.put("ocr_text", ocrTextMapping);
        outputMapping.put("variablesToSet", variablesToSet);
        config.put("outputMapping", outputMapping);

        JSONObject errorHandling = new JSONObject();
        errorHandling.put("onFailure", "throwError");
        errorHandling.put("continueOnError", false);
        errorHandling.put("errorCode", "failedOcr");
        config.put("errorHandling", errorHandling);

        agentConfig.put("config", config);

        execution.setVariable("currentAgentConfig", agentConfig);

        genericDelegate.execute(execution);

        log.debug("OCROnDoc completed via generic delegate");
    }
}