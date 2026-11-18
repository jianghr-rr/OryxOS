package io.github.oryxlabs.oryxos.storage.session;

import jakarta.persistence.*;

@Entity
@Table(name = "sessions")
public class SessionEntity {
  @Id
  @Column(name = "id")
  public String id;

  @Column(name = "workspace_id", updatable = false)
  public String workspaceId;

  @Column(name = "channel", updatable = false)
  public String channel;

  @Column(name = "user_id", updatable = false)
  public String userId;

  @Column(name = "agent_name", updatable = false)
  public String agentName;

  @Column(name = "provider_name", updatable = false)
  public String providerName;

  @Column(name = "model", updatable = false)
  public String model;

  @Column(name = "kind", updatable = false)
  public String kind;

  @Column(name = "status")
  public String status;

  @Column(name = "created_at", updatable = false)
  public String createdAt;

  @Column(name = "archived_at")
  public String archivedAt;

  @Version
  @Column(name = "version")
  public Long version;
}
