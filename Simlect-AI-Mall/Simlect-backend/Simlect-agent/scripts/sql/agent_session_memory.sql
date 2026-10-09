CREATE TABLE IF NOT EXISTS agent_session_memory
(
    user_id      varchar(32)                        NOT NULL PRIMARY KEY,
    summary_json json                               NULL,
    state_json   json                               NULL,
    turn_count   int      DEFAULT 0                 NOT NULL,
    updated_at   datetime DEFAULT CURRENT_TIMESTAMP NOT NULL ON UPDATE CURRENT_TIMESTAMP
) CHARSET = utf8mb4;
