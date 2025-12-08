package com.DronaPay.frm.HealthClaim;

import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;

/**
 * UI Displayer - Prepares user-friendly fields from consolidated FHIR data
 *
 * Input: consolidatedFhir variable
 * Output: Flat key-value pairs for user verification task form
 */
@Slf4j
public class UIDisplayerDelegate implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("=== UI Displayer Started ===");

        // Get consolidated FHIR data
        String consolidatedFhir = (String) execution.getVariable("consolidatedFhir");
        if (consolidatedFhir == null || consolidatedFhir.isEmpty()) {
            log.error("No consolidated FHIR data available for UI display");
            execution.setVariable("uiFieldsReady", false);
            return;
        }

        try {
            JSONObject fhirData = new JSONObject(consolidatedFhir);

            // Extract and flatten fields for user verification
            Map<String, String> uiFields = new LinkedHashMap<>();

            // Extract from Claim Form if present
            if (fhirData.has("Claim Form")) {
                extractClaimFormFields(fhirData.getJSONObject("Claim Form"), uiFields);
            }

            // Extract from Identity Card if present
            if (fhirData.has("Identity Card")) {
                extractIdentityCardFields(fhirData.getJSONObject("Identity Card"), uiFields);
            }

            // Extract from Diagnostic Report if present
            if (fhirData.has("Diagnostic Report")) {
                extractDiagnosticReportFields(fhirData.getJSONObject("Diagnostic Report"), uiFields);
            }

            // Extract from other document types if present
            extractOtherDocumentFields(fhirData, uiFields);

            // Convert to JSON for storage
            JSONObject uiFieldsJson = new JSONObject(uiFields);
            execution.setVariable("uiFields", uiFieldsJson.toString());
            execution.setVariable("uiFieldsReady", true);

            // Also set individual variables for direct form access
            setIndividualFormVariables(execution, uiFields);

            log.info("UI fields prepared successfully with {} fields", uiFields.size());

        } catch (Exception e) {
            log.error("Error preparing UI fields", e);
            execution.setVariable("uiFieldsReady", false);
        }

        log.info("=== UI Displayer Completed ===");
    }

    /**
     * Extract fields from Claim Form section
     */
    private void extractClaimFormFields(JSONObject claimForm, Map<String, String> uiFields) {
        // Patient details
        if (claimForm.has("Insured_Patient_Details")) {
            JSONObject patientDetails = claimForm.getJSONObject("Insured_Patient_Details");
            addField(uiFields, "Patient Name", patientDetails, "Patient_Name");
            addField(uiFields, "Gender", patientDetails, "Gender");
            addField(uiFields, "Age", patientDetails, "Age");
            addField(uiFields, "Date of Birth", patientDetails, "Date_of_Birth");
            addField(uiFields, "Contact Number", patientDetails, "Contact_Number");
            addField(uiFields, "Policy Number", patientDetails, "Policy_number_or_Name_of_corporate");
            addField(uiFields, "Card ID", patientDetails, "Insured_card_ID_number");
        }

        // Doctor and hospital details
        if (claimForm.has("Treating_Doctor_Hospital_Details")) {
            JSONObject doctorDetails = claimForm.getJSONObject("Treating_Doctor_Hospital_Details");
            addField(uiFields, "Treating Doctor", doctorDetails, "Treating_Doctor_Name");
            addField(uiFields, "Doctor Contact", doctorDetails, "Treating_Doctor_Contact");
            addField(uiFields, "Illness/Disease", doctorDetails, "Nature_of_Illness_Disease_with_presenting_Complaints");
            addField(uiFields, "Clinical Findings", doctorDetails, "Relevant_Clinical_Findings");
            addField(uiFields, "Diagnosis", doctorDetails, "Provisional_Diagnosis");
            addField(uiFields, "ICD-10 Code", doctorDetails, "ICD_10_Code");
            addField(uiFields, "Treatment", doctorDetails, "Proposed_line_of_treatment");
            addField(uiFields, "Surgery Name", doctorDetails, "Surgery_Name");
        }

        // Admission details
        if (claimForm.has("Admission_Hospitalization_Details")) {
            JSONObject admissionDetails = claimForm.getJSONObject("Admission_Hospitalization_Details");
            addField(uiFields, "Admission Date", admissionDetails, "Date_of_Admission");
            addField(uiFields, "Room Type", admissionDetails, "Room_Type");
            addField(uiFields, "Expected Stay", admissionDetails, "Expected_days_stay");
            addField(uiFields, "Total Cost", admissionDetails, "Sum_total_expected_cost_of_hospitalization");
            addField(uiFields, "ICU Charges", admissionDetails, "ICU_Charges");
            addField(uiFields, "Professional Fees", admissionDetails, "Professional_Fees");
        }

        // Hospital details from Declaration
        if (claimForm.has("Declaration")) {
            JSONObject declaration = claimForm.getJSONObject("Declaration");
            addField(uiFields, "Hospital Name", declaration, "Hospital_Name");
            addField(uiFields, "Hospital Address", declaration, "Hospital_Address");
            addField(uiFields, "Hospital Contact", declaration, "Hospital_Contact");
        }
    }

    /**
     * Extract fields from Identity Card section
     */
    private void extractIdentityCardFields(JSONObject identityCard, Map<String, String> uiFields) {
        addField(uiFields, "Insured Name", identityCard, "Insured_Name");
        addField(uiFields, "Policy Start Date", identityCard, "Policy_Start_Date");
        addField(uiFields, "Policy End Date", identityCard, "Policy_End_Date");
        addField(uiFields, "Insurer Name", identityCard, "Insurer_Name");
        addField(uiFields, "TPA Name", identityCard, "TPA_Name");
        addField(uiFields, "Corporate Name", identityCard, "Corporate_Name");
    }

    /**
     * Extract fields from Diagnostic Report section
     */
    private void extractDiagnosticReportFields(JSONObject diagnosticReport, Map<String, String> uiFields) {
        if (diagnosticReport.has("Patient_Information")) {
            JSONObject patientInfo = diagnosticReport.getJSONObject("Patient_Information");
            addField(uiFields, "Referring Doctor", patientInfo, "Referring_Doctor");
            addField(uiFields, "Report Date", patientInfo, "Report_Date");
        }

        if (diagnosticReport.has("Test_Information")) {
            JSONObject testInfo = diagnosticReport.getJSONObject("Test_Information");
            addField(uiFields, "Test Name", testInfo, "Test_Name");
            addField(uiFields, "Test Result", testInfo, "Test_Result");
            addField(uiFields, "Test Impression", testInfo, "Impression");
        }

        if (diagnosticReport.has("Emergency_Triage_Notes")) {
            JSONObject triageNotes = diagnosticReport.getJSONObject("Emergency_Triage_Notes");
            addField(uiFields, "Chief Complaints", triageNotes, "Chief_Complaints");
            addField(uiFields, "Past History", triageNotes, "Past_History");
            addField(uiFields, "Medicines Administered", triageNotes, "Medicines_Administered");
        }
    }

    /**
     * Extract fields from other document types (Discharge Summary, Hospital Bill, etc.)
     */
    private void extractOtherDocumentFields(JSONObject fhirData, Map<String, String> uiFields) {
        for (String key : fhirData.keySet()) {
            if (!key.equals("Claim Form") && !key.equals("Identity Card") &&
                    !key.equals("Diagnostic Report")) {

                Object value = fhirData.get(key);
                if (value instanceof JSONObject) {
                    JSONObject docData = (JSONObject) value;
                    // Add document type as a header
                    uiFields.put("--- " + key + " ---", "");

                    // Extract key fields from this document type
                    extractGenericFields(docData, uiFields, "");
                }
            }
        }
    }

    /**
     * Generic field extraction for unknown document types
     */
    private void extractGenericFields(JSONObject data, Map<String, String> uiFields, String prefix) {
        for (String key : data.keySet()) {
            Object value = data.get(key);

            if (value instanceof JSONObject) {
                // Nested object, recurse with prefix
                extractGenericFields((JSONObject) value, uiFields, prefix + key + " - ");
            } else if (value != null && !value.toString().isEmpty() &&
                    !value.toString().equals("null")) {
                // Leaf value, add to fields
                String displayKey = prefix + formatFieldName(key);
                uiFields.put(displayKey, value.toString());
            }
        }
    }

    /**
     * Add a field to the UI fields map if it exists and is not null
     */
    private void addField(Map<String, String> uiFields, String displayName,
                          JSONObject source, String jsonKey) {
        if (source.has(jsonKey)) {
            Object value = source.get(jsonKey);
            if (value != null && !value.toString().isEmpty() &&
                    !value.toString().equals("null")) {
                uiFields.put(displayName, value.toString());
            }
        }
    }

    /**
     * Format field name from JSON key (convert underscores to spaces, capitalize)
     */
    private String formatFieldName(String jsonKey) {
        return jsonKey.replace("_", " ");
    }

    /**
     * Set individual process variables for direct form field access
     * This allows the user task form to access fields directly
     */
    private void setIndividualFormVariables(DelegateExecution execution,
                                            Map<String, String> uiFields) {
        // Set commonly used fields as individual variables
        setIfExists(execution, "ui_patientName", uiFields, "Patient Name");
        setIfExists(execution, "ui_gender", uiFields, "Gender");
        setIfExists(execution, "ui_age", uiFields, "Age");
        setIfExists(execution, "ui_policyNumber", uiFields, "Policy Number");
        setIfExists(execution, "ui_treatingDoctor", uiFields, "Treating Doctor");
        setIfExists(execution, "ui_diagnosis", uiFields, "Diagnosis");
        setIfExists(execution, "ui_treatment", uiFields, "Treatment");
        setIfExists(execution, "ui_admissionDate", uiFields, "Admission Date");
        setIfExists(execution, "ui_totalCost", uiFields, "Total Cost");
        setIfExists(execution, "ui_hospitalName", uiFields, "Hospital Name");
        setIfExists(execution, "ui_insurerName", uiFields, "Insurer Name");

        log.debug("Set {} individual form variables", 11);
    }

    /**
     * Set process variable if the field exists in UI fields
     */
    private void setIfExists(DelegateExecution execution, String varName,
                             Map<String, String> uiFields, String fieldKey) {
        if (uiFields.containsKey(fieldKey)) {
            execution.setVariable(varName, uiFields.get(fieldKey));
        }
    }
}