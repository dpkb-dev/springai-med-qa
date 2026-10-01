package com.med.qa.service.medication;

import com.med.qa.controller.dto.ClinicalReport;

import java.util.List;

/**
 * Holds the clinical reports submitted during one consultation session.
 *
 * <h2>Session-scoped by design, not by accident</h2>
 * <p>Reports stored here expire with the session and are never promoted to long-term memory
 * (blueprint rule BR-5). Two independent reasons, both of which matter more than the convenience of
 * remembering:</p>
 * <ul>
 *   <li><b>Safety.</b> A Doppler from eight months ago resurfacing in a new consultation would let
 *       the system answer a fresh question against stale evidence. Re-requesting current reports is
 *       the safe behaviour, even though it costs the patient effort.</li>
 *   <li><b>Privacy.</b> Indefinitely retained clinical detail is a liability, and nothing a patient
 *       disclosed in a consultation should ever be reachable from an underwriting decision.</li>
 * </ul>
 *
 * <h2>Why an interface</h2>
 * <p>{@link MedicationConsultService} depends on this rather than on Redis directly, so storage can
 * move — to MySQL, or to an in-memory fake in tests — without the service changing.</p>
 */
public interface ConsultationSessionStore {

    /**
     * Stores the reports submitted for a session, replacing anything previously held for it.
     *
     * @param sessionId the consultation session, must not be blank
     * @param reports   the submitted reports, must not be {@code null}
     */
    void saveReports(String sessionId, List<ClinicalReport> reports);

    /**
     * Returns the reports held for a session.
     *
     * @param sessionId the consultation session, must not be blank
     * @return the stored reports, or an empty list when none are held or the session has expired
     */
    List<ClinicalReport> findReports(String sessionId);

    /**
     * Whether any reports are currently held for a session.
     *
     * @param sessionId the consultation session, must not be blank
     * @return {@code true} when at least one report is held
     */
    boolean hasReports(String sessionId);

    /**
     * Discards the reports held for a session.
     *
     * @param sessionId the consultation session, must not be blank
     */
    void evict(String sessionId);
}
