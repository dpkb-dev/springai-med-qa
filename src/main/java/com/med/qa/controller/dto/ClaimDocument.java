package com.med.qa.controller.dto;

/**
 * One document submitted as part of a reimbursement claim bundle — its text content, plus a label
 * identifying what kind of document it is.
 *
 * @param documentType a short label such as {@code "ADMISSION_FORM"}, {@code "DISCHARGE_SUMMARY"},
 *                      {@code "ITEMIZED_BILL"}, or {@code "PRESCRIPTION"}; free text, not a closed
 *                      enum, since real claim bundles vary by insurer and hospital
 * @param text          the document's raw text content, must not be blank
 */
public record ClaimDocument(String documentType, String text) {

    /**
     * Validates this document.
     *
     * @throws IllegalArgumentException if {@code documentType} or {@code text} is blank
     */
    public void validate() {
        if (documentType == null || documentType.isBlank()) {
            throw new IllegalArgumentException("documentType must not be blank");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("document text must not be blank");
        }
    }
}
