package io.github.oryxlabs.oryxos.storage.runtime;

import jakarta.persistence.*;

@Entity
@Table(name = "incidents")
public class IncidentEntity {
  @Id
  @Column(name = "id")
  public String id;

  @Column(name = "workspace_id")
  public String workspaceId;

  @Column(name = "run_id")
  public String runId;

  @Column(name = "detected_at")
  public String detectedAt;

  @Column(name = "reason")
  public String reason;

  @Column(name = "affected_execution_ids")
  public String affectedExecutionIds;

  @Column(name = "missing_record_summary")
  public String missingRecordSummary;

  @Column(name = "state")
  public String state;

  @Column(name = "acknowledged_by")
  public String acknowledgedBy;

  @Column(name = "acknowledged_at")
  public String acknowledgedAt;

  @Column(name = "recovery_notes")
  public String recoveryNotes;

  @Column(name = "detail_json")
  public String detailJson;
}
