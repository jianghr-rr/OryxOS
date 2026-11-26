package io.github.oryxlabs.oryxos.tool.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.tool.ToolResult;
import io.github.oryxlabs.oryxos.tool.support.ToolFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("us4-tools")
class FileToolsTest {
  @TempDir Path root;

  @Test
  @DisplayName("read_file/write_file/list_dir 在白名单目录内应正常跑通")
  void fileTools_readWriteAndListWithinAllowedPaths() throws Exception {
    try (var fixture = new ToolFixture(root)) {
      Files.createDirectories(root.resolve("output"));
      var policy = new FileAccessPolicy(root, List.of("output"), List.of("output"));
      var ctx = fixture.context("writer");

      var writeTool = new WriteFileTool(policy);
      Map<String, Object> writeArgs = Map.of("path", "output/report.txt", "content", "hello oryx");
      writeTool.authorize(writeArgs, ctx);
      ToolResult writeResult = writeTool.execute(writeArgs, ctx);
      assertEquals(ToolResult.Outcome.SUCCESS, writeResult.outcome());

      var readTool = new ReadFileTool(policy);
      Map<String, Object> readArgs = Map.of("path", "output/report.txt");
      readTool.authorize(readArgs, ctx);
      ToolResult readResult = readTool.execute(readArgs, ctx);
      assertEquals(ToolResult.Outcome.SUCCESS, readResult.outcome());
      assertTrue(readResult.content().contains("hello oryx"));

      var listTool = new ListDirTool(policy);
      Map<String, Object> listArgs = Map.of("path", "output");
      listTool.authorize(listArgs, ctx);
      ToolResult listResult = listTool.execute(listArgs, ctx);
      assertEquals(ToolResult.Outcome.SUCCESS, listResult.outcome());
      assertTrue(listResult.content().contains("report.txt"));
    }
  }

  @Test
  @DisplayName("文件工具命中白名单外路径、路径穿越、控制文件或越界符号链接应被拦下")
  void fileTools_rejectOutOfBoundsPathsTraversalAndSymlinks() throws Exception {
    try (var fixture = new ToolFixture(root.resolve("workspace"))) {
      Path workspaceRoot = fixture.workspace.root();
      Files.createDirectories(workspaceRoot.resolve("output"));
      Files.createDirectories(workspaceRoot.resolve("private"));
      Files.writeString(workspaceRoot.resolve("private/secret.txt"), "private-data");
      Path outside = root.resolve("outside.txt");
      Files.writeString(outside, "untouched");
      Files.createSymbolicLink(workspaceRoot.resolve("output/escape.txt"), outside);

      var policy = new FileAccessPolicy(workspaceRoot, List.of("output"), List.of("output"));
      var ctx = fixture.context("writer");
      var readTool = new ReadFileTool(policy);
      var writeTool = new WriteFileTool(policy);
      var listTool = new ListDirTool(policy);

      assertThrows(
          OryxError.class, () -> readTool.authorize(Map.of("path", "../outside.txt"), ctx));
      assertThrows(
          OryxError.class, () -> readTool.authorize(Map.of("path", "private/secret.txt"), ctx));
      assertThrows(OryxError.class, () -> listTool.authorize(Map.of("path", "private"), ctx));
      assertThrows(
          OryxError.class,
          () ->
              writeTool.authorize(
                  Map.of("path", "output/escape.txt", "content", "overwrite"), ctx));
      assertThrows(
          OryxError.class,
          () -> writeTool.authorize(Map.of("path", "runtime.yaml", "content", "tamper"), ctx));

      assertEquals("untouched", Files.readString(outside));
    }
  }
}
