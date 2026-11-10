ALTER TABLE collaboration_room_entry
  ADD COLUMN delivery_state varchar(16) NOT NULL DEFAULT 'visible',
  ADD COLUMN recipient_agent_id varchar(100) DEFAULT NULL,
  ADD COLUMN queue_position bigint DEFAULT NULL,
  ADD KEY ix_room_member_queue (enterprise_id,room_id,recipient_agent_id,delivery_state,queue_position),
  ADD KEY ix_room_waiting_entries (delivery_state,updated_at,id),
  ADD CONSTRAINT ck_room_entry_delivery CHECK (delivery_state IN ('visible','queued','selected','starting','withdrawn','failed')),
  ADD CONSTRAINT fk_room_entry_recipient FOREIGN KEY (enterprise_id,room_id,recipient_agent_id)
    REFERENCES collaboration_room_member(enterprise_id,room_id,agent_id);
