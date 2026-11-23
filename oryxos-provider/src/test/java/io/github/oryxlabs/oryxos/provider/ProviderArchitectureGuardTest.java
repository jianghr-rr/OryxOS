package io.github.oryxlabs.oryxos.provider;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;

@Tag("us1-contract")
class ProviderArchitectureGuardTest {
  static final String ALLOWED_PREFIX = "org.springframework.ai.openai.api.";
  static final Pattern SPRING_AI = Pattern.compile("org\\.springframework\\.ai\\.[\\w.]*");
  static final Pattern FORBIDDEN =
      Pattern.compile(
          "\\b(ChatModel|ChatClient|ToolCallback|ToolCallingManager|internalToolExecution)\\b");
  static final Pattern COMMENTS = Pattern.compile("(?s)/\\*.*?\\*/|//[^\\n]*");

  @Test
  void providerMainCodeUsesOnlyLowLevelOpenAiApi() throws Exception {
    Path sources = Path.of("src/main/java");
    assertTrue(
        Files.isDirectory(sources), "provider main sources must be scanned from module root");
    var hits = new ArrayList<String>();
    try (Stream<Path> files = Files.walk(sources)) {
      for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
        String code = stripComments(Files.readString(file));
        String[] lines = code.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
          var spring = SPRING_AI.matcher(lines[i]);
          while (spring.find())
            if (!spring.group().startsWith(ALLOWED_PREFIX))
              hits.add(file + ":" + (i + 1) + " " + spring.group());
          var forbidden = FORBIDDEN.matcher(lines[i]);
          while (forbidden.find()) hits.add(file + ":" + (i + 1) + " " + forbidden.group());
        }
      }
    }
    assertEquals(
        List.of(), hits, "Provider 不得接入框架自动工具执行（宪章 II）；只允许 " + ALLOWED_PREFIX + "，命中位置：" + hits);
  }

  /** Keeps line numbers stable so failures point at the real source line. */
  static String stripComments(String source) {
    var matcher = COMMENTS.matcher(source);
    var out = new StringBuilder();
    while (matcher.find())
      matcher.appendReplacement(out, matcher.group().replaceAll("[^\\n]", " "));
    matcher.appendTail(out);
    return out.toString();
  }
}
