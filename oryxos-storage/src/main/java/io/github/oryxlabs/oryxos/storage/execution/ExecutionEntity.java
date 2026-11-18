package io.github.oryxlabs.oryxos.storage.execution;

import jakarta.persistence.*;

@Entity
@Table(name = "executions")
public class ExecutionEntity {
  @Id
  @Column(name = "id")
  public String id;

  @Column(name = "session_id")
  public String sessionId;

  @Column(name = "turn_id")
  public String turnId;

  @Column(name = "agent_name")
  public String agentName;

  @Column(name = "channel")
  public String channel;

  @Column(name = "user_id")
  public String userId;

  @Column(name = "trigger_type")
  public String triggerType;

  @Column(name = "schedule_id")
  public String scheduleId;

  @Column(name = "config_revision")
  public String configRevision;

  @Column(name = "started_at")
  public String startedAt;

  @Column(name = "deadline_at")
  public String deadlineAt;

  @Column(name = "finished_at")
  public String finishedAt;

  @Column(name = "status")
  public String status;

  @Column(name = "stop_reason")
  public String stopReason;

  @Column(name = "recording_status")
  public String recordingStatus;

  @Column(name = "final_content")
  public String finalContent;

  @Column(name = "error_code")
  public String errorCode;

  @Column(name = "timezone")
  public String timezone;

  @Column(name = "tool_name_map_json")
  public String toolNameMapJson;

  @Column(name = "in_flight_count")
  public Long inFlightCount;

  @Column(name = "has_tool_errors")
  public Long hasToolErrors;

  @Column(name = "has_unresolved_tool_errors")
  public Long hasUnresolvedToolErrors;

  @Column(name = "turn_complete")
  public Long turnComplete;
}
