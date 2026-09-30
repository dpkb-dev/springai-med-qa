-- Flyway migration: create the med_declared_condition table, holding one row per pre-existing
-- disease (PED) a policyholder declared when the policy was purchased.
--
-- This table is the feature's only long-term record (blueprint rule BR-5). Free-form clinical
-- conversation and submitted report text stay session-scoped and are never promoted here: a stale
-- report resurfacing in a later consultation is a safety risk, and retaining clinical dialogue
-- indefinitely is a privacy liability. What persists is the structured declaration - the fact of
-- disclosure, and the terms attached to it at issuance.
--
-- Like med_session, this table is NOT sharded. A policy carries a handful of declared conditions at
-- most, rows are read by (policy_id, condition_code) or listed per policy, and total volume is
-- orders of magnitude below med_message. Sharding it would buy nothing and turn a plain indexed
-- lookup into a scatter-gather. ShardingSphere-JDBC serves it through its SINGLE rule.
--
-- Column notes:
--   waiting_period_months  The period PUBLISHED for that product in its brochure and policy
--                          document and confirmed with the customer at issuance - typically 12 to
--                          36 months, genuinely different across products and across conditions
--                          within a product, and reduced (possibly to 0) by a waiver-of-PED add-on.
--                          It is stored per declaration rather than derived in code so the engine
--                          can never hold an opinion that contradicts the customer's own policy
--                          document.
--   permanently_excluded   Some policies are issued with a specific condition excluded outright.
--                          Unlike a waiting period this never lapses, so it is a distinct state
--                          rather than "a very long waiting period".
--   declared_on            Starts the waiting period. Stored as a DATE, not epoch millis: the whole
--                          domain reasons in whole days (brochures say "3 years", claims carry a
--                          date of hospitalisation), so a timestamp would imply a precision the
--                          business rule does not have.
--
-- The DDL is intentionally backend-agnostic so it runs unchanged against MySQL (production) and an
-- in-memory H2 schema in MySQL compatibility mode (tests). Index names carry the table name because
-- H2 requires schema-wide unique index names.

CREATE TABLE IF NOT EXISTS med_declared_condition (
    declaration_id        VARCHAR(64)  NOT NULL,
    policy_id             VARCHAR(64)  NOT NULL,
    condition_code        VARCHAR(64)  NOT NULL,
    declared_on           DATE         NOT NULL,
    waiting_period_months INT          NOT NULL,
    permanently_excluded  TINYINT(1)   NOT NULL DEFAULT 0,
    created_at            BIGINT       NOT NULL,
    PRIMARY KEY (declaration_id),
    UNIQUE INDEX uk_med_declared_condition_policy_code (policy_id, condition_code),
    INDEX idx_med_declared_condition_policy (policy_id),
    -- A negative waiting period would make waiting_period_ends_on fall BEFORE declared_on, so the
    -- evaluator would silently return COVERED for a claim that should never have passed. Corrupt
    -- data quietly producing a favourable verdict is worth blocking at the storage layer, not just
    -- in application code. Enforced from MySQL 8.0.16 onward and honoured by H2 in MySQL mode.
    CONSTRAINT ck_med_declared_condition_waiting_period CHECK (waiting_period_months >= 0)
);
