CREATE TABLE incidents (
 id TEXT PRIMARY KEY, workspace_id TEXT NOT NULL, run_id TEXT NOT NULL, detected_at TEXT NOT NULL, reason TEXT NOT NULL,
 affected_execution_ids TEXT NOT NULL, missing_record_summary TEXT NOT NULL, state TEXT NOT NULL CHECK(state IN ('OPEN','ACKNOWLEDGED')),
 acknowledged_by TEXT, acknowledged_at TEXT, recovery_notes TEXT, detail_json TEXT NOT NULL
);
CREATE INDEX incidents_workspace ON incidents(workspace_id,state,detected_at);
