-- 原有任务保留各自的讨论起点，同一轮讨论中不同成员可以同时工作。
ALTER TABLE collaboration_room MODIFY lead_agent_id varchar(100) DEFAULT NULL;
ALTER TABLE collaboration_task
  ADD COLUMN web_topic_id varchar(100) DEFAULT NULL,
  ADD KEY ix_web_room_topic (enterprise_id,web_room_id,web_topic_id,created_at),
  ADD CONSTRAINT fk_web_task_topic FOREIGN KEY (enterprise_id,web_room_id,web_topic_id)
    REFERENCES collaboration_room_entry(enterprise_id,room_id,id);
UPDATE collaboration_task SET web_topic_id=web_origin_entry_id WHERE origin_kind='web';
