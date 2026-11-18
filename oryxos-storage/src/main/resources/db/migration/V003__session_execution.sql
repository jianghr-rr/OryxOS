CREATE TABLE sessions (
 id TEXT PRIMARY KEY, workspace_id TEXT NOT NULL, channel TEXT NOT NULL, user_id TEXT NOT NULL,
 agent_name TEXT NOT NULL, provider_name TEXT NOT NULL CHECK(length(provider_name)>0), model TEXT NOT NULL CHECK(length(model)>0),
 kind TEXT NOT NULL CHECK(kind IN ('CONVERSATION','ONE_SHOT')), status TEXT NOT NULL CHECK(status IN ('ACTIVE','ARCHIVED')),
 created_at TEXT NOT NULL, archived_at TEXT, version INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX sessions_identity ON sessions(workspace_id,channel,user_id,agent_name);
CREATE TABLE executions (
 id TEXT PRIMARY KEY, session_id TEXT NOT NULL REFERENCES sessions(id), turn_id TEXT NOT NULL UNIQUE,
 agent_name TEXT NOT NULL, channel TEXT NOT NULL, user_id TEXT NOT NULL, trigger_type TEXT NOT NULL, schedule_id TEXT,
 config_revision TEXT NOT NULL, started_at TEXT NOT NULL, deadline_at TEXT NOT NULL, finished_at TEXT,
 status TEXT NOT NULL CHECK(status IN ('RUNNING','SUCCEEDED','PARTIAL','FAILED','TIMED_OUT','PARTIAL_RECORDING_FAILURE','INTERRUPTED')),
 stop_reason TEXT, recording_status TEXT NOT NULL CHECK(recording_status IN ('COMPLETE','INCOMPLETE','UNKNOWN')),
 in_flight_count INTEGER NOT NULL CHECK(in_flight_count>=0), final_content TEXT, error_code TEXT,
 has_tool_errors INTEGER NOT NULL, has_unresolved_tool_errors INTEGER NOT NULL, timezone TEXT NOT NULL,
 tool_name_map_json TEXT NOT NULL, turn_complete INTEGER NOT NULL DEFAULT 0,
 UNIQUE(id,session_id,turn_id)
);
CREATE INDEX executions_session ON executions(session_id,started_at);
CREATE INDEX executions_schedule ON executions(schedule_id,started_at);
CREATE INDEX executions_status ON executions(status,recording_status);
CREATE TABLE messages (
 id TEXT PRIMARY KEY, session_id TEXT NOT NULL REFERENCES sessions(id), execution_id TEXT NOT NULL,
 turn_id TEXT NOT NULL, seq INTEGER NOT NULL CHECK(seq>0), role TEXT NOT NULL CHECK(role IN ('USER','ASSISTANT','TOOL')),
 content_json TEXT NOT NULL, provider_name TEXT NOT NULL, provider_opaque_json TEXT, created_at TEXT NOT NULL,
 UNIQUE(session_id,seq), FOREIGN KEY(execution_id,session_id,turn_id) REFERENCES executions(id,session_id,turn_id)
);
CREATE TABLE workspace_state (
 workspace_id TEXT PRIMARY KEY, gate_state TEXT NOT NULL, incident_id TEXT, updated_at TEXT NOT NULL
);
