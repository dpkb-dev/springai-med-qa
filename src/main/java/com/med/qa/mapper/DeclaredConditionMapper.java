package com.med.qa.mapper;

import com.med.qa.domain.entity.DeclaredConditionDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * MyBatis data-access mapper for declared pre-existing diseases, backed by the single (non-sharded)
 * {@code med_declared_condition} table.
 *
 * <p>Declarations deliberately stay in one table: a policy carries a handful of declared conditions
 * at most, rows are read by {@code (policy_id, condition_code)} or listed per policy, and total
 * volume is orders of magnitude below {@code med_message}. ShardingSphere-JDBC routes the table
 * through its {@code SINGLE} rule.</p>
 *
 * <p>This mapper is read-mostly by design. The eligibility engine only ever reads: declarations are
 * written once at policy issuance, which in v1 is a manual seed rather than an API (decision C-4).
 * {@link #insert(DeclaredConditionDO)} exists for that seeding and for tests, not for a
 * customer-facing declaration flow.</p>
 */
@Mapper
public interface DeclaredConditionMapper {

    /**
     * Inserts a declaration row.
     *
     * @param declaration the declaration to persist; must carry a non-blank {@code declarationId},
     *                    {@code policyId} and {@code conditionCode}, and a non-null
     *                    {@code declaredOn}
     * @return the number of affected rows (1 on success)
     */
    int insert(DeclaredConditionDO declaration);

    /**
     * Loads the declaration of one condition on one policy.
     *
     * <p>A {@code null} return is meaningful to the eligibility engine rather than merely empty: it
     * is precisely the non-disclosure case, which terminally rejects a PED-related claim regardless
     * of how much time has since elapsed.</p>
     *
     * @param policyId      the policy id
     * @param conditionCode the condition code being claimed
     * @return the matching declaration, or {@code null} when the condition was never declared
     */
    DeclaredConditionDO selectByPolicyAndCondition(@Param("policyId") String policyId,
                                                   @Param("conditionCode") String conditionCode);

    /**
     * Lists every condition declared on one policy, for display to a reviewer.
     *
     * @param policyId the policy id
     * @return the declarations ordered by {@code declared_on} ascending, possibly empty
     */
    List<DeclaredConditionDO> selectAllByPolicy(@Param("policyId") String policyId);
}
