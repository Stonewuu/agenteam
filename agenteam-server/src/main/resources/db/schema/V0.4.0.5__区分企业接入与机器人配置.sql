ALTER TABLE enterprise_integration
  ADD COLUMN purpose varchar(32) NOT NULL DEFAULT 'enterprise',
  ADD CONSTRAINT ck_integration_purpose CHECK (purpose IN ('enterprise', 'chat_bot')),
  ADD KEY ix_integration_purpose (enterprise_id, purpose, provider_code, status, created_at, id);

-- 保留已有登录和个人通知，只有明确的机器人专用配置改归机器人管理。
UPDATE enterprise_integration i
LEFT JOIN chat_bot_binding b ON b.enterprise_id = i.enterprise_id AND b.connection_id = i.id AND b.status <> 'deleted'
SET i.purpose = 'chat_bot'
WHERE i.provider_code = 'wecom_aibot'
   OR (b.id IS NOT NULL AND i.login_enabled = 0 AND i.messaging_enabled = 0);

ALTER TABLE enterprise_integration
  ADD CONSTRAINT ck_integration_bot_purpose CHECK (purpose <> 'chat_bot' OR (login_enabled = 0 AND messaging_enabled = 0));
