-- 用户明确回复本人上传的群文件后，准备附件期间不重新触发原文件消息的任务路由。
ALTER TABLE chat_message
  DROP CHECK ck_chat_message_routing_status,
  ADD CONSTRAINT ck_chat_message_routing_status CHECK (
    routing_status IN ('pending', 'awaiting_binding', 'awaiting_clarification', 'awaiting_reference', 'routed', 'ignored', 'failed')
  );
