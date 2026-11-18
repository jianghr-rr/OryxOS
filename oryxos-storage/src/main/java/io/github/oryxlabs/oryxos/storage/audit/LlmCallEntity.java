package io.github.oryxlabs.oryxos.storage.audit;

import jakarta.persistence.*;

@Entity
@Table(name = "llm_calls")
public class LlmCallEntity {
  @Id
  @Column(name = "id")
  public String id;

  @Column(name = "execution_id")
  public String executionId;

  @Column(name = "provider")
  public String provider;

  @Column(name = "model")
  public String model;

  @Column(name = "requested_model")
  public String requestedModel;

  @Column(name = "status")
  public String status;

  @Column(name = "started_at")
  public String startedAt;

  @Column(name = "finished_at")
  public String finishedAt;

  @Column(name = "error_code")
  public String errorCode;

  @Column(name = "iteration")
  public Long iteration;

  @Column(name = "attempt")
  public Long attempt;

  @Column(name = "duration_ms")
  public Long durationMs;

  @Column(name = "input_tokens")
  public Long inputTokens;

  @Column(name = "output_tokens")
  public Long outputTokens;
}
