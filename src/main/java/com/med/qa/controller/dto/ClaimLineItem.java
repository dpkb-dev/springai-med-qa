package com.med.qa.controller.dto;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;

/**
 * One claimed item on a reimbursement claim — a single procedure, medicine, or charge line, as it
 * would appear on a hospital bill.
 *
 * @param description free-text description of the claimed item, must not be blank
 * @param amount      the claimed amount for this line item, must not be {@code null} or negative
 * @param days        the number of days this charge covers (e.g. days of room rent), required only
 *                     for items whose matched policy clause expresses a per-day cap; {@code null} for
 *                     items with no meaningful day count (e.g. a single procedure or medicine)
 */
public record ClaimLineItem(String description, BigDecimal amount, @Nullable Integer days) {

    /**
     * Validates this line item.
     *
     * @throws IllegalArgumentException if the description is blank, the amount is missing/negative,
     *                                   or {@code days} is present but not positive
     */
    public void validate() {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("line item description must not be blank");
        }
        if (amount == null || amount.signum() < 0) {
            throw new IllegalArgumentException("line item amount must be present and non-negative");
        }
        if (days != null && days <= 0) {
            throw new IllegalArgumentException("line item days, when provided, must be positive");
        }
    }
}
