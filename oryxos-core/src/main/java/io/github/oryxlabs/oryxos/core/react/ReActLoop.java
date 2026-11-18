package io.github.oryxlabs.oryxos.core.react;

import io.github.oryxlabs.oryxos.core.context.PromptBuilder;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.execution.Execution;
import io.github.oryxlabs.oryxos.core.execution.ExecutionContext;
import io.github.oryxlabs.oryxos.core.execution.RecordingScope;
import io.github.oryxlabs.oryxos.core.memory.MemoryPort;
import io.github.oryxlabs.oryxos.core.model.*;
import io.github.oryxlabs.oryxos.core.provider.ProviderPort;
import io.github.oryxlabs.oryxos.core.provider.ProviderReply;
import io.github.oryxlabs.oryxos.core.runtime.RuntimeTiming;
import io.github.oryxlabs.oryxos.core.session.SessionManager;
import io.github.oryxlabs.oryxos.core.tool.*;
import java.util.*;

public final class ReActLoop {
  private final ProviderPort provider;
  private final ToolRegistryPort registry;
  private final ToolExecutor tools;
  private final MemoryPort memory;
  private final PromptBuilder prompts;

  public ReActLoop(
      ProviderPort provider,
      ToolRegistryPort registry,
      ToolExecutor tools,
      MemoryPort memory,
      PromptBuilder prompts) {
    this.provider = provider;
    this.registry = registry;
    this.tools = tools;
    this.memory = memory;
    this.prompts = prompts;
  }

  public void run(ExecutionContext ctx, SessionManager.Lease session) {
    var corrections = new CorrectionTracker();
    int completed = 0, modelReplies = 0;
    var seen = new HashSet<String>();
    try {
      int limit = ctx.agent().maxIterations();
      if (limit < 1 || limit > 100) throw OryxError.configuration("agent max_iterations");
      for (int iteration = 1; iteration <= limit; iteration++) {
        ctx.dispatcher().check();
        final int roundNumber = iteration;
        var request =
            RuntimeTiming.call(
                "react.prompt",
                () ->
                    prompts.build(
                        ctx, memory.load(ctx), registry.available(ctx.agent()), roundNumber));
        ProviderReply reply;
        try (var postModel = RecordingScope.coalesce()) {
          try {
            reply =
                RuntimeTiming.call(
                    "react.provider", () -> ctx.dispatcher().call(() -> provider.call(request)));
            modelReplies++;
            if (ctx.deadline().expired()) {
              postModel.flush();
              ctx.expire();
              return;
            }
            ctx.dispatcher().check();
            var assistant = reply.assistantMessage();
            if (assistant == null || assistant.role() != ChatMessage.Role.ASSISTANT)
              throw new OryxError("PROVIDER_PROTOCOL", "Expected an assistant reply");
            for (var call : reply.toolCalls())
              if (call.id() == null || !seen.add(call.id()))
                throw new OryxError("PROVIDER_PROTOCOL", "Duplicate or missing tool call id");
            session.append(ctx.execution().id(), ctx.execution().turnId(), assistant, null);
            if (reply.toolCalls().isEmpty()) {
              if (assistant.content() == null || assistant.content().isBlank())
                throw new OryxError("PROVIDER_PROTOCOL", "Empty assistant result");
              String content = ctx.redactor().redact(assistant.content());
              if (corrections.unresolved()) content = "仍有未解决的工具错误；模型文字不能证明动作成功。\n" + content;
              else if (corrections.hadErrors()) content = "成功；过程中有已纠正的工具错误。\n" + content;
              ctx.execution()
                  .finish(
                      corrections.unresolved()
                          ? Execution.Status.PARTIAL
                          : Execution.Status.SUCCEEDED,
                      corrections.unresolved() ? "UNRESOLVED_TOOL_ERRORS" : "FINAL_REPLY",
                      content,
                      null,
                      ctx.time().instant());
              postModel.handOffToScope();
              return;
            }
            postModel.handOffToScope();
          } catch (RuntimeException error) {
            postModel.flush();
            throw error;
          }
        }
        var round = new ArrayList<ToolExecutor.Attempt>();
        boolean unknown = false;
        for (var call : reply.toolCalls()) {
          try (var postTool = RecordingScope.coalesce()) {
            try {
              ToolExecutor.Attempt attempt;
              if (unknown)
                attempt =
                    tools.notExecuted(
                        ctx,
                        call,
                        "SIDE_EFFECT_UNCERTAIN",
                        "Earlier action result is uncertain; remaining request was not executed");
              else if (ctx.deadline().expired())
                attempt =
                    tools.notExecuted(
                        ctx, call, "DEADLINE", "Deadline reached; request was not executed");
              else if (iteration == limit)
                attempt =
                    tools.notExecuted(
                        ctx,
                        call,
                        "ITERATION_LIMIT",
                        "Iteration limit reached; request was not executed");
              else attempt = RuntimeTiming.call("react.tool", () -> tools.execute(ctx, call));
              round.add(attempt);
              var result = attempt.result();
              if (result.outcome() == ToolResult.Outcome.SUCCESS) completed++;
              session.append(
                  ctx.execution().id(),
                  ctx.execution().turnId(),
                  ChatMessage.tool(call, resultText(result)),
                  result.outcome());
              postTool.handOffToScope();
              if (ToolFailurePolicy.stop(result)) {
                unknown = true;
                ctx.stop();
              }
            } catch (RuntimeException error) {
              postTool.flush();
              throw error;
            }
          }
        }
        try (var postObserve = RecordingScope.coalesce()) {
          try {
            corrections.observe(ctx, round, tools);
            postObserve.handOffToScope();
          } catch (RuntimeException error) {
            postObserve.flush();
            throw error;
          }
        }
        ctx.execution().toolErrors(corrections.hadErrors(), corrections.unresolved());
        if (ctx.deadline().expired()) {
          ctx.expire();
          return;
        }
        if (unknown) {
          ctx.execution()
              .finish(
                  Execution.Status.PARTIAL,
                  "SIDE_EFFECT_UNCERTAIN",
                  "结果不确定，请先核查；本次执行已停止，未执行其余请求。",
                  "SIDE_EFFECT_UNCERTAIN",
                  ctx.time().instant());
          return;
        }
        if (iteration == limit) {
          ctx.stop();
          ctx.execution()
              .finish(
                  Execution.Status.FAILED,
                  "ITERATION_LIMIT",
                  "达到迭代上限；已确认完成 " + completed + " 个工具请求，目标未完成，剩余提议未执行。",
                  "ITERATION_LIMIT",
                  ctx.time().instant());
          return;
        }
      }
    } catch (OryxError error) {
      ctx.stop();
      if (Set.of("RECORDING_FAILURE", "PARTIAL_RECORDING_FAILURE", "RECORDING_UNAVAILABLE")
          .contains(error.code()))
        ctx.recordingFailed(error.code().equals("RECORDING_UNAVAILABLE") && modelReplies == 0);
      else if (ctx.deadline().expired() || error.code().equals("DEADLINE")) ctx.expire();
      else
        ctx.execution()
            .finish(
                Execution.Status.FAILED,
                error.code(),
                ctx.redactor().redact(error.getMessage()),
                error.code(),
                ctx.time().instant());
    }
  }

  private String resultText(ToolResult result) {
    return "outcome="
        + result.outcome()
        + "; errorCode="
        + result.errorCode()
        + "; sideEffect="
        + result.sideEffectState()
        + "\n"
        + Objects.toString(result.content(), "");
  }
}
