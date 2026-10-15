-- 企微使用长连接发送，网络异常也可能没有 HTTP 状态，不能用虚构数字阻止保存未知结果。
ALTER TABLE chat_outbound_attempt MODIFY COLUMN http_status int DEFAULT NULL;
UPDATE chat_outbound_attempt SET http_status = NULL WHERE http_status = 0;
