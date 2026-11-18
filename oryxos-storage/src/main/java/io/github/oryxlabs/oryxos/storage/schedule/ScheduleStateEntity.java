package io.github.oryxlabs.oryxos.storage.schedule;

import jakarta.persistence.*;

@Entity
@Table(name = "scheduled_tasks")
public class ScheduleStateEntity {
  @Id public String id;

  @Column(name = "agent_name", nullable = false)
  public String agentName;

  @Column(name = "definition_hash", nullable = false)
  public String definitionHash;

  @Column(nullable = false)
  public Long enabled;

  @Column(name = "configured_present", nullable = false)
  public Long configuredPresent;

  @Column(name = "last_execution_id")
  public String lastExecutionId;

  @Column(name = "updated_at", nullable = false)
  public String updatedAt;
}
