ALTER TABLE feishu_setup_job
  DROP CHECK ck_feishu_setup_kind,
  ADD CONSTRAINT ck_feishu_setup_kind CHECK (kind IN ('management','enterprise','bot')),
  ADD COLUMN connection_revision bigint DEFAULT NULL,
  ADD COLUMN credential_revision bigint DEFAULT NULL,
  ADD COLUMN active_connection_id varchar(100) GENERATED ALWAYS AS
    (CASE WHEN stage IN ('completed','cancelled','expired','failed','registration_unknown') THEN NULL ELSE connection_id END) STORED,
  ADD UNIQUE KEY uk_feishu_setup_connection (enterprise_id, active_connection_id);

UPDATE feishu_setup_job j JOIN enterprise_integration c ON c.id = j.connection_id AND c.enterprise_id = j.enterprise_id
SET j.connection_revision = c.revision, j.credential_revision = c.credential_revision;

CREATE TABLE feishu_management_connection (
  connection_id varchar(100) NOT NULL,
  enterprise_id varchar(100) NOT NULL,
  credential_revision bigint NOT NULL,
  verified_at datetime(3) NOT NULL,
  PRIMARY KEY (connection_id),
  KEY ix_feishu_management_enterprise (enterprise_id),
  CONSTRAINT fk_feishu_management_connection FOREIGN KEY (enterprise_id, connection_id) REFERENCES enterprise_integration(enterprise_id, id),
  CONSTRAINT ck_feishu_management_credential CHECK (credential_revision > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='已核对发布权限的飞书企业连接';
