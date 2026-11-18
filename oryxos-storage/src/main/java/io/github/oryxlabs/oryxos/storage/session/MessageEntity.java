package io.github.oryxlabs.oryxos.storage.session;

import jakarta.persistence.*;

@Entity
@Table(name = "messages")
public class MessageEntity {
  @Id
  @Column(name = "id")
  public String id;

  @Column(name = "session_id")
  public String sessionId;

  @Column(name = "execution_id")
  public String executionId;

  @Column(name = "turn_id")
  public String turnId;

  @Column(name = "role")
  public String role;

  @Column(name = "content_json")
  public String contentJson;

  @Column(name = "provider_name")
  public String providerName;

  @Column(name = "provider_opaque_json")
  public String providerOpaqueJson;

  @Column(name = "created_at")
  public String createdAt;

  @Column(name = "seq")
  public Long seq;
}
