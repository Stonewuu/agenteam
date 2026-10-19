ALTER TABLE model_provider
    ADD COLUMN deleted_at datetime(3) NULL COMMENT '删除时间；保留历史调用关联',
    ADD COLUMN active_name varchar(80) GENERATED ALWAYS AS
        (CASE WHEN deleted_at IS NULL THEN name ELSE NULL END) STORED COMMENT '未删除配置的名称，用于唯一约束',
    DROP INDEX uk_model_provider_1,
    ADD UNIQUE KEY uk_model_provider_active_name (enterprise_id, active_name);

ALTER TABLE model_profile
    ADD COLUMN deleted_at datetime(3) NULL COMMENT '删除时间；保留历史用量和价格关联',
    ADD COLUMN active_name varchar(80) GENERATED ALWAYS AS
        (CASE WHEN deleted_at IS NULL THEN name ELSE NULL END) STORED COMMENT '未删除配置的名称，用于唯一约束',
    DROP INDEX uk_model_profile_1,
    ADD UNIQUE KEY uk_model_profile_active_name (enterprise_id, active_name),
    COMMENT = '管理员维护的模型配置；删除后保留历史关联';
