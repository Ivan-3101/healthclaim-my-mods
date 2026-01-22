package com.DronaPay.frm.HealthClaim.generic.delegates;

import com.DronaPay.frm.HealthClaim.generic.services.ConfigurationService;
import com.DronaPay.frm.HealthClaim.generic.services.DocumentProcessingService;
import com.DronaPay.frm.HealthClaim.generic.storage.MinIOStorageProvider;
import com.DronaPay.frm.HealthClaim.generic.storage.StorageProvider;
import lombok.extern.slf4j.Slf4j;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.Expression;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.json.JSONArray;
import org.json.JSONObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;

@Slf4j
public class GenericTicketIdGenerator implements JavaDelegate {

    private Expression configKey;

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("=== Generic Ticket Generator Started ===");

        String configKeyStr = (configKey != null) ? (String) configKey.getValue(execution) : null;
        if (configKeyStr == null || configKeyStr.trim().isEmpty()) {
            throw new RuntimeException("GenericTicketIdGenerator: 'configKey' is missing in BPMN.");
        }

        String tenantId = execution.getTenantId();
        String workflowKey = execution.getProcessEngineServices().getRepositoryService()
                .getProcessDefinition(execution.getProcessDefinitionId()).getKey();

        log.info("Loading config for Key: '{}', Workflow: '{}', Tenant: '{}'", configKeyStr, workflowKey, tenantId);

        Connection connection = null;
        try {
            connection = execution.getProcessEngine().getProcessEngineConfiguration().getDataSource().getConnection();

            JSONObject fullWorkflowConfig = ConfigurationService.loadWorkflowConfig(workflowKey, tenantId, connection);

            if (!fullWorkflowConfig.has("genericWorkflowDelegateConfigurations") ||
                    !fullWorkflowConfig.getJSONObject("genericWorkflowDelegateConfigurations").has(configKeyStr)) {
                throw new RuntimeException("Configuration not found for key: " + configKeyStr);
            }

            JSONObject stageConfig = fullWorkflowConfig.getJSONObject("genericWorkflowDelegateConfigurations")
                    .getJSONObject(configKeyStr);

            Map<String, Object> context = new HashMap<>();
            if (stageConfig.has("initialVariablesRootObj")) {
                resolveInitialVariables(execution, stageConfig.getJSONArray("initialVariablesRootObj"), context, tenantId, workflowKey);
            }

            if (stageConfig.has("steps")) {
                JSONArray steps = stageConfig.getJSONArray("steps");
                for (int i = 0; i < steps.length(); i++) {
                    executeStep(execution, steps.getJSONObject(i), context, connection, tenantId);
                }
            }

        } finally {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        }
        log.info("=== Generic Ticket Generator Completed Successfully ===");
    }

    private void resolveInitialVariables(DelegateExecution execution, JSONArray variablesConfig, Map<String, Object> context, String tenantId, String workflowKey) {
        for (int i = 0; i < variablesConfig.length(); i++) {
            JSONObject varConf = variablesConfig.getJSONObject(i);
            String key = varConf.getString("key");

            if (varConf.has("executionMethod") && varConf.getBoolean("executionMethod")) {
                switch (key) {
                    case "tenantId": context.put(key, tenantId); break;
                    case "workflowKey": context.put(key, workflowKey); break;
                    case "processInstanceId": context.put(key, execution.getProcessInstanceId()); break;
                    case "stageName": context.put(key, execution.getCurrentActivityId()); break;
                    default: log.warn("Unknown execution variable: {}", key);
                }
            } else if (varConf.has("processVariable") && varConf.getBoolean("processVariable")) {
                String sourceVar = varConf.optString("source", key);
                Object val = execution.getVariable(sourceVar);
                context.put(key, val != null ? val : new HashMap<>());
            } else if (varConf.has("staticValue")) {
                context.put(key, varConf.get("staticValue"));
            }
        }
    }

    private void executeStep(DelegateExecution execution, JSONObject step, Map<String, Object> context, Connection conn, String tenantId) throws Exception {
        String type = step.getString("type");
        log.debug("Executing step: {}", type);

        if ("SqlQueryExecution".equalsIgnoreCase(type)) {
            executeSqlStep(execution, step, context, conn);
        } else if ("UploadToS3".equalsIgnoreCase(type)) {
            executeUploadStep(execution, step, context, tenantId);
        } else if ("SetProcessVariables".equalsIgnoreCase(type)) {
            executeSetVariablesStep(execution, step, context);
        }
    }

    private void executeSqlStep(DelegateExecution execution, JSONObject step, Map<String, Object> context, Connection conn) throws Exception {
        JSONObject config = step.getJSONObject("config");
        String query = config.getString("query");
        JSONArray params = config.optJSONArray("parameters");

        try (PreparedStatement stmt = conn.prepareStatement(query)) {
            if (params != null) {
                for (int i = 0; i < params.length(); i++) {
                    JSONObject param = params.getJSONObject(i);
                    String key = param.getString("key");
                    Object value = context.get(key);
                    stmt.setObject(i + 1, value);
                }
            }

            if ("select".equalsIgnoreCase(config.optString("queryType"))) {
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        String returnKey = step.optString("returnValueKey");
                        Object result = rs.getObject(returnKey);
                        String contextKey = step.optString("returnValueObjKey");
                        if (!contextKey.isEmpty()) {
                            context.put(contextKey, result);
                        }
                        if (step.has("processVariablesToSetAfterExecution")) {
                            JSONArray varsToSet = step.getJSONArray("processVariablesToSetAfterExecution");
                            for(int j=0; j<varsToSet.length(); j++) {
                                JSONObject v = varsToSet.getJSONObject(j);
                                execution.setVariable(v.getString("key"), result);
                            }
                        }
                    }
                }
            } else {
                stmt.executeUpdate();
            }
        }
    }

    private void executeUploadStep(DelegateExecution execution, JSONObject step, Map<String, Object> context, String tenantId) throws Exception {
        Object docsObj = context.get("docsObj");

        // --- LOGIC CHANGE FOR PATH PATTERN ---
        // 1. Try to get it from the JSON Step configuration
        String pathPattern = step.optString("pathPattern");

        Properties props = ConfigurationService.getTenantProperties(tenantId);

        // 2. If missing in JSON, fetch from application.properties
        if (pathPattern == null || pathPattern.trim().isEmpty()) {
            pathPattern = props.getProperty("storage.pathPattern", "{tenantId}/{workflowKey}/{ticketId}/{stageName}/");
            log.info("Using 'storage.pathPattern' from application.properties: {}", pathPattern);
        } else {
            log.info("Using 'pathPattern' from JSON Config: {}", pathPattern);
        }
        // -------------------------------------

        String resolvedPath = pathPattern;
        for (String key : context.keySet()) {
            resolvedPath = resolvedPath.replace("{" + key + "}", String.valueOf(context.get(key)));
        }

        log.info("Processing upload to path: {}", resolvedPath);

        StorageProvider storage = new MinIOStorageProvider(props);
        Map<String, String> documentPaths = new HashMap<>();

        try {
            if (docsObj instanceof Map) {
                Map<String, Object> docsMap = (Map<String, Object>) docsObj;
                for (Map.Entry<String, Object> entry : docsMap.entrySet()) {
                    String docName = entry.getKey();
                    String base64Content = (String) entry.getValue();
                    byte[] content = Base64.getDecoder().decode(base64Content);

                    String fullPath = resolvedPath + docName;
                    String url = storage.uploadDocument(fullPath, content, "application/octet-stream");
                    documentPaths.put(docName, fullPath);
                }
            }

            execution.setVariable("documentPaths", documentPaths);
            execution.setVariable("attachmentVars", new ArrayList<>(documentPaths.keySet()));

            Map<String, Map<String, Object>> fileProcessMap = DocumentProcessingService.initializeFileProcessMap(documentPaths.keySet());
            execution.setVariable("fileProcessMap", fileProcessMap);

            log.info("Uploaded {} documents.", documentPaths.size());

        } finally {
            if (storage instanceof MinIOStorageProvider) {
                ((MinIOStorageProvider) storage).close();
            }
        }
    }

    private void executeSetVariablesStep(DelegateExecution execution, JSONObject step, Map<String, Object> context) {
        if (step.has("variables")) {
            JSONArray vars = step.getJSONArray("variables");
            for (int i = 0; i < vars.length(); i++) {
                JSONObject v = vars.getJSONObject(i);
                String varName = v.getString("key");
                Object value;

                if (v.has("staticValue")) {
                    value = v.get("staticValue");
                } else if (v.has("sourcePath")) {
                    value = context.get(v.getString("sourcePath"));
                } else {
                    continue;
                }
                execution.setVariable(varName, value);
            }
        }
    }
}