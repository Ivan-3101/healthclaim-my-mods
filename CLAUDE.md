# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Deploy

This is a Java 17 Maven project that produces a WAR deployed to a CIBSeven (Camunda fork) process engine running on an app server.

```bash
# Build WAR
mvn clean package

# Run tests
mvn test

# Deploy to WildFly
mvn clean wildfly:deploy

# Deploy to Tomcat
mvn clean tomcat7:deploy
```

No standalone Spring Boot execution — the WAR is deployed into an existing CIBSeven application server (WildFly or Tomcat).

## Architecture Overview

### Process Engine
The application uses **CIBSeven 2.0.0** (a fork of Camunda 7.15.0). All imports use `org.cibseven.bpm.*` packages instead of `org.camunda.bpm.*`. The process application entry point is `CamundaBpmProcessApplication` extending `JakartaServletProcessApplication`.

### BPMN Process
A single BPMN process (`process.bpmn`, ID: `HealthClaim`) defines the entire claim workflow:
1. Generate ticket ID and load workflow config from DB
2. Verify master data (policy/claim validation)
3. FWA (Fraud/Warning Analysis) risk scoring
4. Manual review of FWA results
5. Claim cost computation
6. Risk-based routing: RiskScore >= 20 goes to manual review, < 20 auto-approves
7. Approve/Reject/Request-Info branching with email notifications
8. Timeout handling (1-hour reminders x3, then 1-day auto-rejection)

### Generic Delegate Pattern
The codebase is being refactored from domain-specific delegates to **generic, configuration-driven delegates** in `generic/delegates/`:

- **GenericTicketIdGenerator** — Loads workflow config JSON from DB, executes setup SQL queries, uploads docs to MinIO, sets process variables for multi-instance agent loops
- **GenericApiCallDelegate** — HTTP calls (GET/POST/PUT/DELETE) with placeholder resolution (`${appproperties.*}`, `${processVariable.*}`)
- **GenericAgentDelegate** — Orchestrates external AI/ML agents; downloads files from MinIO, encodes to Base64, maps JSON responses back to process variables via JSONPath
- **GenericEmailTask** — Template-based emails via API or SMTP; supports auto-response message correlation
- **GenericScoringAPIDelegate** — Wrapper for risk scoring, used by FWADecisioning and ClaimCostComputation

These delegates are configured through **Camunda Element Templates** (JSON files in `Generic Templates/`) that inject field bindings into BPMN service tasks.

### Multi-Tenant Architecture
- Tenant ID comes from `execution.getTenantId()`
- Tenant-specific config loaded from `application.properties_{tenantId}`
- `ConfigurationService` caches tenant configs
- MinIO paths are tenant-isolated: `{tenantId}/HealthClaim/{ticketId}/{stageName}/`

### Storage Layer
MinIO is used for document and agent result persistence. `StorageProvider` interface in `generic/storage/` with `MinIOStorageProvider` implementation. Agent results are stored as JSON audit trails at `{tenantId}/HealthClaim/{ticketId}/{stageName}/{agentId}_result.json`.

## Key Source Paths

- `src/main/java/com/DronaPay/frm/HealthClaim/` — All Java source
- `src/main/java/.../generic/delegates/` — Reusable BPMN service task delegates
- `src/main/java/.../generic/services/` — ConfigurationService, AgentResultStorageService
- `src/main/java/.../generic/storage/` — StorageProvider interface and MinIO implementation
- `src/main/resources/process.bpmn` — The BPMN process definition
- `src/main/resources/application.properties` — Default config (has tenant-specific variants)
- `Generic Templates/` — Camunda Modeler element templates (JSON)
- `src/main/webapp/forms/` — Embedded HTML forms for user tasks

## Key Process Variables

- `TicketID` — Unique ticket identifier
- `RiskScore` — Fraud risk score (integer, threshold at 20)
- `Action`, `Action1`, `Action2` — User decisions at review stages
- `sender_email`, `holder_name`, `policy_id` — Used for email notifications
- `workflowConfig` — JSON blob loaded from DB defining stages and agents

## Dependencies to Know

- **CIBSeven 2.0.0** — Process engine (provided scope, runs in app server)
- **MinIO 8.5.7** — Object storage for documents and agent results
- **Apache HttpComponents 4.5.14** — HTTP client for API/agent calls
- **JSONPath 2.9.0** — Extracting values from agent JSON responses
- **Lombok** — Used for boilerplate reduction
- **org.json + Jackson** — JSON processing
- **JUnit 4** — Test framework (tests currently minimal)
