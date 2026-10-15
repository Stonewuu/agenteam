-- 复用一次性授权记录保存独立聊天配对；原 OAuth 绑定和登录条件保持不变。
ALTER TABLE channel_oauth_session
  ADD COLUMN pairing_attempts int NOT NULL DEFAULT 0,
  DROP CHECK ck_channel_oauth_purpose,
  ADD CONSTRAINT ck_channel_oauth_purpose CHECK (purpose IN ('bind', 'login', 'chat_pairing')),
  DROP CHECK ck_channel_oauth_initiator,
  ADD CONSTRAINT ck_channel_oauth_initiator CHECK (
    (purpose IN ('bind', 'chat_pairing') AND initiator_user_id IS NOT NULL AND initiator_session_version IS NOT NULL)
    OR (purpose = 'login' AND initiator_user_id IS NULL AND initiator_session_version IS NULL)
  ),
  ADD CONSTRAINT ck_channel_pairing_attempts CHECK (pairing_attempts BETWEEN 0 AND 5);

ALTER TABLE chat_inbound_receipt ADD COLUMN payload_purged_at datetime(3) DEFAULT NULL;
ALTER TABLE chat_message ADD COLUMN content_purged_at datetime(3) DEFAULT NULL;
