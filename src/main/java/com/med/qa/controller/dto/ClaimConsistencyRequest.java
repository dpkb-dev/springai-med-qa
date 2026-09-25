package com.med.qa.controller.dto;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import org.springframework.lang.Nullable;

import java.util.List;

/**
 * A bundle of claim documents to check for internal inconsistencies.
 *
 * @param claimId   optional caller-supplied claim identifier, echoed back for the CC team's tracking
 * @param documents the documents to cross-check; must contain at least two, since consistency is a
 *                  cross-document comparison and a single document has nothing to be compared against
 */
public record ClaimConsistencyRequest(@Nullable String claimId, List<ClaimDocument> documents) {

    /**
     * Validates the request as a whole, including every document.
     *
     * @throws BizException {@link ErrorCode#BAD_REQUEST} if fewer than two documents are supplied or
     *                       any individual document fails its own validation
     */
    public void validate() {
        if (documents == null || documents.size() < 2) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "at least two documents are required to check consistency across them");
        }
        for (ClaimDocument document : documents) {
            try {
                document.validate();
            } catch (IllegalArgumentException ex) {
                throw new BizException(ErrorCode.BAD_REQUEST, ex.getMessage());
            }
        }
    }
}
