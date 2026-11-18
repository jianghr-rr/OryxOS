package io.github.oryxlabs.oryxos.channel.cli;

import io.github.oryxlabs.oryxos.core.agent.AgentService;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.execution.*;
import io.github.oryxlabs.oryxos.core.runtime.ChatOptions;
import io.github.oryxlabs.oryxos.core.security.Redactor;
import java.io.*;
import java.util.UUID;

public final class CliChannelAdapter {
  private final AgentService service;
  private final Redactor redactor;
  private final io.github.oryxlabs.oryxos.core.runtime.TimeSource time;

  public CliChannelAdapter(AgentService service) {
    this(service, new Redactor());
  }

  public CliChannelAdapter(AgentService service, Redactor redactor) {
    this(service, redactor, io.github.oryxlabs.oryxos.core.runtime.TimeSource.system());
  }

  public CliChannelAdapter(
      AgentService service,
      Redactor redactor,
      io.github.oryxlabs.oryxos.core.runtime.TimeSource time) {
    this.service = service;
    this.redactor = redactor;
    this.time = time;
  }

  public int chat(ChatOptions options, Reader input, PrintWriter output) {
    UUID session = options.sessionId();
    int code = 0;
    var reader = new BufferedReader(input);
    try {
      String text;
      while ((text = reader.readLine()) != null) {
        if (text.strip().equals("/quit")) break;
        try {
          var waited =
              CliExecutionAwaiter.execute(
                  service,
                  ProcessRequest.manual("cli", options.userId(), options.profile(), session, text),
                  time);
          var result = waited.snapshot();
          session = result.sessionId();
          code = CliOutput.exitCode(result, waited.expired());
          String resultCode = CliOutput.executionCode(result, waited.expired());
          if (options.json())
            CliOutput.json(
                output,
                resultCode,
                code == 0 ? "Execution completed" : "Inspect execution before retrying",
                CliOutput.execution(result, redactor));
          else {
            output.printf(
                "session=%s execution=%s status=%s recording=%s inFlightCount=%d hasToolErrors=%s"
                    + " hasUnresolvedToolErrors=%s%n%s%n",
                session,
                result.id(),
                result.status(),
                result.recordingStatus(),
                result.inFlightCount(),
                result.hasToolErrors(),
                result.hasUnresolvedToolErrors(),
                redactor.redact(result.finalContent()));
            if (code == 6 || code == 7)
              output.println(
                  resultCode
                      + ": outcome/recording may be UNKNOWN; inspect the execution before retrying."
                      + " Recovery requires explicit HTTP operator confirmation.");
            if (result.hasToolErrors() && !result.hasUnresolvedToolErrors())
              output.println(
                  "Completed with corrected tool errors; original failures and correction links are"
                      + " retained.");
            if (result.hasUnresolvedToolErrors())
              output.println(
                  "Tool errors remain unresolved; side effects may be UNKNOWN. Inspect tool audit"
                      + " records before retrying.");
          }
        } catch (OryxError e) {
          if (options.json())
            CliOutput.json(output, e.code(), redactor.redact(e.getMessage()), null);
          else output.println(e.code() + ": " + redactor.redact(e.getMessage()));
          code = exitCode(e.code());
        }
        output.flush();
      }
      return code;
    } catch (IOException e) {
      throw new OryxError("INVALID_INPUT", "Cannot read CLI input");
    }
  }

  public static int exitCode(String code) {
    return CliOutput.exitCode(code);
  }
}
