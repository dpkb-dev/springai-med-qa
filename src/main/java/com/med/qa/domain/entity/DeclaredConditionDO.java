package com.med.qa.domain.entity;

import java.time.LocalDate;

/**
 * A pre-existing disease declared by the policyholder when the policy was purchased.
 *
 * <p>This is the only long-term record the PED eligibility feature keeps (blueprint rule BR-5).
 * Free-form clinical conversation and submitted report text are deliberately session-scoped and are
 * never promoted here: a stale report resurfacing in a later consultation is a safety risk, and
 * retaining clinical dialogue indefinitely is a privacy liability. What persists is the structured
 * declaration — the fact of disclosure, and the terms attached to it at issuance.</p>
 *
 * <h2>Why the waiting period lives here rather than in code</h2>
 * <p>{@code waitingPeriodMonths} is copied onto this record at issuance rather than looked up from a
 * constant, because the applicable period is whatever was <em>published</em> in that product's
 * brochure and policy document and confirmed with the customer — typically one to three years, and
 * genuinely different across products and across conditions within a product. A waiver-of-PED add-on
 * purchased with the policy is reflected in this same value. Hard-coding a period in Java would mean
 * the engine holding an opinion that contradicts the customer's own policy document.</p>
 *
 * <h2>Why this is a mutable JavaBean rather than an immutable record</h2>
 * <p>MyBatis populates result maps through a no-arg constructor and setters. This class follows the
 * same shape as {@link ChatSessionDO} for that reason, so a declaration loads through the ordinary
 * mapper path without constructor-arg binding.</p>
 */
public class DeclaredConditionDO {

    private String declarationId;

    private String policyId;

    private String conditionCode;

    private LocalDate declaredOn;

    private int waitingPeriodMonths;

    private boolean permanentlyExcluded;

    private long createdAt;

    /**
     * Returns the date on which this condition's waiting period completes.
     *
     * @return {@code declaredOn} plus the waiting period
     * @throws IllegalStateException if {@code declaredOn} has not been populated
     */
    public LocalDate waitingPeriodEndsOn() {
        if (declaredOn == null) {
            throw new IllegalStateException(
                    "declaredOn must be set before computing the waiting period end");
        }
        return declaredOn.plusMonths(waitingPeriodMonths);
    }

    public String getDeclarationId() {
        return declarationId;
    }

    public void setDeclarationId(String declarationId) {
        this.declarationId = declarationId;
    }

    public String getPolicyId() {
        return policyId;
    }

    public void setPolicyId(String policyId) {
        this.policyId = policyId;
    }

    public String getConditionCode() {
        return conditionCode;
    }

    public void setConditionCode(String conditionCode) {
        this.conditionCode = conditionCode;
    }

    public LocalDate getDeclaredOn() {
        return declaredOn;
    }

    public void setDeclaredOn(LocalDate declaredOn) {
        this.declaredOn = declaredOn;
    }

    /**
     * Returns the waiting period in months, as published for this product and adjusted by any
     * waiver-of-PED add-on on the policy.
     *
     * @return the waiting period in months
     */
    public int getWaitingPeriodMonths() {
        return waitingPeriodMonths;
    }

    public void setWaitingPeriodMonths(int waitingPeriodMonths) {
        this.waitingPeriodMonths = waitingPeriodMonths;
    }

    /**
     * Whether this condition was permanently excluded from cover at issuance, in which case it never
     * becomes claimable regardless of elapsed time.
     *
     * @return {@code true} when permanently excluded
     */
    public boolean isPermanentlyExcluded() {
        return permanentlyExcluded;
    }

    public void setPermanentlyExcluded(boolean permanentlyExcluded) {
        this.permanentlyExcluded = permanentlyExcluded;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }
}
