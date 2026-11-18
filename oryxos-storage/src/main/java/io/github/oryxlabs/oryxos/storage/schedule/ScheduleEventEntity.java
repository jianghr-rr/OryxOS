package io.github.oryxlabs.oryxos.storage.schedule;

import jakarta.persistence.*;

@Entity
@Table(name = "task_executions")
public class ScheduleEventEntity {
  @Id public String id;

  @Column(name = "schedule_id", nullable = false)
  public String scheduleId;

  @Column(name = "execution_id")
  public String executionId;

  @Column(name = "trigger_kind", nullable = false)
  public String triggerKind;

  @Column(name = "scheduled_for")
  public String scheduledFor;

  @Column(nullable = false)
  public String status;

  public String reason;

  @Column(name = "created_at", nullable = false)
  public String createdAt;
}
