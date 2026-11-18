CREATE TABLE memory_entries (
 id TEXT PRIMARY KEY NOT NULL,
 workspace_id TEXT NOT NULL,
 scope TEXT NOT NULL CHECK(scope IN ('CORE','ARCHIVAL')),
 raw_content TEXT NOT NULL,
 created_at TEXT NOT NULL,
 source_execution_id TEXT NOT NULL,
 source_agent_name TEXT NOT NULL,
 backend TEXT NOT NULL CHECK(backend IN ('md','sqlite','mem0')),
 write_status TEXT NOT NULL CHECK(write_status IN ('PREPARED','CONFIRMED','UNKNOWN','FAILED')),
 UNIQUE(id, workspace_id)
);
CREATE INDEX memory_entries_workspace_backend_scope ON memory_entries(workspace_id,backend,scope,created_at,id);
CREATE TABLE memory_remote_links (
 source_entry_id TEXT NOT NULL,
 remote_id TEXT NOT NULL,
 workspace_id TEXT NOT NULL,
 verified_at TEXT NOT NULL,
 PRIMARY KEY(source_entry_id,remote_id,workspace_id),
 FOREIGN KEY(source_entry_id,workspace_id) REFERENCES memory_entries(id,workspace_id)
);
