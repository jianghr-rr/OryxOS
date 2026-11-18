package io.github.oryxlabs.oryxos.storage.audit;

import jakarta.persistence.*;

@Entity
@Table(name = "tool_invocations")
public class ToolInvocationEntity {
  @Id
  @Column(name = "id")
  public String id;

  @Column(name = "execution_id")
  public String executionId;

  @Column(name = "tool_call_id")
  public String toolCallId;

  @Column(name = "tool_name")
  public String toolName;

  @Column(name = "status")
  public String status;

  @Column(name = "args_redacted")
  public String argsRedacted;

  @Column(name = "result_redacted")
  public String resultRedacted;

  @Column(name = "side_effect_state")
  public String sideEffectState;

  @Column(name = "resolution_status")
  public String resolutionStatus;

  @Column(name = "correction_of_call_id")
  public String correctionOfCallId;

  @Column(name = "resolved_by_call_id")
  public String resolvedByCallId;

  @Column(name = "started_at")
  public String startedAt;

  @Column(name = "finished_at")
  public String finishedAt;

  @Column(name = "error_code")
  public String errorCode;

  @Column(name = "attempt")
  public Long attempt;

  @Column(name = "duration_ms")
  public Long durationMs;
}
