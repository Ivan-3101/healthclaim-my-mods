package com.DronaPay.frm.HealthClaim;

import com.DronaPay.frm.HealthClaim.generic.delegates.GenericAgentExecutorDelegate;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.json.JSONObject;

@Slf4j
public class OCRToFHIR implements JavaDelegate {

    private final GenericAgentExecutorDelegate genericDelegate = new GenericAgentExecutorDelegate();

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.debug("OCRToFHIR wrapper called - delegating to generic");

        JSONObject agentConfig = new JSONObject();
        agentConfig.put("agentId", "ocrToFhir");
        agentConfig.put("displayName", "FHIR Conversion");
        agentConfig.put("enabled", true);
        agentConfig.put("critical", true);

        JSONObject config = new JSONObject();

        JSONObject inputMapping = new JSONObject();
        inputMapping.put("source", "processVariable");
        inputMapping.put("variableName", "ocr_text");
        config.put("inputMapping", inputMapping);

        JSONObject outputMapping = new JSONObject();
        JSONObject variablesToSet = new JSONObject();
        JSONObject fhirMapping = new JSONObject();
        fhirMapping.put("jsonPath", "/answer");
        fhirMapping.put("dataType", "json");
        variablesToSet.put("fhir_json", fhirMapping);
        outputMapping.put("variablesToSet", variablesToSet);
        config.put("outputMapping", outputMapping);

        JSONObject errorHandling = new JSONObject();
        errorHandling.put("onFailure", "throwError");
        errorHandling.put("continueOnError", false);
        config.put("errorHandling", errorHandling);

        agentConfig.put("config", config);

        execution.setVariable("currentAgentConfig", agentConfig);

        genericDelegate.execute(execution);

        log.debug("OCRToFHIR completed via generic delegate");
    }
}