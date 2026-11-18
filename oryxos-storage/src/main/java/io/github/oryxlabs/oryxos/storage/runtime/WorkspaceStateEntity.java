package io.github.oryxlabs.oryxos.storage.runtime;

import jakarta.persistence.*;

@Entity
@Table(name = "workspace_state")
public class WorkspaceStateEntity {
  @Id
  @Column(name = "workspace_id")
  public String workspaceId;

  @Column(name = "gate_state")
  public String gateState;

  @Column(name = "incident_id")
  public String incidentId;

  @Column(name = "updated_at")
  public String updatedAt;
}
