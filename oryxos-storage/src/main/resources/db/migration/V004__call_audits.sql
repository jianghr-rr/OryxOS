CREATE TABLE llm_calls (
 id TEXT PRIMARY KEY, execution_id TEXT NOT NULL REFERENCES executions(id), iteration INTEGER NOT NULL, attempt INTEGER NOT NULL,
 provider TEXT NOT NULL, model TEXT NOT NULL, requested_model TEXT NOT NULL, status TEXT NOT NULL,
 started_at TEXT NOT NULL, finished_at TEXT, duration_ms INTEGER, input_tokens INTEGER, output_tokens INTEGER, error_code TEXT,
 UNIQUE(execution_id,iteration,attempt)
);
CREATE TABLE tool_invocations (
 id TEXT PRIMARY KEY, execution_id TEXT NOT NULL REFERENCES executions(id), tool_call_id TEXT NOT NULL, tool_name TEXT NOT NULL,
 attempt INTEGER NOT NULL, status TEXT NOT NULL, args_redacted TEXT, result_redacted TEXT, side_effect_state TEXT NOT NULL,
 resolution_status TEXT NOT NULL, correction_of_call_id TEXT, resolved_by_call_id TEXT,
 started_at TEXT NOT NULL, finished_at TEXT, duration_ms INTEGER, error_code TEXT,
 UNIQUE(execution_id,tool_call_id,attempt)
);
