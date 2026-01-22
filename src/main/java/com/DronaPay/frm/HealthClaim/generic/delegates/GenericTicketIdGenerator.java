package com.DronaPay.frm.HealthClaim.generic.delegates;

import com.DronaPay.frm.HealthClaim.generic.services.ConfigurationService;
import com.DronaPay.frm.HealthClaim.generic.services.DocumentProcessingService;
import com.DronaPay.frm.HealthClaim.generic.storage.MinIOStorageProvider;
import com.DronaPay.frm.HealthClaim.generic.storage.StorageProvider;
import lombok.extern.slf4j.Slf4j;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.Expression;
import org.cibseven.bpm.engine.delegate.JavaDelegate;
import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.bpm.engine.variable.value.ObjectValue;
import org.json.JSONArray;
import org.json.JSONObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;

@Slf4j
public class GenericTicketIdGenerator implements JavaDelegate {

    // The 'configKey' injected from the Camunda Modeler (e.g., "Generate TicketID and Workflow Name")
    private Expression configKey;

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("=== Generic Ticket Generator Started ===");

        // 1. Resolve the Configuration Key
        String configKeyStr = (configKey != null) ? (String) configKey.getValue(execution) : null;
        if (configKeyStr == null || configKeyStr.trim().isEmpty()) {
            throw new RuntimeException("GenericTicketIdGenerator: 'configKey' is missing in BPMN.");
        }

        // 2. Identify Context (Tenant & Workflow)
        String tenantId = execution.getTenantId();
        // Dynamically fetch the workflow key (e.g., "HealthClaim") from the process definition
        String workflowKey = execution.getProcessEngineServices().getRepositoryService()
                .getProcessDefinition(execution.getProcessDefinitionId()).getKey();
        
        log.info("Loading config for Key: '{}', Workflow: '{}', Tenant: '{}'", configKeyStr, workflowKey, tenantId);

        Connection connection = null;
        try {
            // Obtain JDBC connection to fetch configuration
            connection = execution.getProcessEngine().getProcessEngineConfiguration().getDataSource().getConnection();
            
            // 3. Load the Full Workflow Configuration (JSON blob) from Database
            JSONObject fullWorkflowConfig = ConfigurationService.loadWorkflowConfig(workflowKey, tenantId, connection);
            
            // 4. BOOTSTRAP AGENTS (Critical Fix)
            // We must load the "agents" list into a process variable NOW.
            // If we don't, the downstream multi-instance Agent tasks will have nothing to iterate over and will skip.
            loadAgentConfiguration(execution, fullWorkflowConfig);

            // 5. Locate Specific Stage Configuration
            if (!fullWorkflowConfig.has("genericWorkflowDelegateConfigurations") || 
                !fullWorkflowConfig.getJSONObject("genericWorkflowDelegateConfigurations").has(configKeyStr)) {
                throw new RuntimeException("Configuration not found for key: " + configKeyStr);
            }

            JSONObject stageConfig = fullWorkflowConfig.getJSONObject("genericWorkflowDelegateConfigurations")
                    .getJSONObject(configKeyStr);

            // 6. Initialize Execution Context
            // This Map acts as a local variable store for SQL parameters and path resolution.
            Map<String, Object> context = new HashMap<>();
            if (stageConfig.has("initialVariablesRootObj")) {
                resolveInitialVariables(execution, stageConfig.getJSONArray("initialVariablesRootObj"), context, tenantId, workflowKey);
            }

            // 7. Execute Dynamic Steps defined in JSON
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

    /**
     * Reads the "agents" array from the global config and sets the "agentList" process variable.
     * This bootstraps the multi-instance subprocesses downstream.
     */
    private void loadAgentConfiguration(DelegateExecution execution, JSONObject fullWorkflowConfig) {
        if (fullWorkflowConfig.has("agents")) {
            JSONArray agentsArray = fullWorkflowConfig.getJSONArray("agents");
            List<Map<String, Object>> agentList = new ArrayList<>();
            
            for (int i = 0; i < agentsArray.length(); i++) {
                JSONObject agent = agentsArray.getJSONObject(i);
                agentList.add(agent.toMap());
            }

            // Serialize as JSON object so Camunda can handle it in multi-instance loops
            ObjectValue agentListJson = Variables.objectValue(agentList)
                    .serializationDataFormat(Variables.SerializationDataFormats.JSON)
                    .create();
            
            execution.setVariable("agentList", agentListJson);
            log.info("Loaded {} agents into 'agentList' variable.", agentList.size());
        } else {
            log.warn("No 'agents' configuration found in workflow master.");
        }
    }

    /**
     * Resolves variables needed for the steps (e.g., pulling tenantId, TicketID, or input documents).
     */
    private void resolveInitialVariables(DelegateExecution execution, JSONArray variablesConfig, Map<String, Object> context, String tenantId, String workflowKey) {
        for (int i = 0; i < variablesConfig.length(); i++) {
            JSONObject varConf = variablesConfig.getJSONObject(i);
            String key = varConf.getString("key");

            if (varConf.has("executionMethod") && varConf.getBoolean("executionMethod")) {
                // Auto-resolve standard metadata
                switch (key) {
                    case "tenantId": context.put(key, tenantId); break;
                    case "workflowKey": context.put(key, workflowKey); break;
                    case "processInstanceId": context.put(key, execution.getProcessInstanceId()); break;
                    case "stageName": context.put(key, execution.getCurrentActivityId()); break;
                    default: log.warn("Unknown execution variable: {}", key);
                }
            } else if (varConf.has("processVariable") && varConf.getBoolean("processVariable")) {
                // Fetch a variable from the process (e.g., the input 'docs' object)
                String sourceVar = varConf.optString("source", key);
                Object val = execution.getVariable(sourceVar);
                // Allow null values here so we can check type/validity later in the specific step
                context.put(key, val); 
            } else if (varConf.has("staticValue")) {
                context.put(key, varConf.get("staticValue"));
            }
        }
    }

    /**
     * Dispatcher method: Routes the step to the correct handler based on "type".
     */
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

    /**
     * Executes arbitrary SQL (INSERT/UPDATE/SELECT) defined in the configuration.
     */
    private void executeSqlStep(DelegateExecution execution, JSONObject step, Map<String, Object> context, Connection conn) throws Exception {
        JSONObject config = step.getJSONObject("config");
        String query = config.getString("query");
        JSONArray params = config.optJSONArray("parameters");

        try (PreparedStatement stmt = conn.prepareStatement(query)) {
            // Map parameters from Context to the SQL statement
            if (params != null) {
                for (int i = 0; i < params.length(); i++) {
                    JSONObject param = params.getJSONObject(i);
                    String key = param.getString("key");
                    Object value = context.get(key);
                    stmt.setObject(i + 1, value); // Standard JDBC index starts at 1
                }
            }

            // Handle SELECT vs UPDATE logic
            if ("select".equalsIgnoreCase(config.optString("queryType"))) {
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        String returnKey = step.optString("returnValueKey");
                        Object result = rs.getObject(returnKey);
                        
                        // Store result in local context (for future steps, like Upload path resolution)
                        String contextKey = step.optString("returnValueObjKey");
                        if (!contextKey.isEmpty()) {
                            context.put(contextKey, result);
                        }
                        
                        // Set as Process Variable if configured (so rest of workflow sees it)
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

    /**
     * Handles File Uploads with dynamic path resolution and polymorphic input types.
     */
    private void executeUploadStep(DelegateExecution execution, JSONObject step, Map<String, Object> context, String tenantId) throws Exception {
        Object docsObj = context.get("docsObj");
        
        // 1. Determine Storage Path Pattern
        // First check JSON config; if missing, fall back to application.properties
        String pathPattern = step.optString("pathPattern");
        Properties props = ConfigurationService.getTenantProperties(tenantId);
        
        if (pathPattern == null || pathPattern.trim().isEmpty()) {
            // Fallback default: {tenantId}/{workflowKey}/{ticketId}/{stageName}/
            pathPattern = props.getProperty("storage.pathPattern", "{tenantId}/{workflowKey}/{ticketId}/{stageName}/");
        }

        // 2. Resolve Path Variables (e.g. replace "{TicketID}" with actual ID "2026000481")
        String resolvedPath = pathPattern;
        for (String key : context.keySet()) {
            Object val = context.get(key);
            resolvedPath = resolvedPath.replace("{" + key + "}", String.valueOf(val != null ? val : ""));
        }

        log.info("Processing upload to path: {}", resolvedPath);
        
        StorageProvider storage = new MinIOStorageProvider(props);
        Map<String, String> documentPaths = new HashMap<>();

        try {
            // 3. POLYMORPHIC INPUT HANDLING
            // Handle 'docs' input whether it is a single Map (Legacy) or a List (New API format)
            if (docsObj instanceof Map) {
                processDocsMap((Map<String, Object>) docsObj, resolvedPath, storage, documentPaths);
            } 
            else if (docsObj instanceof List) {
                processDocsList((List<Map<String, Object>>) docsObj, resolvedPath, storage, documentPaths);
            } 
            else {
                log.warn("docsObj is neither Map nor List. Type: {}", (docsObj != null ? docsObj.getClass().getName() : "null"));
            }
            
            // 4. Set Output Variables for Document Classifier / Agents
            execution.setVariable("documentPaths", documentPaths);
            execution.setVariable("attachmentVars", new ArrayList<>(documentPaths.keySet()));
            
            // Initialize the tracking map for the DocTypeSplitter
            Map<String, Map<String, Object>> fileProcessMap = DocumentProcessingService.initializeFileProcessMap(documentPaths.keySet());
            execution.setVariable("fileProcessMap", fileProcessMap);
            
            log.info("Uploaded {} documents.", documentPaths.size());

        } finally {
            if (storage instanceof MinIOStorageProvider) {
                ((MinIOStorageProvider) storage).close();
            }
        }
    }

    // Helper: Uploads files if input is a Map { "filename": "base64", ... }
    private void processDocsMap(Map<String, Object> docsMap, String basePath, StorageProvider storage, Map<String, String> documentPaths) {
        for (Map.Entry<String, Object> entry : docsMap.entrySet()) {
            uploadSingleDoc(entry.getKey(), (String) entry.getValue(), basePath, storage, documentPaths);
        }
    }

    // Helper: Uploads files if input is a List [ { "filename": "x", "content": "base64" }, ... ]
    private void processDocsList(List<Map<String, Object>> docsList, String basePath, StorageProvider storage, Map<String, String> documentPaths) {
        for (Map<String, Object> doc : docsList) {
            String name = (String) doc.get("filename");
            String content = (String) doc.get("content");
            uploadSingleDoc(name, content, basePath, storage, documentPaths);
        }
    }

    // Core upload logic used by both helpers
    private void uploadSingleDoc(String name, String base64Content, String basePath, StorageProvider storage, Map<String, String> documentPaths) {
        try {
            if (name != null && base64Content != null) {
                byte[] content = Base64.getDecoder().decode(base64Content);
                String fullPath = basePath + name;
                storage.uploadDocument(fullPath, content, "application/octet-stream");
                documentPaths.put(name, fullPath);
            }
        } catch (Exception e) {
            log.error("Failed to upload document: {}", name, e);
        }
    }

    /**
     * Sets process variables defined in the JSON step (e.g., expiry duration).
     */
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