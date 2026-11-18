-- 限制值统一使用 -1；零额度仍表示不允许新执行。
ALTER TABLE quota_policy
    DROP CHECK ck_quota_policy_1,
    MODIFY monthly_limit BIGINT DEFAULT -1 COMMENT '-1 表示该层不限，0 禁止新执行；兼容旧空值',
    ADD CONSTRAINT ck_quota_policy_1 CHECK (monthly_limit IS NULL OR monthly_limit >= -1);

UPDATE quota_policy SET monthly_limit = -1 WHERE monthly_limit IS NULL;

ALTER TABLE model_price_rule
    DROP CHECK ck_model_price_rule_context,
    MODIFY context_max_tokens BIGINT DEFAULT -1 COMMENT '-1 表示上下文范围没有上界；兼容旧空值',
    ADD CONSTRAINT ck_model_price_rule_context CHECK (
        context_min_tokens >= 0 AND
        (context_max_tokens IS NULL OR context_max_tokens = -1 OR context_max_tokens > context_min_tokens)
    );

UPDATE model_price_rule SET context_max_tokens = -1 WHERE context_max_tokens IS NULL;

-- 不限时长的任务没有截止时刻，不使用虚构的未来日期。
ALTER TABLE collaboration_task MODIFY deadline_at DATETIME(3) NULL;
ALTER TABLE collaboration_work_item MODIFY deadline_at DATETIME(3) NULL;
