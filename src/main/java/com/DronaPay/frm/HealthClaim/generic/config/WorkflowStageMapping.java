package com.DronaPay.frm.HealthClaim.generic.config;

import java.util.HashMap;
import java.util.Map;

/**
 * Stage number mappings for workflow tasks
 *
 * Maps BPMN task names to their sequential stage numbers
 * Used by StoragePathBuilder to create numbered folder structure
 */
public class WorkflowStageMapping {

    // HealthClaim workflow stage mappings
    private static final Map<String, Integer> HEALTH_CLAIM_STAGES = new HashMap<>();

    static {
        // Stage 1: Initial document upload and ID generation
        HEALTH_CLAIM_STAGES.put("GenerateTicketIDAndWorkflowName", 1);
        HEALTH_CLAIM_STAGES.put("Generate TicketID And WorkflowName", 1);
        HEALTH_CLAIM_STAGES.put("Generate TicketID and Workflow Name", 1);

        // Stage 2: Master data verification
        HEALTH_CLAIM_STAGES.put("VerifyMasterData", 2);
        HEALTH_CLAIM_STAGES.put("Verify Master Data", 2);

        // Stage 3: Forgery detection
        HEALTH_CLAIM_STAGES.put("IdentifyForgedDocuments", 3);
        HEALTH_CLAIM_STAGES.put("Identify Forged Documents", 3);
        HEALTH_CLAIM_STAGES.put("Identify forged documents", 3);

        // Stage 4: Document classification
        HEALTH_CLAIM_STAGES.put("DocumentClassifier", 4);
        HEALTH_CLAIM_STAGES.put("Document Classifier", 4);
        HEALTH_CLAIM_STAGES.put("Doc Classifier", 4);
        HEALTH_CLAIM_STAGES.put("DocClassifier", 4);

        // Stage 5: Document type splitting
        HEALTH_CLAIM_STAGES.put("DocTypeSplitter", 5);
        HEALTH_CLAIM_STAGES.put("Doc Type Splitter", 5);
        HEALTH_CLAIM_STAGES.put("DocTypeSplitter", 5);

        // Stage 6: OCR extraction
        HEALTH_CLAIM_STAGES.put("OCROnDoc", 6);
        HEALTH_CLAIM_STAGES.put("OCR On Doc", 6);
        HEALTH_CLAIM_STAGES.put("OCR on Doc", 6);

        // Stage 7: OCR to FHIR conversion
        HEALTH_CLAIM_STAGES.put("OcrToStatic", 7);
        HEALTH_CLAIM_STAGES.put("OCR To Static", 7);
        HEALTH_CLAIM_STAGES.put("OCR to Static", 7);

        // Stage 8: FHIR consolidation
        HEALTH_CLAIM_STAGES.put("FHIRConsolidator", 8);
        HEALTH_CLAIM_STAGES.put("FHIR Consolidator", 8);

        // Stage 9: Submission validation
        HEALTH_CLAIM_STAGES.put("SubmissionValidator", 9);
        HEALTH_CLAIM_STAGES.put("Submission Validator", 9);

        // Stage 10: FHIR analysis
        HEALTH_CLAIM_STAGES.put("FHIRAnalyser", 10);
        HEALTH_CLAIM_STAGES.put("FHIR Analyser", 10);
        HEALTH_CLAIM_STAGES.put("FHIRAnalyzer", 10);

        // Stage 11: UI field display
        HEALTH_CLAIM_STAGES.put("UIDisplayer", 11);
        HEALTH_CLAIM_STAGES.put("UI Displayer", 11);

        // Stage 12: Load UI fields
        HEALTH_CLAIM_STAGES.put("LoadUIFields", 12);
        HEALTH_CLAIM_STAGES.put("Load UI Fields", 12);

        // Stage 13: User verification task
        HEALTH_CLAIM_STAGES.put("VerifyExtractedInfo", 13);
        HEALTH_CLAIM_STAGES.put("Verify Extracted Info", 13);

        // Stage 14: Policy coherence check
        HEALTH_CLAIM_STAGES.put("PolicyCoherence", 14);
        HEALTH_CLAIM_STAGES.put("Policy Coherence", 14);

        // Stage 15: Medical coherence check
        HEALTH_CLAIM_STAGES.put("MedicalCoherence", 15);
        HEALTH_CLAIM_STAGES.put("Medical Coherence", 15);

        // Stage 16: Load final review fields
        HEALTH_CLAIM_STAGES.put("LoadFinalReviewFields", 16);
        HEALTH_CLAIM_STAGES.put("Load Final Review Fields", 16);

        // Stage 17: Final review task
        HEALTH_CLAIM_STAGES.put("FinalReviewTask", 17);
        HEALTH_CLAIM_STAGES.put("Final Review Task", 17);

        // Stage 18: FWA decisioning
        HEALTH_CLAIM_STAGES.put("FWADecisioning", 18);
        HEALTH_CLAIM_STAGES.put("FWA Decisioning", 18);

        // Stage 19: Claim cost computation
        HEALTH_CLAIM_STAGES.put("ClaimCostComputation", 19);
        HEALTH_CLAIM_STAGES.put("Claim Cost Computation", 19);

        // Stage 20: Approval/Rejection
        HEALTH_CLAIM_STAGES.put("ApproveClaim", 20);
        HEALTH_CLAIM_STAGES.put("Approve Claim", 20);
    }

    /**
     * Get stage number for a given task name in HealthClaim workflow
     *
     * @param taskName - BPMN task name
     * @return Stage number, or -1 if not found
     */
    public static int getHealthClaimStageNumber(String taskName) {
        if (taskName == null) {
            return -1;
        }

        // Try direct match first
        if (HEALTH_CLAIM_STAGES.containsKey(taskName)) {
            return HEALTH_CLAIM_STAGES.get(taskName);
        }

        // Try case-insensitive match
        for (Map.Entry<String, Integer> entry : HEALTH_CLAIM_STAGES.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(taskName)) {
                return entry.getValue();
            }
        }

        return -1;
    }

    /**
     * Get stage number based on workflow type and task name
     *
     * @param workflowType - Workflow type (HealthClaim, MotorClaim, etc.)
     * @param taskName - BPMN task name
     * @return Stage number, or -1 if not found
     */
    public static int getStageNumber(String workflowType, String taskName) {
        if ("HealthClaim".equalsIgnoreCase(workflowType)) {
            return getHealthClaimStageNumber(taskName);
        }

        // Add more workflow types here as needed
        // else if ("MotorClaim".equalsIgnoreCase(workflowType)) { ... }

        return -1;
    }

    /**
     * Check if a task is an agent stage (should have task-docs/ folder)
     *
     * @param taskName - BPMN task name
     * @return true if this is an agent stage
     */
    public static boolean isAgentStage(String taskName) {
        if (taskName == null) {
            return false;
        }

        String lower = taskName.toLowerCase();

        // Agent stages for HealthClaim
        return lower.contains("forgery") ||
                lower.contains("classifier") ||
                lower.contains("ocr") ||
                lower.contains("fhir") && (lower.contains("analyser") || lower.contains("analyzer")) ||
                lower.contains("validator") ||
                lower.contains("uidisplayer") ||
                lower.contains("policy") && lower.contains("coherence") ||
                lower.contains("medical") && lower.contains("coherence") ||
                lower.contains("ocrtostat");
    }
}