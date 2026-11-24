# Contract: `ProfileContext` 与 `AgentService` 处理边界

本特性唯一新增的 public 类型。其余 ReAct 相关类的对外签名均不变（FR-007）。

## API

```java
package io.github.oryxlabs.oryxos.core.agent;

public final class ProfileContext {
  public static void set(AgentProfile profile);   // 仅 AgentService 调用
  public static AgentProfile current();           // 不在处理中返回 null，不抛异常
  public static void clear();                     // ThreadLocal.remove()
}
```

## 不变量

| 编号 | 约束 | 守护测试 |
| --- | --- | --- |
| PC1 | 一次处理的工具执行期间，`ProfileContext.current()` 与 `ExecutionContext.agent()` 是同一实例 | `AgentServiceTest#profileContextIsAvailableDuringToolExecution` |
| PC2 | 循环以意外异常结束时，内联执行线程上 `current()` 为 `null` | `AgentServiceTest#profileContextIsClearedWhenProcessingThrows` |
| PC3 | 异步工作线程在 `handle.complete` 触发调用方回调前已清理 | `AgentServiceTest#profileContextIsClearedBeforeCompletionOnWorkerThread` |
| PC4 | `set` / `clear` 只出现在 `AgentService` 主代码中 | quickstart §4 搜索 |
| PC5 | 现有工具与记忆后端仍从 `ExecutionContext` 取 Agent，本次不改取值方式 | quickstart §4 搜索（`ProfileContext.current` 主代码零引用） |

## 不改变的外部契约

`AgentService` 的公开方法、`ReActLoop.run`、`PromptBuilder.build`、`ToolExecutor.execute`、`ContextLoader.load` 签名与行为；
HTTP/OpenAPI、CLI 输出与退出码、`tool_invocations` / `llm_calls`、会话消息、错误码集合、迭代上限结束文案。
