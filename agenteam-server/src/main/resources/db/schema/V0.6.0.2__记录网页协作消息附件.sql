CREATE TABLE collaboration_room_entry_file (
  id varchar(100) NOT NULL PRIMARY KEY,
  enterprise_id varchar(100) NOT NULL,
  room_id varchar(100) NOT NULL,
  entry_id varchar(100) NOT NULL,
  file_id varchar(100) NOT NULL,
  ordinal int NOT NULL,
  sha256 char(64) NOT NULL,
  UNIQUE KEY uk_room_file_position (enterprise_id,entry_id,ordinal),
  UNIQUE KEY uk_room_file_entry (enterprise_id,entry_id,file_id),
  KEY ix_room_file_usage (enterprise_id,file_id),
  CONSTRAINT fk_room_file_entry FOREIGN KEY (enterprise_id,room_id,entry_id)
    REFERENCES collaboration_room_entry (enterprise_id,room_id,id),
  CONSTRAINT fk_room_file_upload FOREIGN KEY (enterprise_id,file_id) REFERENCES file_object (enterprise_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
