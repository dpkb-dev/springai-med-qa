package com.med.qa.service.medication;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MedicationRiskRules}, the deterministic medication-to-reports mapping.
 *
 * <p>No Spring, no model, no infrastructure — the same medication must always produce the same
 * report list, and these tests are what hold that guarantee in place.</p>
 */
class MedicationRiskRulesTest {

    @Nested
    @DisplayName("high-risk recognition")
    class HighRisk {

        @Test
        @DisplayName("recognises anticoagulants by brand and generic name")
        void recognisesAnticoagulants() {
            assertThat(MedicationRiskRules.isHighRisk("Pradaxa")).isTrue();
            assertThat(MedicationRiskRules.isHighRisk("dabigatran")).isTrue();
            assertThat(MedicationRiskRules.isHighRisk("Warfarin")).isTrue();
            assertThat(MedicationRiskRules.isHighRisk("Acitrom")).isTrue();
            assertThat(MedicationRiskRules.isHighRisk("Eliquis")).isTrue();
        }

        @Test
        @DisplayName("matching ignores case and surrounding whitespace")
        void matchingIsCaseAndWhitespaceInsensitive() {
            assertThat(MedicationRiskRules.isHighRisk("  PRADAXA  ")).isTrue();
            assertThat(MedicationRiskRules.isHighRisk("wArFaRiN")).isTrue();
        }

        @Test
        @DisplayName("an unrecognised medication is not flagged high-risk")
        void unrecognisedIsNotHighRisk() {
            assertThat(MedicationRiskRules.isHighRisk("Paracetamol")).isFalse();
            assertThat(MedicationRiskRules.isHighRisk(null)).isFalse();
            assertThat(MedicationRiskRules.isHighRisk("   ")).isFalse();
        }
    }

    @Nested
    @DisplayName("required reports")
    class RequiredReports {

        @Test
        @DisplayName("Warfarin requires INR and liver function, reflecting how it is monitored")
        void warfarinRequiresInrAndLiverFunction() {
            List<String> reports = MedicationRiskRules.requiredReportsFor("Warfarin", "Can I continue?");

            assertThat(reports).contains("COAGULATION_PANEL_INR", "LIVER_FUNCTION_TEST", "ECG");
        }

        @Test
        @DisplayName("Pradaxa requires renal function rather than INR, which is the real clinical difference")
        void pradaxaRequiresRenalFunction() {
            List<String> reports = MedicationRiskRules.requiredReportsFor("Pradaxa", "Can I continue?");

            assertThat(reports).contains("COAGULATION_PANEL", "RENAL_FUNCTION_TEST", "ECG");
            assertThat(reports).doesNotContain("COAGULATION_PANEL_INR");
        }

        @Test
        @DisplayName("general reports are always included alongside medication-specific ones")
        void generalReportsAlwaysIncluded() {
            List<String> reports = MedicationRiskRules.requiredReportsFor("Pradaxa", "Can I continue?");

            assertThat(reports).contains("RECENT_PRESCRIPTION_LIST", "CURRENT_DIAGNOSIS_SUMMARY");
        }

        @Test
        @DisplayName("an unrecognised medication still requires the general reports, never an empty list")
        void unrecognisedMedicationStillRequiresReports() {
            List<String> reports = MedicationRiskRules.requiredReportsFor("SomeNewDrug", "Can I continue?");

            // An unknown drug is a reason for MORE caution. An empty list would read as
            // "no reports needed", which a caller could mistake for "safe to answer directly".
            assertThat(reports).isNotEmpty();
            assertThat(reports).contains("RECENT_PRESCRIPTION_LIST", "CURRENT_DIAGNOSIS_SUMMARY");
        }

        @Test
        @DisplayName("the report list has no duplicates even when sources overlap")
        void reportListHasNoDuplicates() {
            List<String> reports = MedicationRiskRules.requiredReportsFor(
                    "Warfarin", "I have DVT, can I continue?");

            assertThat(reports).doesNotHaveDuplicates();
        }
    }

    @Nested
    @DisplayName("clot-related imaging")
    class ClotRelated {

        @Test
        @DisplayName("a DVT mention adds lower-limb Doppler imaging")
        void dvtMentionAddsDoppler() {
            List<String> reports = MedicationRiskRules.requiredReportsFor(
                    "Pradaxa", "I have DVT in my left leg, should I continue?");

            assertThat(reports).contains("DOPPLER_ULTRASOUND_LOWER_LIMB");
        }

        @Test
        @DisplayName("clot keywords are recognised in several forms")
        void clotKeywordsRecognisedInSeveralForms() {
            assertThat(MedicationRiskRules.mentionsClotCondition("diagnosed with deep vein thrombosis")).isTrue();
            assertThat(MedicationRiskRules.mentionsClotCondition("worried about a CLOT")).isTrue();
            assertThat(MedicationRiskRules.mentionsClotCondition("risk of pulmonary embolism")).isTrue();
        }

        @Test
        @DisplayName("a question with no clot mention does not request imaging")
        void noClotMentionNoImaging() {
            List<String> reports = MedicationRiskRules.requiredReportsFor(
                    "Warfarin", "Can I take this with my blood pressure tablet?");

            assertThat(reports).doesNotContain("DOPPLER_ULTRASOUND_LOWER_LIMB");
        }

        @Test
        @DisplayName("null or blank question text is handled safely")
        void nullOrBlankQuestionHandled() {
            assertThat(MedicationRiskRules.mentionsClotCondition(null)).isFalse();
            assertThat(MedicationRiskRules.mentionsClotCondition("   ")).isFalse();
            assertThat(MedicationRiskRules.requiredReportsFor("Pradaxa", null)).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("diagnostic checklist")
    class Checklist {

        @Test
        @DisplayName("the checklist asks questions and never states conclusions")
        void checklistAsksRatherThanConcludes() {
            List<String> checklist = MedicationRiskRules.generalDiagnosticChecklist();

            assertThat(checklist).isNotEmpty();
            // Every entry is a question put to the patient. Nothing here interprets an answer,
            // which is the boundary that keeps clinical judgment with the clinician.
            assertThat(checklist).allMatch(question -> question.endsWith("?"));
        }
    }
}
