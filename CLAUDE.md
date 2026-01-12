# CLAUDE.md

This file provides comprehensive guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**HealthClaim** is a sophisticated health insurance claim processing workflow application built on **Camunda BPM Platform v7.23.0**. It automates the entire claim approval lifecycle with AI agent integration for document verification, fraud detection, and policy comparison.

**Tech Stack**:
- Java 17 (Maven compiler source/target: 1.17)
- Maven 3.x
- Camunda BPM 7.23.0 (Process Engine + Spin Data Format)
- Deployment: WAR package to Wildfly/JBoss/Tomcat
- Logging: Logback 1.5.12 with SLF4J
- HTTP Client: Apache HttpClient 4.5.14
- JSON Processing: org.json 20231013
- Utilities: Lombok 1.18.30, Commons IO 2.15.1

**Maven Coordinates**: `com.DronaPay.frm:healthclaim:0.0.1-SNAPSHOT`

## Build & Test Commands

```bash
# Clean and build WAR package
mvn clean package

# Run unit tests
mvn test

# Deploy (choose one based on application server)
mvn clean wildfly:deploy          # Wildfly
mvn clean jboss-as:deploy         # JBoss AS7
mvn clean package antrun:run      # Tomcat (requires build.properties configuration)

# Tomcat specific (requires tomcat-users.xml configuration)
mvn clean tomcat7:deploy          # Initial deployment
mvn clean tomcat7:redeploy        # Redeploy
mvn tomcat7:undeploy              # Undeploy
```

**Tomcat Configuration**: Add to `tomcat-users.xml`:
```xml
<role rolename="manager-script"/>
<user username="admin" password="admin" roles="manager-script"/>
```

## Architecture

### BPMN Workflow Engine

The core is a Camunda BPMN workflow (`src/main/resources/process.bpmn`) with:
- **Process Definition Key**: `HealthClaim` (defined in `ProcessConstants.java`)
- **Process Name**: "Health Claim"
- **Executable**: true
- **Modeler**: Camunda Modeler 5.39.0

#### Workflow Stages

1. **Initialization**
   - Start Event: "Create Ticket"
   - Service Task: `GenerateIDAndWorkflowName` - Assigns unique ticket ID and workflow name
   - Service Task: `VerifyMasterData` (async before/after) - Validates claim against master data via Spring API

2. **Document Processing Pipeline** (Multi-Instance Subprocess)
   - **Loop Characteristics**: Sequential multi-instance over `${attachmentVars}` collection
   - **Element Variable**: `attachment` (current document being processed)
   - **Processing Chain**:
     1. `IdentifyForgedDocuments` - AI-based forgery detection using base64-encoded images
     2. `OCROnDoc` - Optical Character Recognition on documents (has error boundary event)
     3. `OCRToFHIR` - Converts OCR output to FHIR JSON format
     4. `PolicyComparator` - Compares extracted data against policy using AI agent
   - **Data Structure**: Results stored in `fileProcessMap` (Map<String, Map<String, Object>>)
   - **Error Handling**: Boundary error event on OCR task catches `Error_13azljt`

3. **Post-Processing**
   - Service Task: `ProcessAgentResponse` - Aggregates multi-instance outputs

4. **Risk Assessment**
   - Service Task: `FWADecisioning` (async before/after) - Fraud/Waste/Abuse scoring via Spring API
   - Service Task: `ClaimCostComputation` (async before/after) - Calculates final claim amount

5. **Decision Gateway**
   - **Condition**: `${RiskScore>=20}`
   - **High Risk Path** (Score ≥ 20): Manual review via User Task "User Verification"
   - **Low Risk Path** (Score < 20): Automatic approval

6. **User Task: "User Verification"** (Manual Review)
   - Form fields include: patient name, gender, doctor details, disease, procedure, medication, dates, insurance company, hospital, claim amount
   - Timeout boundary events trigger email reminders (1 hour, 1 day)

7. **Communication & Finalization**
   - Email notifications for approval, rejection, missing information
   - Service Tasks: `IntimateClaimApproval`, `SendRejectionMail`, `RequestInfoMail`, `SendReminderToPolicyHolder`, `MissingInfoRejectionMail`
   - `ApproveClaim` - Final approval step
   - End Event: "Close Ticket"

### Multi-Tenant Architecture

**Configuration**:
- Tenant ID specified in `src/main/resources/META-INF/processes.xml`
- Current tenant: `tenantId="1"`
- Process engine: `default`
- Properties: `isScanForProcessDefinitions=true`, `isDeleteUponUndeploy=false`

**Tenant Properties Management**:
- `TenantPropertiesUtil.java` loads tenant-specific properties from `application.properties_{tenantid}`
- Current file: `application.properties_1`
- All API calls, email services, and configurations must be tenant-aware
- Tenant ID accessible via `execution.getTenantId()`

**Multi-Tenant Design Principles**:
- Never hardcode tenant-specific values
- Always use `TenantPropertiesUtil.getTenantProps(tenantId)` for configuration
- Pass tenant ID to `APIServices` constructor
- Database isolation handled by Camunda tenant mechanism

### Java Delegate Pattern

All service tasks implement `org.camunda.bpm.engine.delegate.JavaDelegate` with a single method:
```java
void execute(DelegateExecution execution) throws Exception
```

**Key Delegates** (all in `com.DronaPay.frm.HealthClaim` package):

#### Document Processing Chain
1. **IdentifyForgedDocuments.java**
   - Retrieves file attachment via `execution.getVariableTyped()`
   - Converts to base64 string
   - Calls AI agent with `agentid="forgeryagent"`
   - Sets `fileProcessMap` with forgery detection results
   - Output keys: `forgeDocAPIResponse`, `forgeDocAPIStatusCode`, `docForgedAPICall`, `isDocForged`

2. **OCROnDoc.java**
   - Performs OCR on document files
   - Updates `fileProcessMap` with OCR results

3. **OCRToFHIR.java**
   - Converts OCR text to FHIR-compliant JSON
   - Sets `fhir_json` process variable

4. **PolicyComparator.java**
   - Reads `fhir_json` from process variables
   - Constructs request: `{"data": {"doc_fhir": <fhir_json>}, "agentid": "policy_comp"}`
   - Calls `callPolicyComparatorAgent()` via APIServices
   - Parses response JSON structure: `response.answer.List 2[]`
   - Extracts items with `Status/Issue` and `Question/Area` fields
   - Sets process variables:
     - `policyMissingInfo`: Comma-separated list of missing information areas
     - `policyPotentialIssues`: Newline-separated list of issues
     - `policyComparatorStatus`: "success" | "failed" | "error"

5. **ProcessAgentResponse.java**
   - Aggregates outputs from multi-instance document processing
   - Currently under active development on `Policy-Comparator-Agent` branch

#### Decision & Computation Engine
1. **FWADecisioning.java**
   - Calculates fraud/waste/abuse risk score
   - Calls Spring API `/score` endpoint
   - Sets `RiskScore` variable (integer)

2. **ClaimCostComputation.java**
   - Computes final approved claim amount
   - Calls Spring API `/score` endpoint
   - Updates claim cost variables

#### Communication Delegates
- **IntimateClaimApproval.java**: Sends approval notification email
- **SendRejectionMail.java**: Sends rejection notification
- **RequestInfoMail.java**: Requests missing information from claimant
- **SendReminderToPolicyHolder.java**: Sends timer-based reminders
- **MissingInfoRejectionMail.java**: Sends rejection for missing info timeout

#### Utility Delegates
- **GenerateIDAndWorkflowName.java**: Creates unique identifiers using `com.fasterxml.uuid.java-uuid-generator`
- **VerifyMasterData.java**: Validates policy holder data against master database
- **ApproveClaim.java**: Final approval processing
- **LoggerDelegate.java**: Logging utility for workflow events

### External Integration (APIServices.java)

Centralized HTTP client service initialized with tenant ID. Uses Apache HttpClient for synchronous calls.

**Constructor**: `new APIServices(String tenantId)` - Loads tenant-specific configuration

**Email Integration**:
- **Method**: `sendEmailViaUiserver(JSONObject emailReqBody, String tenantid)`
- **Endpoint**: `{uiserver.url}/api/v1/testing/email-service/send-email/tenant-id/{tenantid}`
- **Authentication**: API Key (`X-API-Key`) or Bearer token (`Authorization`)
- **Provider Support**: SMTP (Office 365) or API-based
- **SMTP Config**: smtp.office365.com:587, TLS enabled, authenticated

**Spring API Integration**:
- **verifyMasterData(String policyId)**
  - Endpoint: `GET {springapi.url}/accounts/{policyId}`
  - Header: `X-API-Key`

- **FWADecisioning(String body)**
  - Endpoint: `POST {springapi.url}/score`
  - Content-Type: application/json

- **claimCost(String body)**
  - Endpoint: `POST {springapi.url}/score`
  - Content-Type: application/json

**AI Agent Integration** (Basic Auth):
- **callAgent(String body)**
  - Endpoint: `POST {ai.agent.url}/agent`
  - Authentication: Basic (username: `batchuser`, password: `100!batch`)
  - Used for: Forgery detection

- **callPolicyComparatorAgent(String body)**
  - Endpoint: `POST {ai.agent.url}/agent`
  - Authentication: Basic (same credentials)
  - Request format: `{"data": {...}, "agentid": "policy_comp"}`
  - Response format: `{"answer": {"List 2": [{"Status/Issue": "...", "Question/Area": "..."}]}}`

**Dummy Stub**:
- **dummyStub(String businessKey, String templateName, String tenantId)**
  - Endpoint: `GET {uiserver.url}/api/v1/dummy/ivr/risk-notification-ivr-call/{businessKey}/{templateName}`
  - Async call using `HttpClient.sendAsync()`

### Configuration Management

**Primary Config**: `src/main/resources/application.properties_{tenantid}`

**Tenant 1 Configuration** (`application.properties_1`):
```properties
# Email Configuration
email.enable=true
email.provider=api
mail.smtp.host=smtp.office365.com
mail.smtp.port=587
mail.username=riskdocs.pinelabs.dit@outlook.com
mail.sender=Claim Team
mail.smtp.auth=true
mail.smtp.starttls.enable=true

# API Endpoints
uiserver.url=https://dev.prl.dronapay.net
uiserver.apikey=f877a5b1-b6d6-4733-9d2e-0509538e536e
uiserver.auth.type=apikey

springapi.url=https://dev.prl.dronapay.net/springapitest
springapi.api.key=f3c62f8d-b61d-4cc0-ae87-9b4306e63e1d

ai.agent.url=http://10.200.84.54:8000
ai.agent.username=batchuser
ai.agent.password=100!batch

# Feature Flags
dummy.stub=false
```

**Token Management**: `TokenUtil.java` manages authentication tokens (file-based: `/mnt/d/docker_data/token.txt`)

### Data Exchange & Variable Management

**Process Variable Patterns**:
- **Naming Convention**: camelCase for variables (e.g., `TicketID`, `RiskScore`)
- **Access**: `execution.getVariable("variableName")` - returns Object, requires casting
- **Set**: `execution.setVariable("variableName", value)`
- **Typed Access**: `execution.getVariableTyped("variableName")` - for FileValue, etc.

**Key Process Variables**:
- `TicketID` (String): Unique claim identifier
- `attachmentVars` (Collection): Document collection for multi-instance processing
- `attachment` (String): Current document variable name in multi-instance loop
- `fileProcessMap` (Map<String, Map<String, Object>>): Nested map storing per-file processing results
- `fhir_json` (String): FHIR-compliant JSON extracted from documents
- `policyMissingInfo` (String): Comma-separated missing information areas
- `policyPotentialIssues` (String): Newline-separated policy issues
- `policyComparatorStatus` (String): "success" | "failed" | "error"
- `RiskScore` (Integer): FWA risk score (0-100 scale)
- `claimAmount` (Long): Final approved amount

**Multi-Instance Collection Pattern**:
```xml
<multiInstanceLoopCharacteristics
  isSequential="true"
  camunda:collection="${attachmentVars}"
  camunda:elementVariable="attachment" />
```
Each iteration processes one attachment, updating `fileProcessMap` with results.

**FHIR JSON Standard**:
- Healthcare data interchange format (HL7 FHIR)
- Contains structured patient, encounter, condition, medication, procedure data
- Generated by `OCRToFHIR.java`, consumed by `PolicyComparator.java`

## Working with BPMN

### Editing Workflows

**Tools**:
- **Camunda Modeler** (desktop application) - required for visual BPMN editing
- File: `src/main/resources/process.bpmn`
- XML format with BPMN 2.0 specification

**Service Task Configuration**:
```xml
<bpmn:serviceTask
  id="Activity_xyz"
  name="Task Name"
  camunda:class="com.DronaPay.frm.HealthClaim.YourDelegate"
  camunda:asyncBefore="true"
  camunda:asyncAfter="true">
```

**Async Execution**:
- `camunda:asyncBefore="true"`: Execute in separate transaction, allows retry on failure
- `camunda:asyncAfter="true"`: Continue workflow asynchronously after completion
- Use for: External API calls, long-running tasks, fault-tolerant operations

**Conditional Flows**:
```xml
<bpmn:sequenceFlow sourceRef="Gateway_xyz" targetRef="Activity_abc">
  <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">
    ${RiskScore>=20}
  </bpmn:conditionExpression>
</bpmn:sequenceFlow>
```

### Adding New Service Tasks

**Step-by-Step**:
1. Create Java class in `src/main/java/com/DronaPay/frm/HealthClaim/`
2. Implement `org.camunda.bpm.engine.delegate.JavaDelegate`
3. Add `@Slf4j` annotation for logging
4. Implement `execute(DelegateExecution execution)` method
5. Access tenant ID: `execution.getTenantId()`
6. Handle exceptions appropriately (logged and propagated)
7. Update BPMN file with `camunda:class` reference

**Example Skeleton**:
```java
package com.DronaPay.frm.HealthClaim;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MyNewTask implements JavaDelegate {
    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("MyNewTask called for ticket " + execution.getVariable("TicketID"));

        // 1. Get tenant-specific API service
        APIServices api = new APIServices(execution.getTenantId());

        // 2. Retrieve input variables
        String inputData = (String) execution.getVariable("inputVar");

        // 3. Process business logic
        // ...

        // 4. Set output variables
        execution.setVariable("outputVar", result);

        log.info("MyNewTask completed successfully");
    }
}
```

### Error Handling in BPMN

**Boundary Error Events**:
```xml
<bpmn:boundaryEvent id="Event_error" attachedToRef="Activity_risky">
  <bpmn:errorEventDefinition errorRef="Error_code" />
</bpmn:boundaryEvent>
```

**Throwing Errors in Delegates**:
```java
throw new BpmnError("ERROR_CODE", "Error message for process context");
```

### User Task Forms

Define form fields in BPMN using Camunda's form data:
```xml
<camunda:formData>
  <camunda:formField id="fieldId" label="Field Label" type="string|long|boolean" />
</camunda:formData>
```

Supported types: string, long, boolean, date, enum

### Testing Workflows

**Unit Testing Framework**:
- **Library**: Camunda BPM Assert 15.0.0 + AssertJ 3.22.0
- **Test Config**: `src/test/resources/camunda.cfg.xml`
- **Example Test**: `src/test/java/com/DronaPay/frm/Onboarding/ProcessUnitTest.java`

**Test Structure**:
```java
import org.camunda.bpm.engine.test.Deployment;
import org.camunda.bpm.engine.test.ProcessEngineRule;
import static org.camunda.bpm.engine.test.assertions.bpmn.BpmnAwareTests.*;

@Rule
public ProcessEngineRule processEngineRule = new ProcessEngineRule();

@Test
@Deployment(resources = "process.bpmn")
public void testHappyPath() {
    ProcessInstance pi = runtimeService()
        .startProcessInstanceByKey("HealthClaim");
    assertThat(pi).isStarted()
                  .task()
                  .hasName("User Verification");
}
```

**Coverage Reports**: Uses `camunda-bpm-process-test-coverage:0.4.0` for test coverage metrics

## Logging

**Configuration**: `healthclaim-logback.xml` (project root)

**Log Destinations**:
- Production: `/camunda/logs/` directory
- Rolling policy: Daily rotation
- Max file size: 10MB
- Archive pattern: `healthclaim-%d{yyyy-MM-dd}.log`

**Logger Usage** (Lombok):
```java
@Slf4j
public class MyClass {
    public void method() {
        log.info("Info message with param: {}", value);
        log.error("Error occurred", exception);
        log.debug("Debug details: {}", debugInfo);
    }
}
```

**Log Levels**:
- ERROR: Critical failures requiring immediate attention
- WARN: Non-critical issues (degraded service)
- INFO: Key business events (claim processed, API calls, status changes)
- DEBUG: Detailed diagnostic information

**Best Practices**:
- Always log ticket ID for traceability
- Log all external API calls (request + response + status code)
- Include tenant ID in multi-tenant operations
- Avoid logging sensitive data (credentials, PII)
- Use structured logging: `log.info("Event {} for ticket {}", event, ticketId)`

## Dependencies & Libraries

**Key Dependencies** (from pom.xml):

**Camunda BPM**:
- `camunda-engine:7.23.0` (provided scope - shared library in app server)
- `camunda-spin-dataformat-all` (provided)
- `camunda-engine-plugin-spin:7.23.0` (provided)
- `camunda-template-engines-freemarker` (provided)

**HTTP & JSON**:
- `httpclient:4.5.14` (Apache HttpComponents)
- `json:20231013` (org.json)

**Utilities**:
- `lombok:1.18.30` (compile scope)
- `commons-io:2.15.1`
- `java-uuid-generator:4.3.0`

**Logging**:
- `logback-classic:1.5.12`
- `slf4j-api:2.0.16`

**Testing**:
- `junit:4.13.2` (test scope)
- `camunda-bpm-assert:15.0.0`
- `assertj-core:3.22.0` (test scope)
- `camunda-bpm-process-test-coverage:0.4.0` (test scope)

**Servlet**:
- `javax.servlet-api:4.0.1` (provided)
- `jakarta.servlet-api:6.1.0` (provided)

**Object Storage**:
- `minio:8.5.7` - MinIO Java SDK for object storage operations

## Object Storage Architecture

### Overview

The HealthClaim workflow now includes a robust object storage abstraction layer that enables multi-cloud document storage capabilities. The implementation uses MinIO as the primary storage provider, with architecture designed to support S3, Azure Blob Storage, and Google Cloud Storage in the future.

**Why Object Storage?**
- **Scalability**: Store unlimited documents without database size constraints
- **Performance**: Offload large binary data from Camunda process variables
- **Cost**: Cheaper than database storage for large files
- **Multi-Cloud**: Provider-agnostic design allows switching between MinIO, S3, Azure, etc.
- **Separability**: Documents can be managed independently from workflow state

### Architecture Components

#### 1. Storage Provider Interface (`StorageProvider.java`)

Defines the contract that all storage implementations must follow:

```java
public interface StorageProvider {
    String uploadDocument(String path, byte[] content, String contentType) throws Exception;
    InputStream downloadDocument(String path) throws Exception;
    boolean deleteDocument(String path) throws Exception;
    boolean documentExists(String path) throws Exception;
    String getProviderName();
}
```

**Location**: `com.DronaPay.frm.HealthClaim.generic.storage.StorageProvider`

**Methods**:
- `uploadDocument()`: Uploads document to storage, returns URL/path
- `downloadDocument()`: Returns InputStream to document content (caller must close)
- `deleteDocument()`: Removes document from storage
- `documentExists()`: Checks if document exists at path
- `getProviderName()`: Returns provider type ("minio", "s3", "azure")

#### 2. MinIO Storage Provider (`MinIOStorageProvider.java`)

Concrete implementation using MinIO Java SDK.

**Location**: `com.DronaPay.frm.HealthClaim.generic.storage.MinIOStorageProvider`

**Initialization**:
```java
// Automatically initialized from tenant properties
MinIOStorageProvider(Properties props) {
    String endpoint = props.getProperty("storage.minio.endpoint");
    String accessKey = props.getProperty("storage.minio.accessKey");
    String secretKey = props.getProperty("storage.minio.secretKey");
    String bucketName = props.getProperty("storage.minio.bucketName");
    
    // Creates MinIO client and ensures bucket exists
}
```

**Key Features**:
- Automatic bucket creation if it doesn't exist
- Structured exception handling with context
- Logging of all operations (upload/download/delete)
- Returns `minio://bucket/path` URL format

**Upload Example**:
```java
StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);
String url = storage.uploadDocument(
    "tenant1/HealthClaim/12345/invoice.pdf",
    pdfBytes,
    "application/pdf"
);
// Returns: "minio://claim-documents/tenant1/HealthClaim/12345/invoice.pdf"
```

#### 3. Object Storage Service (`ObjectStorageService.java`)

Factory and utility service for managing storage providers.

**Location**: `com.DronaPay.frm.HealthClaim.generic.services.ObjectStorageService`

**Key Methods**:

1. **Get Storage Provider** (with caching):
```java
StorageProvider provider = ObjectStorageService.getStorageProvider(tenantId);
```
- Loads tenant-specific configuration from `application.properties_{tenantId}`
- Caches provider instances per tenant (singleton pattern)
- Supports switching providers via `storage.provider` property

2. **Build Storage Path**:
```java
String path = ObjectStorageService.buildStoragePath(
    "{tenantId}/{workflowKey}/{ticketId}/",  // pattern
    "1",                                      // tenantId
    "HealthClaim",                            // workflowKey
    "12345",                                  // ticketId
    "invoice.pdf"                             // filename
);
// Returns: "1/HealthClaim/12345/invoice.pdf"
```

**Provider Cache**:
- Static `Map<String, StorageProvider>` caches providers per tenant
- Avoids recreating MinIO clients for every operation
- Call `clearCache()` for hot-reload scenarios

#### 4. Document Processing Service (`DocumentProcessingService.java`)

High-level service for document operations in workflows.

**Location**: `com.DronaPay.frm.HealthClaim.generic.services.DocumentProcessingService`

**Primary Methods**:

1. **Process and Upload Documents**:
```java
Map<String, String> documentPaths = DocumentProcessingService.processAndUploadDocuments(
    execution.getVariable("docs"),  // docs variable (JSON array)
    tenantId,                        // "1"
    workflowKey,                     // "HealthClaim"
    ticketId                         // "12345"
);
// Returns Map: {"invoice.pdf" -> "1/HealthClaim/12345/invoice.pdf", ...}
```

**Process**:
- Accepts `docs` variable (List or JSON string)
- Each document has: `filename`, `mimetype`, `encoding`, `content` (base64)
- Decodes base64 content to byte array
- Builds storage path using configured pattern
- Uploads to storage provider
- Returns map of filename -> storage path

2. **Process Documents (Legacy - In-Memory)**:
```java
Map<String, FileValue> fileMap = DocumentProcessingService.processDocuments(docsObject);
```
- Creates Camunda `FileValue` objects without uploading
- Used for backward compatibility
- Not recommended for large documents

3. **Download Document as FileValue**:
```java
FileValue file = DocumentProcessingService.downloadDocumentAsFileValue(
    filename, storagePath, tenantId
);
```
- Downloads from storage and converts to Camunda FileValue
- Useful when external systems need FileValue format

4. **Get Document as Base64**:
```java
String base64 = DocumentProcessingService.getDocumentAsBase64(
    filename, documentPaths, tenantId
);
```
- Downloads from storage and encodes as base64
- Used for AI agent API calls (OCR, forgery detection)

### Configuration

#### Required Properties (per tenant)

Add to `src/main/resources/application.properties_{tenantId}`:

```properties
# Object Storage Configuration
storage.provider=minio
storage.minio.endpoint=http://host.docker.internal:9000
storage.minio.accessKey=YOUR_ACCESS_KEY
storage.minio.secretKey=YOUR_SECRET_KEY
storage.minio.bucketName=claim-documents
storage.minio.region=us-east-1

# Path pattern: {tenantId}/{workflowKey}/{ticketId}/
storage.pathPattern={tenantId}/{workflowKey}/{ticketId}/
```

**Property Descriptions**:
- `storage.provider`: Storage type ("minio", "s3", "azure") - only "minio" implemented
- `storage.minio.endpoint`: MinIO server URL (use `host.docker.internal` for Docker)
- `storage.minio.accessKey`: MinIO access key (default: minioadmin - **CHANGE IN PRODUCTION**)
- `storage.minio.secretKey`: MinIO secret key (default: minioadmin123 - **CHANGE IN PRODUCTION**)
- `storage.minio.bucketName`: Bucket name (created automatically if missing)
- `storage.minio.region`: AWS region (optional, for S3-compatible setup)
- `storage.pathPattern`: Template for document paths with variables: `{tenantId}`, `{workflowKey}`, `{ticketId}`

**Security Notes**:
- Never commit credentials to git
- Use environment variables or secrets manager in production
- Change default credentials (minioadmin/minioadmin123)
- Use HTTPS endpoints in production

#### MinIO Server Setup

**Docker Compose**:
```yaml
version: '3'
services:
  minio:
    image: minio/minio:latest
    ports:
      - "9000:9000"  # API
      - "9001:9001"  # Console
    environment:
      MINIO_ROOT_USER: minioadmin
      MINIO_ROOT_PASSWORD: minioadmin123
    command: server /data --console-address ":9001"
    volumes:
      - minio-data:/data
volumes:
  minio-data:
```

**Access MinIO Console**: http://localhost:9001

### Integration with Workflow

#### How Documents Flow Through Workflow

1. **Workflow Start**: User submits claim with documents via `docs` variable
   ```json
   {
     "docs": [
       {
         "filename": "invoice.pdf",
         "mimetype": "application/pdf",
         "encoding": "base64",
         "content": "JVBERi0xLjQK..."
       }
     ]
   }
   ```

2. **GenericIDGeneratorDelegate**: First workflow task
   - Generates ticket ID (e.g., 12345)
   - Calls `DocumentProcessingService.processAndUploadDocuments()`
   - Uploads all documents to MinIO
   - Sets process variables:
     - `documentPaths`: Map of filename -> storage path
     - `attachmentVars`: List of filenames for multi-instance loop
     - `fileProcessMap`: Initialized map for processing results

3. **Multi-Instance Document Processing**: Loops over each document
   - **OCROnDoc**: Downloads document from MinIO, sends to OCR agent
   - **IdentifyForgedDocuments**: Downloads document, sends to forgery detection
   - **OCRToFHIR**: Converts OCR text to FHIR
   - **PolicyComparator**: Compares FHIR data against policy

4. **Downstream Tasks**: Use `documentPaths` variable to access documents

#### Delegate Integration Pattern

**Standard Pattern for Accessing Documents**:

```java
@Slf4j
public class MyDocumentProcessor implements JavaDelegate {
    @Override
    public void execute(DelegateExecution execution) throws Exception {
        // 1. Get current document filename (in multi-instance loop)
        String filename = (String) execution.getVariable("attachment");
        
        // 2. Get document paths map
        Map<String, String> documentPaths = 
            (Map<String, String>) execution.getVariable("documentPaths");
        
        // 3. Get storage path for this document
        String storagePath = documentPaths.get(filename);
        if (storagePath == null) {
            throw new RuntimeException("Storage path not found for: " + filename);
        }
        
        // 4. Download from storage
        String tenantId = execution.getTenantId();
        StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);
        
        try (InputStream fileContent = storage.downloadDocument(storagePath)) {
            // 5. Process document
            byte[] bytes = fileContent.readAllBytes();
            // ... your processing logic ...
        } // InputStream auto-closed
    }
}
```

**Critical**: Always use try-with-resources for `InputStream` to prevent resource leaks.

#### Examples from Existing Delegates

**OCROnDoc.java**:
```java
// Retrieve from MinIO instead of FileValue
String filename = (String) execution.getVariable("attachment");
Map<String, String> documentPaths = 
    (Map<String, String>) execution.getVariable("documentPaths");
String storagePath = documentPaths.get(filename);

// Download from Storage
StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);
InputStream fileContent = storage.downloadDocument(storagePath);

// Convert to base64 for OCR agent
byte[] bytes = IOUtils.toByteArray(fileContent);
String base64 = Base64.getEncoder().encodeToString(bytes);

// Call OCR agent
JSONObject requestBody = new JSONObject();
requestBody.put("data", new JSONObject().put("base64_img", base64));
requestBody.put("agentid", "openaiVision");
```

**IdentifyForgedDocuments.java**:
```java
// Same pattern: retrieve storage path, download, process
String storagePath = documentPaths.get(filename);
StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);
InputStream fileContent = storage.downloadDocument(storagePath);

// Convert to base64 for forgery detection agent
byte[] bytes = IOUtils.toByteArray(fileContent);
String base64 = Base64.getEncoder().encodeToString(bytes);

// Call forgery agent
requestBody.put("agentid", "forgeryagent");
```

### Migration Considerations

#### Migrating from FileValue to Object Storage

If you have existing delegates using Camunda FileValue:

**Old Pattern**:
```java
FileValue fileValue = execution.getVariableTyped(attachmentVar);
InputStream stream = fileValue.getValue();
```

**New Pattern**:
```java
String filename = (String) execution.getVariable("attachment");
Map<String, String> documentPaths = 
    (Map<String, String>) execution.getVariable("documentPaths");
StorageProvider storage = ObjectStorageService.getStorageProvider(tenantId);
InputStream stream = storage.downloadDocument(documentPaths.get(filename));
```

**Backward Compatibility Strategy**:
- `DocumentProcessingService.processDocuments()` still creates FileValue objects
- Use for in-flight processes or gradual migration
- Eventually migrate all to object storage for scalability

### Generic Package Structure

New generic packages enable reusability across workflows:

**`com.DronaPay.frm.HealthClaim.generic.storage`**:
- `StorageProvider` - Interface for storage providers
- `MinIOStorageProvider` - MinIO implementation
- Future: `S3StorageProvider`, `AzureBlobStorageProvider`, `GCSStorageProvider`

**`com.DronaPay.frm.HealthClaim.generic.services`**:
- `ObjectStorageService` - Storage provider factory and utilities
- `DocumentProcessingService` - High-level document operations
- `ConfigurationService` - Tenant configuration management

**`com.DronaPay.frm.HealthClaim.generic.delegates`**:
- `GenericIDGeneratorDelegate` - Generic ticket ID generation and document upload
- `GenericMasterDataVerificationDelegate` - Reusable master data validation
- Future: More reusable delegates for common workflow patterns

**Design Philosophy**:
- **Workflow-Agnostic**: Generic components work for HealthClaim, MotorClaim, etc.
- **Tenant-Aware**: All services accept tenantId for multi-tenant configuration
- **Provider Pattern**: Easy to swap MinIO for S3/Azure without changing workflow code

### Troubleshooting

#### Common Issues

**Issue**: `Exception: Bucket does not exist`
- **Cause**: MinIO server doesn't have the bucket
- **Solution**: MinIOStorageProvider auto-creates buckets, check MinIO server is running and accessible

**Issue**: `Connection refused to MinIO endpoint`
- **Cause**: MinIO server not running or incorrect endpoint
- **Solution**: 
  - Verify MinIO is running: `docker ps` or check service status
  - Check endpoint URL in properties (use `host.docker.internal` for Docker)
  - Test connectivity: `curl http://localhost:9000/minio/health/live`

**Issue**: `Access Denied` when uploading
- **Cause**: Invalid credentials
- **Solution**: Verify accessKey/secretKey match MinIO server credentials

**Issue**: Documents not appearing in MinIO Console
- **Cause**: Wrong bucket name or path
- **Solution**: 
  - Check `storage.minio.bucketName` matches expected bucket
  - Verify path pattern generates correct paths
  - Check MinIO Console: http://localhost:9001

**Issue**: `InputStream` not closed warnings
- **Cause**: Not using try-with-resources
- **Solution**: Always wrap storage downloads in try-with-resources:
  ```java
  try (InputStream stream = storage.downloadDocument(path)) {
      // process
  }
  ```

#### Debugging Storage Operations

**Enable Debug Logging**:
Add to `healthclaim-logback.xml`:
```xml
<logger name="com.DronaPay.frm.HealthClaim.generic.storage" level="DEBUG"/>
<logger name="com.DronaPay.frm.HealthClaim.generic.services" level="DEBUG"/>
```

**Log Output**:
```
INFO  MinIOStorageProvider - Uploading document to MinIO: 1/HealthClaim/12345/invoice.pdf
INFO  MinIOStorageProvider - Document uploaded successfully: 1/HealthClaim/12345/invoice.pdf (45632 bytes)
DEBUG ObjectStorageService - Initializing storage provider 'minio' for tenant 1
```

**MinIO Client Logs**:
MinIO Java SDK logs to SLF4J, visible in application logs.

### Best Practices

1. **Always Close InputStreams**:
   ```java
   try (InputStream stream = storage.downloadDocument(path)) {
       // process stream
   } // auto-closed
   ```

2. **Validate documentPaths Exists**:
   ```java
   if (documentPaths == null || !documentPaths.containsKey(filename)) {
       throw new RuntimeException("Storage path not found for: " + filename);
   }
   ```

3. **Use Configured Path Patterns**:
   - Don't hardcode paths like `"documents/" + filename`
   - Use `ObjectStorageService.buildStoragePath()` with pattern from properties
   - Ensures consistent structure across tenants

4. **Secure Credentials**:
   - Never commit `application.properties_{tenantId}` with real credentials
   - Use environment variables or secrets manager
   - Rotate credentials regularly

5. **Handle Storage Failures Gracefully**:
   ```java
   try {
       storage.uploadDocument(path, content, type);
   } catch (Exception e) {
       log.error("Failed to upload document: {}", path, e);
       throw new BpmnError("STORAGE_ERROR", "Document upload failed");
   }
   ```

6. **Log Storage Operations**:
   - Log every upload/download with filename, size, tenant ID
   - Helps with audit trail and debugging

7. **Monitor Storage Size**:
   - MinIO buckets can grow large
   - Implement retention policies for old documents
   - Monitor disk usage on MinIO server

## Project Structure

```
healthclaim/
├── src/
│   ├── main/
│   │   ├── java/com/DronaPay/frm/HealthClaim/
│   │   │   ├── APIServices.java                    # HTTP client service
│   │   │   ├── ApproveClaim.java                   # Approval delegate
│   │   │   ├── CamundaBpmProcessApplication.java   # Process application entry
│   │   │   ├── ClaimCostComputation.java           # Cost calculation
│   │   │   ├── FWADecisioning.java                 # Risk scoring
│   │   │   ├── GenerateIDAndWorkflowName.java      # ID generation (DEPRECATED)
│   │   │   ├── IdentifyForgedDocuments.java        # Forgery detection (uses MinIO)
│   │   │   ├── IntimateClaimApproval.java          # Approval email
│   │   │   ├── LoggerDelegate.java                 # Logging utility
│   │   │   ├── MissingInfoRejectionMail.java       # Missing info email
│   │   │   ├── OCROnDoc.java                       # OCR processing (uses MinIO)
│   │   │   ├── OCRToFHIR.java                      # FHIR conversion
│   │   │   ├── PolicyComparator.java               # Policy comparison agent
│   │   │   ├── ProcessAgentResponse.java           # Agent response handler
│   │   │   ├── ProcessConstants.java               # Constants (process key)
│   │   │   ├── RequestInfoMail.java                # Info request email
│   │   │   ├── SendRejectionMail.java              # Rejection email
│   │   │   ├── SendReminderToPolicyHolder.java     # Reminder email
│   │   │   ├── TenantPropertiesUtil.java           # Multi-tenant config
│   │   │   ├── TokenUtil.java                      # Auth token manager
│   │   │   ├── VerifyMasterData.java               # Master data validation
│   │   │   ├── generic/
│   │   │   │   ├── delegates/
│   │   │   │   │   ├── GenericIDGeneratorDelegate.java        # Generic ID gen + doc upload
│   │   │   │   │   └── GenericMasterDataVerificationDelegate.java
│   │   │   │   ├── services/
│   │   │   │   │   ├── ConfigurationService.java             # Tenant config loader
│   │   │   │   │   ├── DocumentProcessingService.java        # Document operations
│   │   │   │   │   └── ObjectStorageService.java             # Storage provider factory
│   │   │   │   └── storage/
│   │   │   │       ├── StorageProvider.java                  # Storage interface
│   │   │   │       └── MinIOStorageProvider.java             # MinIO implementation
│   │   └── resources/
│   │       ├── META-INF/
│   │       │   └── processes.xml                   # Tenant & deployment config
│   │       ├── application.properties              # Default config
│   │       ├── application.properties_1            # Tenant 1 config
│   │       └── process.bpmn                        # BPMN workflow definition
│   └── test/
│       ├── java/com/DronaPay/frm/Onboarding/
│       │   └── ProcessUnitTest.java                # Unit tests
│       └── resources/
│           └── camunda.cfg.xml                     # Test engine config
├── healthclaim-logback.xml                         # Logging configuration
├── pom.xml                                         # Maven build config
├── build.xml                                       # Ant build script (Tomcat)
├── build.properties.example                        # Tomcat deployment example
└── CLAUDE.md                                       # This file
```

## Development Guidelines

### Code Style
- Use Lombok annotations (`@Slf4j`, `@Data`, etc.) to reduce boilerplate
- Follow Java naming conventions: PascalCase for classes, camelCase for variables
- Keep delegate classes focused on single responsibility
- Extract complex logic into utility methods or separate classes

### Error Handling
- Always log errors with context (ticket ID, tenant ID, operation)
- Use try-catch blocks for external API calls
- Set appropriate process variables on error states
- Use BPMN error events for recoverable errors
- Propagate exceptions for unrecoverable failures

### Performance Considerations
- Use async execution (`asyncBefore/After`) for API calls to enable retries
- Minimize process variable size (avoid storing large JSON in variables)
- Use transient variables for temporary data
- Consider sequential vs parallel multi-instance based on resource constraints

### Security Best Practices
- Never commit credentials in `application.properties`
- Use environment variables or secure vaults for sensitive config
- Validate all external API responses
- Sanitize user inputs in user task forms
- Use HTTPS for all external communications
- Implement API key rotation strategy

### Multi-Tenant Best Practices
- Always pass tenant ID to `APIServices` constructor
- Use `TenantPropertiesUtil` for all configuration access
- Never hardcode tenant-specific URLs, credentials, or logic
- Test with multiple tenant configurations
- Ensure database queries filter by tenant ID (Camunda handles this internally)

## API Integration Patterns

### Calling External APIs
```java
// 1. Initialize tenant-aware API service
APIServices api = new APIServices(execution.getTenantId());

// 2. Prepare request body
JSONObject request = new JSONObject();
request.put("key", value);

// 3. Call API
CloseableHttpResponse response = api.callAgent(request.toString());

// 4. Parse response
int statusCode = response.getStatusLine().getStatusCode();
String responseBody = EntityUtils.toString(response.getEntity());

// 5. Handle response
if (statusCode == 200) {
    JSONObject result = new JSONObject(responseBody);
    // Process result
} else {
    log.error("API call failed with status: {}", statusCode);
    // Handle error
}
```

### Handling AI Agent Responses
AI agents typically return structured JSON. Always:
1. Check HTTP status code (200 = success)
2. Parse JSON response safely with try-catch
3. Extract specific fields (avoid storing entire response)
4. Set meaningful process variables for downstream tasks
5. Log full request/response for debugging

### Email Template Usage
Email templates are tenant-specific and fetched from UI Server. Always include:
- Tenant ID in API path
- Template name (e.g., "claim_approval", "claim_rejection")
- Dynamic variables (recipient, claim details, etc.)

## Common Workflows & Patterns

### Adding a New AI Agent
1. Create delegate class implementing `JavaDelegate`
2. Add method to `APIServices.java` for agent endpoint
3. Construct request JSON with `agentid` field
4. Parse response and extract relevant fields
5. Set process variables with extracted data
6. Add service task to BPMN with `camunda:class` reference
7. Configure async execution if calling external service
8. Add unit tests for happy path and error scenarios

### Modifying Email Notifications
1. Update email template in UI Server tenant configuration
2. Modify delegate class to include new variables in `emailReqBody`
3. Test with `dummy.stub=true` for development
4. Verify template rendering in UI Server
5. Enable production emails with `email.enable=true`

### Updating Risk Scoring Logic
1. Modify `FWADecisioning.java` to include new risk factors
2. Update Spring API `/score` endpoint to process new inputs
3. Adjust decision gateway condition in BPMN if threshold changes
4. Update user task form fields if manual review needs new data
5. Test with various risk score scenarios

## Troubleshooting

### Common Issues

**Issue**: Process instance stuck at service task
- Check Camunda Cockpit for error details
- Review logs for exceptions during task execution
- Verify external API connectivity and credentials
- Check if async job executor is running

**Issue**: Email not sending
- Verify `email.enable=true` in tenant properties
- Check SMTP credentials and connectivity
- Review UI Server email service logs
- Test with `dummy.stub=true` to bypass actual sending

**Issue**: API authentication failures
- Verify API keys in `application.properties_{tenantid}`
- Check token file path and permissions (`token.filename`)
- Ensure Basic Auth credentials are correct for AI agents
- Review API service logs for auth errors

**Issue**: Multi-instance subprocess not iterating
- Verify `attachmentVars` collection is properly initialized
- Check that collection is not empty
- Ensure element variable name matches BPMN definition
- Review subprocess completion logic

**Issue**: FHIR JSON parsing errors
- Validate OCR output format
- Check `OCRToFHIR` conversion logic
- Ensure AI agent returns valid JSON
- Handle null/empty FHIR data gracefully

### Debugging Process Instances
1. Access Camunda Cockpit (web UI)
2. Navigate to Running Instances → HealthClaim
3. View process variables and current activity
4. Check Incidents tab for errors
5. Review History for execution timeline
6. Use "Modify Instance" for recovery

### Log Analysis
```bash
# View recent logs
tail -f /camunda/logs/healthclaim.log

# Search for specific ticket
grep "TicketID: ABC123" /camunda/logs/healthclaim*.log

# Filter API errors
grep "ERROR.*API" /camunda/logs/healthclaim.log

# Check multi-instance processing
grep "fileProcessMap" /camunda/logs/healthclaim.log
```

## Current Development State

**Active Branch**: `Policy-Comparator-Agent`

**Recent Changes**:
- Integrated Policy Comparator AI agent (`PolicyComparator.java`)
- Fixed multi-tenant email template resolution
- Updated `ProcessAgentResponse.java` to handle policy comparison outputs
- Added SQL script for templates (tenant ID 1)

**Modified Files**:
- `src/main/java/com/DronaPay/frm/HealthClaim/ProcessAgentResponse.java`

**Git Status**:
- Modified: `ProcessAgentResponse.java`
- Untracked: `CLAUDE.md` (this file)

**Recent Commits**:
1. "Added the SQL Script used to add the templates to tenant id 1"
2. "Fix(email): Multi-tenant template resolution for email services"
3. "feat(workflow): Integrate Policy Comparator agent and fix critical data flow bugs"
4. "agents integrated, errors in the next stages (claim cost computation)"
5. "Changed from tenant0 to tenant1"

**Next Steps** (likely priorities):
- Complete `ProcessAgentResponse.java` implementation
- Fix claim cost computation integration
- Test end-to-end workflow with Policy Comparator
- Validate email notifications with tenant 1 templates
- Merge to `main` branch after testing

## Additional Resources

**Camunda Documentation**:
- [Camunda BPM 7.23 Docs](https://docs.camunda.org/manual/7.23/)
- [BPMN 2.0 Reference](https://docs.camunda.org/manual/7.23/reference/bpmn20/)
- [Java Delegate Guide](https://docs.camunda.org/manual/7.23/user-guide/process-engine/delegation-code/)

**FHIR Resources**:
- [HL7 FHIR R4 Specification](https://www.hl7.org/fhir/)
- [FHIR Resource Types](https://www.hl7.org/fhir/resourcelist.html)

**Development Tools**:
- [Camunda Modeler](https://camunda.com/download/modeler/) - BPMN visual editor
- [Camunda Cockpit](http://localhost:8080/camunda/app/cockpit) - Process monitoring (when deployed)

---

**Maintained by**: DronaPay FRM Team
**Last Updated**: 2025-11-07
**For Questions**: Refer to project documentation or contact development team
