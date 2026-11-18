package io.github.oryxlabs.oryxos.web.api;

import io.github.oryxlabs.oryxos.core.agent.AgentService;
import java.time.Instant;
import java.util.UUID;

/**
 * Boot composes these operations from core ports and storage queries; web has no storage
 * dependency.
 */
public interface WebRuntimePort {
  ContractDtos.Session create(String agent, String user);

  ContractDtos.SessionPage sessions(
      int limit, String cursor, String agent, String user, String channel, Boolean archived);

  ContractDtos.SessionHistory history(UUID id, int limit, Long before);

  ContractDtos.Session archive(UUID id, String user);

  AgentService.Handle message(
      UUID session,
      String user,
      String content,
      Instant entered,
      long nanos,
      java.util.function.Consumer<AgentService.Handle> observer);

  AgentService.Handle invoke(
      String agent,
      String user,
      String content,
      Instant entered,
      long nanos,
      java.util.function.Consumer<AgentService.Handle> observer);

  ContractDtos.AgentPage profiles(int limit, String cursor);

  ContractDtos.MemoryView memory(int limit, String cursor);

  ContractDtos.ToolPage tools(String agent, int limit, String cursor);

  ContractDtos.ExecutionPage executions(int limit, String cursor, String agent, UUID session);

  ContractDtos.Execution execution(UUID id);

  ContractDtos.Health health();

  ContractDtos.Info info();

  ContractDtos.SchedulePage schedules(int limit, String cursor, String agent);

  ContractDtos.Schedule enabled(String id, boolean enabled);

  AgentService.Handle run(
      String id,
      String user,
      Instant entered,
      long nanos,
      java.util.function.Consumer<AgentService.Handle> observer);

  ContractDtos.ScheduleEventPage scheduleHistory(String id, int limit, String cursor);

  ContractDtos.RuntimeState state();

  ContractDtos.RuntimeState recover(ContractDtos.RecoverInput input, String user);
}
