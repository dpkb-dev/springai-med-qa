package com.med.qa.service.underwriting;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.controller.dto.PolicyEligibilityRequest;
import com.med.qa.controller.dto.PolicyEligibilityResponse;
import com.med.qa.domain.entity.DeclaredConditionDO;
import com.med.qa.mapper.DeclaredConditionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Orchestrates a pre-existing-disease eligibility check: loads the policy's declaration for the
 * claimed condition, then hands it to {@link UnderwritingRulesEvaluator} — plain, deterministic Java
 * — to produce the verdict.
 *
 * <p>This service performs no business reasoning of its own. Its entire job is to fetch the right
 * record, refuse to proceed on corrupt data, and translate the evaluator's outcome into a response.
 * Every verdict is decided by the evaluator, so the same policy, condition and claim date always
 * yield the same answer regardless of when or how often the check runs — which is what makes an
 * outcome defensible when a policyholder escalates to the grievance cell or the IRDAI.</p>
 *
 * <p>No AI model is involved anywhere in this path. Whether the claim is PED-related at all is a
 * clinical judgment made by the insurer's doctor team and arrives on the request already made.</p>
 */
@Service
public class PolicyEligibilityService {

    private static final Logger log = LoggerFactory.getLogger(PolicyEligibilityService.class);

    private final DeclaredConditionMapper declaredConditionMapper;

    /**
     * Creates the service.
     *
     * @param declaredConditionMapper declaration data access, must not be {@code null}
     * @throws NullPointerException if {@code declaredConditionMapper} is {@code null}
     */
    public PolicyEligibilityService(DeclaredConditionMapper declaredConditionMapper) {
        this.declaredConditionMapper =
                Objects.requireNonNull(declaredConditionMapper, "declaredConditionMapper must not be null");
    }

    /**
     * Checks PED eligibility for one claimed condition.
     *
     * @param request the policy, condition, claim date and the doctor team's PED determination;
     *                must already be validated
     * @return the verdict, its rationale, and the escalation path where the verdict is terminal
     * @throws BizException {@link ErrorCode#STORAGE_ERROR} if the declaration lookup fails, or
     *                       {@link ErrorCode#INTERNAL_ERROR} if the loaded record is not evaluable
     */
    public PolicyEligibilityResponse check(PolicyEligibilityRequest request) {
        DeclaredConditionDO declaration = loadDeclaration(request);
        guardDataIntegrity(declaration);

        // 1. CALLING THE EVALUATOR AND ACCEPTING THE OUTCOME
        UnderwritingRulesEvaluator.EligibilityOutcome outcome = UnderwritingRulesEvaluator.evaluate(
                declaration, request.conditionCode(), request.claimDate(), request.pedRelated());

        // 2. EXTRACTING THE VERDICT TO CHECK IF IT'S TERMINAL
        boolean terminal = outcome.verdict().isTerminalRejection();
        log.debug("eligibility check for policy {} condition {}: {}",
                request.policyId(), request.conditionCode(), outcome.verdict());

        // 3. TRANSLATING THE OUTCOME INTO A RESPONSE DTO
        return new PolicyEligibilityResponse(
                request.policyId(),
                request.conditionCode(),
                outcome.verdict(),
                outcome.waitingPeriodEndsOn(),
                outcome.rationale(),
                terminal,
                terminal ? PolicyEligibilityResponse.ESCALATION_GUIDANCE : null);
    }

    private DeclaredConditionDO loadDeclaration(PolicyEligibilityRequest request) {
        try {
            return declaredConditionMapper.selectByPolicyAndCondition(
                    request.policyId(), request.conditionCode());
        } catch (RuntimeException ex) {
            log.error("declaration lookup failed for policy {} condition {}",
                    request.policyId(), request.conditionCode(), ex);
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "failed to load the declaration for policy " + request.policyId(), ex);
        }
    }

    /**
     * Refuses to evaluate a declaration whose waiting period is negative.
     *
     * <p>{@link DeclaredConditionDO} is a plain MyBatis bean with no validation of its own, and a
     * negative period would place {@code waitingPeriodEndsOn()} <em>before</em> {@code declaredOn} —
     * so the evaluator would silently return {@code COVERED} for a claim that should never have
     * passed. Corrupt data quietly producing a favourable verdict is worth failing loudly on. The
     * {@code ck_med_declared_condition_waiting_period} constraint blocks such a row at the storage
     * layer; this is the second line of defence for data written before that constraint existed or
     * through some path that bypassed it.</p>
     */
    private void guardDataIntegrity(DeclaredConditionDO declaration) {
        if (declaration != null && declaration.getWaitingPeriodMonths() < 0) {
            log.error("declaration {} has a negative waiting period of {} months",
                    declaration.getDeclarationId(), declaration.getWaitingPeriodMonths());
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "declaration " + declaration.getDeclarationId()
                            + " carries a negative waiting period and cannot be evaluated");
        }
    }
}
