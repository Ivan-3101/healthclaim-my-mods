package com.DronaPay.frm.HealthClaim;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.json.JSONArray;
import org.json.JSONObject;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ProcessAgentResponse implements JavaDelegate {

    @SuppressWarnings("unchecked")
    @Override
    public void execute(DelegateExecution execution) throws Exception {
        log.info("Process Agent Response service called by ticket id " + execution.getVariable("TicketID"));

        Map<String, Map<String, Object>> fileProcessMap = (Map<String, Map<String, Object>>) execution
                .getVariable("fileProcessMap");

        List<String> successFiles = new ArrayList<>();
        List<String> failedFiles = new ArrayList<>();

        List<String> forgedDocs = new ArrayList<>();
        List<String> failedForgeApiCall = new ArrayList<>();

        List<String> failedOCRCall = new ArrayList<>();

        for (String file : fileProcessMap.keySet()) {

            Map<String, Object> processedOutput = fileProcessMap.get(file);

            // Process forged document check
            if (processedOutput.containsKey("forgedDocCheckOutput")) {
                Map<String, Object> forgeOutput = (Map<String, Object>) processedOutput.get("forgedDocCheckOutput");
                if (forgeOutput.containsKey("docForgedAPICall")) {
                    if (forgeOutput.get("docForgedAPICall").equals("success")) {
                        if (forgeOutput.containsKey("isDocForged")) {
                            if (forgeOutput.get("isDocForged").equals("yes")) {
                                forgedDocs.add(file);
                            }
                        }
                    } else {
                        failedForgeApiCall.add(file);
                    }
                } else {
                    failedForgeApiCall.add(file);
                }
            }

            // Process OCR results
            if (processedOutput.containsKey("ocrOnDocOutput")) {
                Map<String, Object> ocrOutput = (Map<String, Object>) processedOutput.get("ocrOnDocOutput");
                if (ocrOutput.containsKey("ocrOnDocAPICall")) {
                    if (!ocrOutput.get("ocrOnDocAPICall").equals("success")) {
                        failedOCRCall.add(file);
                    }
                } else {
                    failedOCRCall.add(file);
                }
            }

            // Process FHIR conversion results
            if (processedOutput.containsKey("ocrToFHIROutput")) {
                Map<String, Object> fhirOutput = (Map<String, Object>) processedOutput.get("ocrToFHIROutput");
                if (fhirOutput.containsKey("ocrToFHIRAPICall")) {
                    if (fhirOutput.get("ocrToFHIRAPICall").equals("success")) {
                        successFiles.add(file);

                        if (fhirOutput.containsKey("ocrToFHIRAPIResponse")) {
                            JSONObject fhir = new JSONObject(fhirOutput.get("ocrToFHIRAPIResponse").toString());
                            JSONArray entry = fhir.getJSONObject("answer").optJSONArray("entry");

                            if (entry != null) {
                                for (int i = 0; i < entry.length(); i++) {
                                    JSONObject entryObj = entry.getJSONObject(i);

                                    if (entryObj.optQuery("/resource/resourceType").equals("Patient")) {
                                        execution.setVariable("patientName",
                                                entryObj.optQuery("/resource/name/0/text"));
                                        execution.setVariable("gender",
                                                entryObj.optQuery("/resource/gender"));
                                        execution.setVariable("contact",
                                                entryObj.optQuery("/resource/telecom/0/value"));
                                    }

                                    if (entryObj.optQuery("/resource/resourceType").equals("Practitioner")) {
                                        execution.setVariable("doctorIdentifier",
                                                entryObj.optQuery("/resource/identifier/0/value"));
                                        execution.setVariable("doctorName",
                                                entryObj.optQuery("/resource/name/0/text"));
                                    }

                                    if (entryObj.optQuery("/resource/resourceType").equals("Condition")) {
                                        execution.setVariable("diseaseName",
                                                entryObj.optQuery("/resource/code/text"));
                                    }

                                    if (entryObj.optQuery("/resource/resourceType").equals("Procedure")) {
                                        execution.setVariable("procedure",
                                                entryObj.optQuery("/resource/code/text"));
                                        execution.setVariable("startDate",
                                                entryObj.optQuery("/resource/occurrenceDateTime"));
                                    }

                                    if (entryObj.optQuery("/resource/resourceType").equals("MedicationRequest")) {
                                        execution.setVariable("medicationName",
                                                entryObj.optQuery("/resource/contained/0/code/coding/0/display"));
                                        execution.setVariable("dosageInstruction",
                                                entryObj.optQuery("/resource/dosageInstruction/0/text"));
                                    }

                                    if (entryObj.optQuery("/resource/resourceType").equals("Coverage")) {
                                        execution.setVariable("insuranceCompany",
                                                entryObj.optQuery("/resource/payor/0/display"));
                                        execution.setVariable("period",
                                                entryObj.optQuery("/resource/period/start"));
                                    }

                                    if (entryObj.optQuery("/resource/resourceType").equals("Claim")) {
                                        execution.setVariable("hospitalName",
                                                entryObj.optQuery("/resource/provider/display"));
                                        execution.setVariable("claimFor",
                                                entryObj.optQuery("/resource/item/0/productOrService/coding/0/code"));
                                        execution.setVariable("claimAmount",
                                                entryObj.optQuery("/resource/total/value"));
                                    }
                                }
                            }
                        }
                    } else {
                        failedFiles.add(file);
                    }
                } else {
                    failedFiles.add(file);
                }
            }
        }

        // Set summary variables
        execution.setVariable("successFHIRCall", successFiles.stream().collect(Collectors.joining(",")));
        execution.setVariable("failedFHIRCall", failedFiles.stream().collect(Collectors.joining(",")));
        execution.setVariable("forgedDocs", forgedDocs.stream().collect(Collectors.joining(",")));
        execution.setVariable("failedForgeApiCall", failedForgeApiCall.stream().collect(Collectors.joining(",")));
        execution.setVariable("failedOCRCall", failedOCRCall.stream().collect(Collectors.joining(",")));

        // Log policy comparator status if available
        String policyStatus = (String) execution.getVariable("policyComparatorStatus");
        String policyMissing = (String) execution.getVariable("policyMissingInfo");
        String policyIssues = (String) execution.getVariable("policyPotentialIssues");

        log.info("Policy Comparator Status: " + policyStatus);
        log.info("Missing Policy Info: " + policyMissing);
        log.info("Policy Issues: " + policyIssues);
    }
}