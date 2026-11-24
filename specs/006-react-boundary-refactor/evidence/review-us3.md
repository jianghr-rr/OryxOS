# US3 故事级复查（课件修订建议）

- [lesson-17-revision.md](../lesson-17-revision.md) 第一部分 15 行，覆盖 spec“保留的偏差”全部 9 条
  （run 签名、最后一轮不执行与文案、Provider 调用、ToolExecutor 签名、表列与 Repository、AgentService 签名与保存方式、Profile 命名、skills 字段、Bootstrap 缺失处理），
  另含 `ProfileContext` 新增与“工具读取通道并存”两行，以及 PromptBuilder 两行“一致”说明。
- 第二部分覆盖课件第四部分全部 5 个测试类。
- 文中引用的 16 个文件路径逐一 `test -f` 核对存在；行号取自 spec 006 完成后的检出（`ReActLoop.java:37/114/165-171`、
  `AgentService.java:99/236/284`、`ToolExecutor.java:35/312`、`ContextLoader.java:11/62`、`PromptBuilder.java:17-21`、`AgentDefinitionReader.java:172`、`JpaAuditSink.java:86`）。
- 课件原文 `docs/第17节：ReAct 原理解析、实现与代码讲解.md` 未修改。
