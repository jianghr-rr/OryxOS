# 007 基线（T001）

- 时间：2026-11-24
- 提交：`0b52b9b`（006 改动已由维护者提交）；工作区另有 007 未提交改动，基线运行期间仅新增/修改了已完成编译阶段的测试源码与非编译文件（见下）
- 命令：`ORYXOS_JAVA_HOME=<JDK21> ./tools/dev/mvnw clean verify`
- 完整日志：`.oryxos-dev/evidence/007-baseline-verify.log`（本地，不入库）

## 结果

| 项 | 值 |
| --- | --- |
| Maven | `BUILD SUCCESS`，`EXIT=0` |
| 测试证据门禁 | `Test evidence PASS: 353 required cases; run 17ad439c-e393-41ab-9100-b60f7bd1565a` |
| 质量门禁 | `Quality gate PASS: 444 baseline entries, 3987 report-only findings` |
| 基线 N | **353** |

## Python 构建环境测试

`python3 tools/dev/test_build_environment.py`：10 项中 9 项通过，1 项错误为 T007 新增的 `test_real_kimi_groups_forward_only_kimi_key`，原因是 `real-provider-kimi` 等分组尚未登记（`Unknown/unimplemented group`），符合 tasks.md 依赖说明，登记后（T011/T018）复跑。T007 之前的 8 项原有测试全部通过。

## 运行期间的工作区状态

`git status --short tools/dev specs/001-single-node-agent-runtime`：

```text
 M tools/dev/build.py
 M tools/dev/test_build_environment.py
?? tools/dev/examples/real-provider-kimi/
```

`examples/runtime.example.yaml` 在基线结束前未改动（`KernelFixture` 运行期读取该文件）。

## T008 双 Provider 入口回归

`-pl oryxos-boot -am` 编译（含 test-compile）通过。临时工作区复制自 `tools/dev/examples/real-provider-kimi`（只含 kimi），未设置任何 Provider 密钥：

| 命令分组 | 结果 | 失败信息 |
| --- | --- | --- |
| `-pl oryxos-provider -am verify -Doryxos.tests.mode=real -Dgroups=real-provider` | 2/2 失败，耗时 0.058 s | `Missing runtime provider: deepseek` |
| `-pl oryxos-boot -am verify -Doryxos.tests.mode=real -Dgroups=real-provider-tools` | 2/2 失败，耗时 0.053 s | `Missing provider deepseek` |

两者都在前置检查（`@BeforeEach`）失败，未启动运行时、未发出网络请求；双入口仍要求 DeepSeek 与 Kimi 同时就绪（FR-007、SC-006）。
