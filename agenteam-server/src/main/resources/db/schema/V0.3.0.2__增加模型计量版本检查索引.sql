ALTER TABLE model_usage_record
    ADD INDEX idx_model_usage_protocol_repair (pricing_algorithm_version, finished_at, id);
