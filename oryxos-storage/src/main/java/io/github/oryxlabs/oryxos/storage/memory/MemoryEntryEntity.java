package io.github.oryxlabs.oryxos.storage.memory;

import jakarta.persistence.*;

@Entity(name = "MemoryEntryEntity")
@Table(name = "memory_entries")
public class MemoryEntryEntity {
  @Id String id;

  @Column(name = "workspace_id", nullable = false)
  String workspaceId;

  @Column(nullable = false)
  String scope;

  @Column(name = "raw_content", nullable = false)
  String rawContent;

  @Column(name = "created_at", nullable = false)
  String createdAt;

  @Column(name = "source_execution_id", nullable = false)
  String sourceExecutionId;

  @Column(name = "source_agent_name", nullable = false)
  String sourceAgentName;

  @Column(nullable = false)
  String backend;

  @Column(name = "write_status", nullable = false)
  String writeStatus;

  public MemoryEntryEntity() {}
}
