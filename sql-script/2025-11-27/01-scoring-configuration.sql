-- ============================================================================
-- Scoring Configuration for HealthClaim Workflow
-- ============================================================================

-- Step 1: Check if configuration exists and delete old ones
DELETE FROM ui.workflowmasters
WHERE workflowkey = 'HealthClaim'
  AND itenantid IN (0, 1)
  AND filterparams IS NOT NULL;

-- Step 2: Insert fresh configuration for tenant 0
INSERT INTO ui.workflowmasters (
    workflowname,
    workflowkey,
    itenantid,
    filterparams
)
VALUES (
    'Health Claim',
    'HealthClaim',
    0,
    '{
      "scoring": {
        "fwaDecisioning": {
          "enabled": true,
          "apiEndpoint": "${springApiUrl}/score",
          "requestTemplate": {
            "staticFields": {
              "org": "EPIFI",
              "txn.type": "PAY",
              "txn.class": "health_claim",
              "payer.mcc": 0,
              "payer.type": "PERSON",
              "payer.currency": "INR",
              "payee.type": "PERSON",
              "payee.currency": "INR"
            },
            "dynamicFields": {
              "reqid": "{{UUID}}",
              "ts": "{{TIMESTAMP}}",
              "txn.ts": "{{TIMESTAMP}}",
              "txn.id": "{{RANDOM_ID}}"
            },
            "variableMappings": [
              {"apiField": "txn.attribs.patient_name", "processVariable": "patientName", "dataType": "string"},
              {"apiField": "txn.attribs.gender", "processVariable": "gender", "dataType": "string"},
              {"apiField": "txn.attribs.doctor_id", "processVariable": "doctorIdentifier", "dataType": "string"},
              {"apiField": "txn.attribs.doctor_name", "processVariable": "doctorName", "dataType": "string"},
              {"apiField": "txn.attribs.disease_name", "processVariable": "diseaseName", "dataType": "string"},
              {"apiField": "txn.attribs.procedure", "processVariable": "procedure", "dataType": "string"},
              {"apiField": "txn.attribs.start_date", "processVariable": "startDate", "dataType": "string"},
              {"apiField": "txn.attribs.medication_name", "processVariable": "medicationName", "dataType": "string"},
              {"apiField": "txn.attribs.instruction_for_usage", "processVariable": "dosageInstruction", "dataType": "string"},
              {"apiField": "txn.attribs.patient_contact", "processVariable": "contact", "dataType": "string"},
              {"apiField": "txn.attribs.insurance_company", "processVariable": "insuranceCompany", "dataType": "string"},
              {"apiField": "txn.attribs.time_period", "processVariable": "period", "dataType": "string"},
              {"apiField": "txn.attribs.hospital_name", "processVariable": "hospitalName", "dataType": "string"},
              {"apiField": "txn.attribs.claim_for", "processVariable": "claimFor", "dataType": "string"},
              {"apiField": "txn.attribs.claim_amount", "processVariable": "claimAmount", "dataType": "long"}
            ]
          },
          "responseMapping": {
            "RiskScore": {
              "jsonPath": "/score/score",
              "dataType": "long",
              "defaultValue": 100
            },
            "fwaResponse": {
              "jsonPath": "",
              "dataType": "string",
              "defaultValue": null
            }
          }
        },
        "claimCostComputation": {
          "enabled": true,
          "apiEndpoint": "${springApiUrl}/score",
          "requestTemplate": {
            "staticFields": {
              "org": "EPIFI",
              "txn.type": "PAY",
              "txn.class": "health_claim_cost",
              "payer.mcc": 0,
              "payer.type": "PERSON",
              "payer.currency": "INR",
              "payee.type": "PERSON",
              "payee.currency": "INR"
            },
            "dynamicFields": {
              "reqid": "{{UUID}}",
              "ts": "{{TIMESTAMP}}",
              "txn.ts": "{{TIMESTAMP}}",
              "txn.id": "{{RANDOM_ID}}"
            },
            "variableMappings": [
              {"apiField": "txn.attribs.patient_name", "processVariable": "patientName", "dataType": "string"},
              {"apiField": "txn.attribs.gender", "processVariable": "gender", "dataType": "string"},
              {"apiField": "txn.attribs.doctor_id", "processVariable": "doctorIdentifier", "dataType": "string"},
              {"apiField": "txn.attribs.doctor_name", "processVariable": "doctorName", "dataType": "string"},
              {"apiField": "txn.attribs.disease_name", "processVariable": "diseaseName", "dataType": "string"},
              {"apiField": "txn.attribs.procedure", "processVariable": "procedure", "dataType": "string"},
              {"apiField": "txn.attribs.start_date", "processVariable": "startDate", "dataType": "string"},
              {"apiField": "txn.attribs.medication_name", "processVariable": "medicationName", "dataType": "string"},
              {"apiField": "txn.attribs.instruction_for_usage", "processVariable": "dosageInstruction", "dataType": "string"},
              {"apiField": "txn.attribs.patient_contact", "processVariable": "contact", "dataType": "string"},
              {"apiField": "txn.attribs.insurance_company", "processVariable": "insuranceCompany", "dataType": "string"},
              {"apiField": "txn.attribs.time_period", "processVariable": "period", "dataType": "string"},
              {"apiField": "txn.attribs.hospital_name", "processVariable": "hospitalName", "dataType": "string"},
              {"apiField": "txn.attribs.claim_for", "processVariable": "claimFor", "dataType": "string"},
              {"apiField": "txn.attribs.claim_amount", "processVariable": "claimAmount", "dataType": "long"}
            ]
          },
          "responseMapping": {
            "approvedAmount": {
              "jsonPath": "/score/decisiondetails/0/approved_amount",
              "dataType": "long",
              "defaultValue": "{{claimAmount}}"
            },
            "bonusAmount": {
              "jsonPath": "/score/decisiondetails/0/bonus",
              "dataType": "long",
              "defaultValue": 0
            },
            "finalAmount": {
              "jsonPath": "/score/decisiondetails/0/final_amount",
              "dataType": "long",
              "defaultValue": "{{claimAmount}}"
            },
            "claimCostResp": {
              "jsonPath": "",
              "dataType": "string",
              "defaultValue": null
            }
          }
        }
      }
    }'::jsonb
);

-- Step 3: Insert configuration for tenant 1
INSERT INTO ui.workflowmasters (
    workflowname,
    workflowkey,
    itenantid,
    filterparams
)
VALUES (
    'Health Claim',
    'HealthClaim',
    1,
    '{
      "scoring": {
        "fwaDecisioning": {
          "enabled": true,
          "apiEndpoint": "${springApiUrl}/score",
          "requestTemplate": {
            "staticFields": {
              "org": "EPIFI",
              "txn.type": "PAY",
              "txn.class": "health_claim",
              "payer.mcc": 0,
              "payer.type": "PERSON",
              "payer.currency": "INR",
              "payee.type": "PERSON",
              "payee.currency": "INR"
            },
            "dynamicFields": {
              "reqid": "{{UUID}}",
              "ts": "{{TIMESTAMP}}",
              "txn.ts": "{{TIMESTAMP}}",
              "txn.id": "{{RANDOM_ID}}"
            },
            "variableMappings": [
              {"apiField": "txn.attribs.patient_name", "processVariable": "patientName", "dataType": "string"},
              {"apiField": "txn.attribs.gender", "processVariable": "gender", "dataType": "string"},
              {"apiField": "txn.attribs.doctor_id", "processVariable": "doctorIdentifier", "dataType": "string"},
              {"apiField": "txn.attribs.doctor_name", "processVariable": "doctorName", "dataType": "string"},
              {"apiField": "txn.attribs.disease_name", "processVariable": "diseaseName", "dataType": "string"},
              {"apiField": "txn.attribs.procedure", "processVariable": "procedure", "dataType": "string"},
              {"apiField": "txn.attribs.start_date", "processVariable": "startDate", "dataType": "string"},
              {"apiField": "txn.attribs.medication_name", "processVariable": "medicationName", "dataType": "string"},
              {"apiField": "txn.attribs.instruction_for_usage", "processVariable": "dosageInstruction", "dataType": "string"},
              {"apiField": "txn.attribs.patient_contact", "processVariable": "contact", "dataType": "string"},
              {"apiField": "txn.attribs.insurance_company", "processVariable": "insuranceCompany", "dataType": "string"},
              {"apiField": "txn.attribs.time_period", "processVariable": "period", "dataType": "string"},
              {"apiField": "txn.attribs.hospital_name", "processVariable": "hospitalName", "dataType": "string"},
              {"apiField": "txn.attribs.claim_for", "processVariable": "claimFor", "dataType": "string"},
              {"apiField": "txn.attribs.claim_amount", "processVariable": "claimAmount", "dataType": "long"}
            ]
          },
          "responseMapping": {
            "RiskScore": {
              "jsonPath": "/score/score",
              "dataType": "long",
              "defaultValue": 100
            },
            "fwaResponse": {
              "jsonPath": "",
              "dataType": "string",
              "defaultValue": null
            }
          }
        },
        "claimCostComputation": {
          "enabled": true,
          "apiEndpoint": "${springApiUrl}/score",
          "requestTemplate": {
            "staticFields": {
              "org": "EPIFI",
              "txn.type": "PAY",
              "txn.class": "health_claim_cost",
              "payer.mcc": 0,
              "payer.type": "PERSON",
              "payer.currency": "INR",
              "payee.type": "PERSON",
              "payee.currency": "INR"
            },
            "dynamicFields": {
              "reqid": "{{UUID}}",
              "ts": "{{TIMESTAMP}}",
              "txn.ts": "{{TIMESTAMP}}",
              "txn.id": "{{RANDOM_ID}}"
            },
            "variableMappings": [
              {"apiField": "txn.attribs.patient_name", "processVariable": "patientName", "dataType": "string"},
              {"apiField": "txn.attribs.gender", "processVariable": "gender", "dataType": "string"},
              {"apiField": "txn.attribs.doctor_id", "processVariable": "doctorIdentifier", "dataType": "string"},
              {"apiField": "txn.attribs.doctor_name", "processVariable": "doctorName", "dataType": "string"},
              {"apiField": "txn.attribs.disease_name", "processVariable": "diseaseName", "dataType": "string"},
              {"apiField": "txn.attribs.procedure", "processVariable": "procedure", "dataType": "string"},
              {"apiField": "txn.attribs.start_date", "processVariable": "startDate", "dataType": "string"},
              {"apiField": "txn.attribs.medication_name", "processVariable": "medicationName", "dataType": "string"},
              {"apiField": "txn.attribs.instruction_for_usage", "processVariable": "dosageInstruction", "dataType": "string"},
              {"apiField": "txn.attribs.patient_contact", "processVariable": "contact", "dataType": "string"},
              {"apiField": "txn.attribs.insurance_company", "processVariable": "insuranceCompany", "dataType": "string"},
              {"apiField": "txn.attribs.time_period", "processVariable": "period", "dataType": "string"},
              {"apiField": "txn.attribs.hospital_name", "processVariable": "hospitalName", "dataType": "string"},
              {"apiField": "txn.attribs.claim_for", "processVariable": "claimFor", "dataType": "string"},
              {"apiField": "txn.attribs.claim_amount", "processVariable": "claimAmount", "dataType": "long"}
            ]
          },
          "responseMapping": {
            "approvedAmount": {
              "jsonPath": "/score/decisiondetails/0/approved_amount",
              "dataType": "long",
              "defaultValue": "{{claimAmount}}"
            },
            "bonusAmount": {
              "jsonPath": "/score/decisiondetails/0/bonus",
              "dataType": "long",
              "defaultValue": 0
            },
            "finalAmount": {
              "jsonPath": "/score/decisiondetails/0/final_amount",
              "dataType": "long",
              "defaultValue": "{{claimAmount}}"
            },
            "claimCostResp": {
              "jsonPath": "",
              "dataType": "string",
              "defaultValue": null
            }
          }
        }
      }
    }'::jsonb
);

-- Step 4: Verify the insertion
SELECT
    workflowkey,
    itenantid,
    jsonb_pretty(filterparams) as configuration
FROM ui.workflowmasters
WHERE workflowkey = 'HealthClaim'
  AND itenantid IN (0, 1)
  AND filterparams IS NOT NULL;