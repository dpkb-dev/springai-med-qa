package com.med.qa.service.medication;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic rules for which clinical reports a medication-safety question requires before it can
 * be reviewed at all.
 *
 * <p>This class contains zero calls to any AI model. Asking "which reports does Warfarin need?" is a
 * clinical-protocol question with a fixed answer, not something to infer per request: the same
 * medication must always produce the same report list, or two policyholders asking the same question
 * would be asked for different evidence.</p>
 *
 * <h2>What this class decides, and what it does not</h2>
 * <p>It decides <em>what to collect</em>. It never decides what the collected answers mean. Whether a
 * given ECG or Doppler finding is concerning is a clinical interpretation, and stays with the
 * clinician (blueprint rule BR-2). The general diagnostic checklist below is in the same spirit: it
 * lists questions worth asking, never how to read the answers.</p>
 *
 * <h2>Scope in v1</h2>
 * <p>Anticoagulants only — the class of medication that motivated the feature, where the
 * consequences of continuing or stopping without review are most serious. An unrecognised medication
 * is deliberately <em>not</em> treated as safe: it returns the general checklist, so an unknown drug
 * still routes to a clinician rather than silently bypassing review.</p>
 */
public final class MedicationRiskRules {

    /**
     * Reports required for any high-risk medication in addition to its specific ones. Collected for
     * every high-risk enquiry because they establish the baseline a clinician reads the rest against.
     */
    private static final List<String> GENERAL_REPORTS = List.of(
            "RECENT_PRESCRIPTION_LIST",
            "CURRENT_DIAGNOSIS_SUMMARY");

    /**
     * Diagnostic questions worth putting to the patient alongside the reports. These are prompts for
     * a clinician's review, not a scoring instrument — nothing in this application interprets the
     * answers.
     */
    private static final List<String> GENERAL_DIAGNOSTIC_CHECKLIST = List.of(
            "Any chest pain or angina, at rest or on exertion?",
            "Any unusual breathlessness on mild exertion?",
            "Any recent change in blood pressure readings, including during exertion?",
            "Any bleeding, unusual bruising, or blood in urine or stool?",
            "Any swelling, pain or tenderness in either leg?",
            "Any missed doses, or changes to other medicines, in the last 30 days?");

    /**
     * Medication (lowercased, including common brand and generic names) to the reports that
     * medication specifically requires. Brand and generic names are listed separately rather than
     * normalised, so the map stays readable against a real prescription.
     */
    private static final Map<String, List<String>> MEDICATION_SPECIFIC_REPORTS = Map.of(
            "warfarin", List.of("COAGULATION_PANEL_INR", "LIVER_FUNCTION_TEST", "ECG"),
            "pradaxa", List.of("COAGULATION_PANEL", "RENAL_FUNCTION_TEST", "ECG"),
            "dabigatran", List.of("COAGULATION_PANEL", "RENAL_FUNCTION_TEST", "ECG"),
            "xarelto", List.of("COAGULATION_PANEL", "RENAL_FUNCTION_TEST", "ECG"),
            "rivaroxaban", List.of("COAGULATION_PANEL", "RENAL_FUNCTION_TEST", "ECG"),
            "eliquis", List.of("COAGULATION_PANEL", "RENAL_FUNCTION_TEST", "ECG"),
            "apixaban", List.of("COAGULATION_PANEL", "RENAL_FUNCTION_TEST", "ECG"),
            "acitrom", List.of("COAGULATION_PANEL_INR", "LIVER_FUNCTION_TEST", "ECG"),
            "acenocoumarol", List.of("COAGULATION_PANEL_INR", "LIVER_FUNCTION_TEST", "ECG"),
            "clopidogrel", List.of("COAGULATION_PANEL", "ECG"));

    /**
     * Reports additionally required when the enquiry mentions a clotting condition, where imaging is
     * needed to see whether an existing clot has resolved.
     */
    private static final List<String> CLOT_RELATED_REPORTS = List.of(
            "DOPPLER_ULTRASOUND_LOWER_LIMB");

    /**
     * Lowercased condition keywords that make the clot-related imaging necessary.
     */
    private static final Set<String> CLOT_RELATED_KEYWORDS = Set.of(
            "dvt", "deep vein thrombosis", "thrombosis", "clot", "embolism",
            "pulmonary embolism", "pe");

    private MedicationRiskRules() {
    }

    /**
     * Whether a medication is treated as high-risk, meaning a safety question about it must never be
     * answered without supporting reports.
     *
     * @param medication the medication name as the patient wrote it, may be {@code null} or blank
     * @return {@code true} when the medication is a recognised anticoagulant or antiplatelet
     */
    public static boolean isHighRisk(String medication) {
        return medication != null
                && MEDICATION_SPECIFIC_REPORTS.containsKey(normalise(medication));
    }

    /**
     * Returns every report that must be supplied before a safety question about this medication can
     * be reviewed.
     *
     * <p>An unrecognised medication returns the general reports rather than an empty list: an unknown
     * drug is a reason for more caution, not less, and a caller must never read "no reports needed"
     * as "safe to answer directly".</p>
     *
     * @param medication   the medication name as the patient wrote it, may be {@code null} or blank
     * @param questionText the patient's question, scanned for clot-related terms that make imaging
     *                     necessary; may be {@code null}
     * @return the required report types, in a stable order, never empty
     */
    public static List<String> requiredReportsFor(String medication, String questionText) {
        Set<String> required = new LinkedHashSet<>(GENERAL_REPORTS);

        List<String> specific = MEDICATION_SPECIFIC_REPORTS.get(normalise(medication));
        if (specific != null) {
            required.addAll(specific);
        }

        if (mentionsClotCondition(questionText)) {
            required.addAll(CLOT_RELATED_REPORTS);
        }

        return List.copyOf(required);
    }

    /**
     * Returns the diagnostic questions worth putting to the patient alongside the reports.
     *
     * @return the checklist, never empty and never interpreted by this application
     */
    public static List<String> generalDiagnosticChecklist() {
        return GENERAL_DIAGNOSTIC_CHECKLIST;
    }

    /**
     * Whether the supplied text mentions a clotting condition, which makes limb imaging necessary.
     *
     * @param text the patient's question or condition note, may be {@code null}
     * @return {@code true} when a clot-related term appears
     */
    static boolean mentionsClotCondition(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lowered = text.toLowerCase(Locale.ROOT);
        for (String keyword : CLOT_RELATED_KEYWORDS) {
            if (lowered.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static String normalise(String medication) {
        return medication == null ? "" : medication.trim().toLowerCase(Locale.ROOT);
    }
}
