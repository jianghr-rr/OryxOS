CREATE TABLE scheduled_tasks (
 id TEXT PRIMARY KEY, agent_name TEXT NOT NULL, definition_hash TEXT NOT NULL,
 enabled INTEGER NOT NULL CHECK(enabled IN (0,1)), configured_present INTEGER NOT NULL CHECK(configured_present IN (0,1)),
 last_execution_id TEXT REFERENCES executions(id), updated_at TEXT NOT NULL
);
CREATE INDEX scheduled_tasks_agent ON scheduled_tasks(agent_name,configured_present);
CREATE TABLE task_executions (
 id TEXT PRIMARY KEY, schedule_id TEXT NOT NULL REFERENCES scheduled_tasks(id), execution_id TEXT REFERENCES executions(id),
 trigger_kind TEXT NOT NULL CHECK(trigger_kind IN ('SCHEDULED','MANUAL')), scheduled_for TEXT,
 status TEXT NOT NULL CHECK(status IN ('DISPATCHED','SKIPPED_OVERLAP','SKIPPED_PAUSED','FAILED')),
 reason TEXT, created_at TEXT NOT NULL
);
CREATE INDEX task_executions_history ON task_executions(schedule_id,created_at,id);
CREATE INDEX task_executions_execution ON task_executions(execution_id);
