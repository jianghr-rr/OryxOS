package io.github.oryxlabs.oryxos.boot.runtime;

import io.github.oryxlabs.oryxos.boot.config.*;
import io.github.oryxlabs.oryxos.core.agent.*;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.execution.*;
import io.github.oryxlabs.oryxos.core.memory.*;
import io.github.oryxlabs.oryxos.core.runtime.*;
import io.github.oryxlabs.oryxos.core.schedule.*;
import io.github.oryxlabs.oryxos.core.session.*;
import io.github.oryxlabs.oryxos.core.tool.*;
import io.github.oryxlabs.oryxos.core.workspace.*;
import io.github.oryxlabs.oryxos.storage.query.SqliteReadOnlyQueries;
import io.github.oryxlabs.oryxos.web.api.*;
import java.time.Instant;
import java.util.*;

/** Composition at the outermost module: no transport logic or second execution engine. */
public final class RuntimeHttpService implements WebRuntimePort {
  private final WorkspaceHandle workspace;
  private final RuntimeConfiguration.LoadedRuntime loaded;
  private final ToolConfiguration.CheckedAgents catalog;
  private final AgentRuntimeConfiguration.RuntimeAgents agents;
  private final SessionManager sessions;
  private final ExecutionStore executions;
  private final MemoryConfiguration.SelectedMemory memory;
  private final ToolRegistryPort tools;
  private final ScheduleCoordinator schedules;
  private final RecoveryPort recovery;
  private final RuntimeGate gate;
  private final SqliteReadOnlyQueries queries;
  private final PublicDtoMapper dto;

  public RuntimeHttpService(
      WorkspaceHandle workspace,
      RuntimeConfiguration.LoadedRuntime loaded,
      ToolConfiguration.CheckedAgents catalog,
      AgentRuntimeConfiguration.RuntimeAgents agents,
      SessionManager sessions,
      ExecutionStore executions,
      MemoryConfiguration.SelectedMemory memory,
      ToolRegistryPort tools,
      ScheduleCoordinator schedules,
      RecoveryPort recovery,
      RuntimeGate gate,
      PublicDtoMapper dto) {
    this.workspace = workspace;
    this.loaded = loaded;
    this.catalog = catalog;
    this.agents = agents;
    this.sessions = sessions;
    this.executions = executions;
    this.memory = memory;
    this.tools = tools;
    this.schedules = schedules;
    this.recovery = recovery;
    this.gate = gate;
    this.dto = dto;
    this.queries = new SqliteReadOnlyQueries(workspace);
  }

  private void ready() {
    if (gate.state() != RuntimeGate.State.READY)
      throw new OryxError("EXECUTION_PAUSED", "Workspace is not ready; inspect runtime state");
  }

  private AgentCatalog catalog() {
    if (catalog.catalog() == null)
      throw new OryxError("INVALID_CONFIG", "Runtime configuration is unavailable");
    return catalog.catalog();
  }

  private AgentProfile agent(String name) {
    if (catalog().entries().stream().noneMatch(e -> e.name().equals(name)))
      throw new OryxError("NOT_FOUND", "Agent does not exist");
    return catalog().require(name);
  }

  private Session session(UUID id) {
    var s = queries.session(id);
    if (s == null) throw new OryxError("NOT_FOUND", "Session does not exist");
    return s;
  }

  public ContractDtos.Session create(String name, String user) {
    ready();
    try (var lease = sessions.acquire(null, "http", user, agent(name), Session.Kind.CONVERSATION)) {
      return dto.session(lease.session());
    }
  }

  public ContractDtos.SessionPage sessions(
      int limit, String cursor, String agent, String user, String channel, Boolean archived) {
    if (channel != null && !Set.of("http", "cli", "scheduler").contains(channel))
      throw RequestLimits.invalid();
    String scope = Arrays.asList("sessions", agent, user, channel, archived).toString();
    String after = RequestLimits.cursor(cursor, scope);
    var rows =
        queries.sessionPage(
            limit,
            after == null ? null : RequestLimits.uuid(after),
            agent,
            user,
            channel,
            archived);
    boolean more = rows.size() > limit;
    var items = more ? rows.subList(0, limit) : rows;
    return new ContractDtos.SessionPage(
        items.stream().map(dto::session).toList(),
        more ? RequestLimits.encode(scope, items.getLast().id().toString()) : null);
  }

  public ContractDtos.SessionHistory history(UUID id, int limit, Long before) {
    var s = session(id);
    var rows = queries.history(id, before, limit);
    Long next =
        rows.isEmpty() || queries.history(id, rows.getFirst().seq(), 1).isEmpty()
            ? null
            : rows.getFirst().seq();
    return new ContractDtos.SessionHistory(
        dto.session(s), rows.stream().map(dto::message).toList(), next);
  }

  public ContractDtos.Session archive(UUID id, String user) {
    var s = session(id);
    sessions.archive(id, "http", user, s.agentName());
    return dto.session(session(id));
  }

  public AgentService.Handle message(
      UUID id,
      String user,
      String content,
      Instant entered,
      long nanos,
      java.util.function.Consumer<AgentService.Handle> observer) {
    ready();
    var s = RuntimeTiming.call("http.session.lookup", () -> sessions.lookup(id));
    if (s == null) throw new OryxError("NOT_FOUND", "Session does not exist");
    agent(s.agentName());
    return agents
        .require()
        .start(
            ProcessRequest.manual("http", user, s.agentName(), id, content),
            x -> {},
            () -> {},
            entered,
            nanos,
            observer);
  }

  public AgentService.Handle invoke(
      String name,
      String user,
      String content,
      Instant entered,
      long nanos,
      java.util.function.Consumer<AgentService.Handle> observer) {
    ready();
    agent(name);
    return agents
        .require()
        .start(
            new ProcessRequest(
                "http", user, name, null, content, ProcessRequest.Trigger.MANUAL, null, true),
            x -> {},
            () -> {},
            entered,
            nanos,
            observer);
  }

  public ContractDtos.AgentPage profiles(int limit, String cursor) {
    var p =
        RequestLimits.page(
            catalog().entries(), limit, cursor, "profiles", AgentCatalog.Entry::name);
    return new ContractDtos.AgentPage(
        p.items().stream().map(this::profile).toList(), p.nextCursor());
  }

  private ContractDtos.Agent profile(AgentCatalog.Entry e) {
    if (!e.available())
      return new ContractDtos.Agent(
          dto.safe(e.name()),
          "",
          "UNAVAILABLE",
          "",
          "",
          List.of(),
          List.of(),
          List.of(),
          "",
          "",
          dto.safe(e.error().safeMessage()));
    var p = e.profile();
    String body = "", error = null;
    try {
      var real = p.definition().toRealPath();
      if (!real.equals(p.definition())
          || !real.startsWith(workspace.root().toRealPath().resolve("agents")))
        throw new IllegalStateException();
      var definition = new AgentDefinitionReader().read(real);
      body =
          HexFormat.of()
              .formatHex(
                  java.security.MessageDigest.getInstance("SHA-256")
                      .digest(definition.body().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (Exception ex) {
      error = "Agent body is unavailable; loaded configuration retained";
    }
    return new ContractDtos.Agent(
        dto.safe(p.name()),
        dto.safe(p.description()),
        error == null ? "AVAILABLE" : "UNAVAILABLE",
        dto.safe(p.providerName()),
        dto.safe(p.model()),
        p.tools().stream().map(dto::safe).toList(),
        p.notifyChannelNames().stream().sorted().map(dto::safe).toList(),
        p.schedules().stream().map(s -> dto.safe(p.name() + ":" + s.id())).toList(),
        p.configRevision(),
        body,
        error);
  }

  public ContractDtos.MemoryView memory(int limit, String cursor) {
    var store = memory.store();
    if (store == null)
      throw new OryxError("MEMORY_UNAVAILABLE", "Configured memory backend is unavailable");
    var core = store.browse(MemoryEntry.Scope.CORE);
    var archive =
        RequestLimits.page(
            store.browse(MemoryEntry.Scope.ARCHIVAL),
            limit,
            cursor,
            "memory:" + store.backend(),
            m -> m.id().toString());
    return new ContractDtos.MemoryView(
        store.backend(),
        core.stream().map(dto::memory).toList(),
        archive.items().stream().map(dto::memory).toList(),
        archive.nextCursor(),
        true);
  }

  public ContractDtos.ToolPage tools(String name, int limit, String cursor) {
    var all = name == null ? tools.all() : tools.available(agent(name));
    var p = RequestLimits.page(all, limit, cursor, "tools:" + name, ToolDescriptor::name);
    return new ContractDtos.ToolPage(
        p.items().stream()
            .map(
                d ->
                    new ContractDtos.Tool(
                        d.name(),
                        dto.safe(d.description()),
                        d.source(),
                        true,
                        dto.schema(d.inputSchemaJson()),
                        d.safeToRetry()))
            .toList(),
        p.nextCursor());
  }

  public ContractDtos.ExecutionPage executions(
      int limit, String cursor, String agent, UUID session) {
    if (session != null) session(session);
    String scope = Arrays.asList("executions", agent, session).toString();
    String after = RequestLimits.cursor(cursor, scope);
    var rows =
        queries.executionPage(
            limit, after == null ? null : RequestLimits.uuid(after), agent, session);
    boolean more = rows.size() > limit;
    var items = more ? rows.subList(0, limit) : rows;
    return new ContractDtos.ExecutionPage(
        items.stream().map(this::execution).toList(),
        more ? RequestLimits.encode(scope, items.getLast().toString()) : null);
  }

  public ContractDtos.Execution execution(UUID id) {
    var active = agents.service() == null ? null : agents.service().activeSnapshot(id);
    var e = active == null ? executions.find(id) : active;
    if (e == null) throw new OryxError("NOT_FOUND", "Execution does not exist");
    return dto.execution(
        e,
        queries.modelAttempts(id).stream().map(dto::llm).toList(),
        queries.toolAttempts(id).stream().map(dto::tool).toList());
  }

  public ContractDtos.Health health() {
    var s = recovery.state();
    return new ContractDtos.Health(true, s.state().equals("READY"), s.state());
  }

  public ContractDtos.Info info() {
    var config = loaded.config();
    if (config == null)
      throw new OryxError("INVALID_CONFIG", "Runtime configuration is unavailable");
    return new ContractDtos.Info(
        "1.0.0-SNAPSHOT",
        WorkspaceInitializer.readId(workspace),
        config.providers().keySet().stream().sorted().toList(),
        catalog().entries().size(),
        tools.all().size(),
        config.memoryBackend(),
        config.timezone().getId());
  }

  public ContractDtos.SchedulePage schedules(int limit, String cursor, String agent) {
    var p =
        RequestLimits.page(
            schedules.list().stream()
                .filter(v -> agent == null || v.state().agentName().equals(agent))
                .toList(),
            limit,
            cursor,
            "schedules:" + agent,
            v -> v.state().id());
    return new ContractDtos.SchedulePage(
        p.items().stream().map(dto::schedule).toList(), p.nextCursor());
  }

  public ContractDtos.Schedule enabled(String id, boolean enabled) {
    schedules.setEnabled(id, enabled);
    return schedules.list().stream()
        .filter(v -> v.state().id().equals(id))
        .map(dto::schedule)
        .findFirst()
        .orElseThrow(() -> new OryxError("NOT_FOUND", "Schedule does not exist"));
  }

  public AgentService.Handle run(
      String id,
      String user,
      Instant entered,
      long nanos,
      java.util.function.Consumer<AgentService.Handle> observer) {
    ready();
    var result = schedules.manual(id, "http", user, entered, nanos, observer);
    if (result.execution() == null)
      throw new OryxError(
          result.event().reason(), "Schedule was not dispatched; inspect its history");
    return result.execution();
  }

  public ContractDtos.ScheduleEventPage scheduleHistory(String id, int limit, String cursor) {
    String scope = "events:" + id;
    String after = RequestLimits.cursor(cursor, scope);
    var rows = schedules.history(id, limit, after == null ? null : RequestLimits.uuid(after));
    String next =
        rows.isEmpty() || schedules.history(id, 1, rows.getLast().id()).isEmpty()
            ? null
            : RequestLimits.encode(scope, rows.getLast().id().toString());
    return new ContractDtos.ScheduleEventPage(rows.stream().map(dto::event).toList(), next);
  }

  public ContractDtos.RuntimeState state() {
    return dto.runtime(recovery.state());
  }

  public ContractDtos.RuntimeState recover(ContractDtos.RecoverInput input, String user) {
    try {
      return dto.runtime(
          recovery.recover(
              new RecoveryPort.Request(
                  input.incidentId(),
                  Boolean.TRUE.equals(input.acknowledgeMissingRecords()),
                  input.notes(),
                  user)));
    } catch (OryxError e) {
      if (e.code().equals("STORAGE_UNAVAILABLE"))
        throw new OryxError("RECOVERY_UNAVAILABLE", e.getMessage());
      throw e;
    }
  }
}
