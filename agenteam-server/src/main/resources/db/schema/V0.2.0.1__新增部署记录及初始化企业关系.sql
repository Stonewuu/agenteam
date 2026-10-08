CREATE TABLE `agenteam_installation`
(
  `id`                    tinyint      NOT NULL,
  `installation_id`       varchar(36)  DEFAULT NULL,
  `edition`               varchar(16)  NOT NULL DEFAULT 'community',
  `initial_enterprise_id`  varchar(100) DEFAULT NULL,
  `initialized_at`        datetime(3)  DEFAULT NULL,
  `updated_at`            datetime(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `revision`              bigint       NOT NULL DEFAULT 1,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_installation_id` (`installation_id`),
  CONSTRAINT `fk_installation_enterprise` FOREIGN KEY (`initial_enterprise_id`)
    REFERENCES `enterprise` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_installation_singleton` CHECK (`id` = 1),
  CONSTRAINT `ck_installation_edition` CHECK (`edition` IN ('community', 'pro')),
  CONSTRAINT `ck_installation_initialization` CHECK (
    (`installation_id` IS NULL AND `initialized_at` IS NULL AND `initial_enterprise_id` IS NULL)
    OR (`installation_id` IS NOT NULL AND `initialized_at` IS NOT NULL
      AND (`edition` = 'pro' OR `initial_enterprise_id` IS NOT NULL))
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 固定记录提供首次初始化的数据库锁；部署编号由初始化事务生成，不推断旧企业归属。
INSERT INTO `agenteam_installation` (`id`, `edition`) VALUES (1, 'community');
