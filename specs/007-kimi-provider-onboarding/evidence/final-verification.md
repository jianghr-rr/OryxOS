# 007 最终验证（T033）

- 日期：2026-11-24
- 基线提交：`0b52b9b`（工作区含 007 未提交改动）
- 完整日志：`.oryxos-dev/evidence/007-final-verify.log`（本地，不入库）

## 本地验证汇总

| 任务 | 验证项 | 命令 | 结果 |
| --- | --- | --- | --- |
| T001 | 改动前基线 | `./tools/dev/mvnw -B -ntp clean verify`；`python3 tools/dev/test_build_environment.py` | `BUILD SUCCESS`；`Test evidence PASS: 353 required cases`；`Quality gate PASS: 444 baseline entries`；Python 既有 8 项全部通过（详见 [baseline.md](./baseline.md)） |
| T008 | 双 Provider 入口回归（FR-007、SC-006） | 只含 `kimi` 的临时工作区运行 `-Doryxos.tests.mode=real -Dgroups=real-provider` 与 `-Dgroups=real-provider-tools` | 均在 `@BeforeEach` 前置失败（耗时 0.05–0.06 s），分别报 `Missing runtime provider: deepseek` 与 `Missing provider deepseek`，未发网络请求（详见 [baseline.md](./baseline.md)） |
| T012 | Kimi 普通请求负向前置（FR-004） | `-pl oryxos-provider -am test -Doryxos.tests.mode=real -Dgroups=real-provider-kimi -Doryxos.real.workspace=<ws>` | 缺 `KIMI_API_KEY` 报 `Missing/invalid provider configuration or secret reference: kimi`；占位模型报 `Configure an account-enabled model in real-kimi/AGENT.md`；输出中 `deepseek` 命中 0 次（详见 [kimi-preflight.md](./kimi-preflight.md)） |
| T019 | Kimi 工具回填负向前置（FR-004） | `-pl oryxos-boot -am test -Doryxos.tests.mode=real -Dgroups=real-provider-tools-kimi -Doryxos.real.workspace=<ws>` | 占位模型报 `Configure account-enabled tool/reasoning models`；非白名单端口 18474 报 `HTTP target is not authorized by scheme, host and port`；输出中 `deepseek` 命中 0 次（详见 [kimi-preflight.md](./kimi-preflight.md)） |
| T027 | US3 本地体验与内核回归 | `-pl oryxos-boot -am test -Dgroups=kimi-onboarding`；`-Dgroups=kernel-local` | `KimiOnboardingLocalIT` 5/5 通过；`kernel-local` 9/9 通过（全量回归中 `KimiOnboardingLocalIT` 再次 5/5 通过） |
| T031 | 格式检查、全量本地回归与 Python 构建环境测试 | `./tools/dev/mvnw -B -ntp spotless:apply`；`./tools/dev/mvnw -B -ntp clean verify`；`python3 tools/dev/test_build_environment.py` | Spotless 0 文件改动；Maven `BUILD SUCCESS`（10/10 模块）；`Test evidence PASS: 358 required cases; run fbe80cd9-f06c-4a2b-8cd7-1ea31c977194`（较基线 353 增加 5 项 `kimi-onboarding`）；`Quality gate PASS: 444 baseline entries, 3991 report-only findings`（`config/quality/baseline.json` 无变更）；Python `Ran 10 tests in 0.011s OK` |
| T032 | 密钥泄露安全检查（SC-003） | 密钥前缀全文检索与 `git diff \| grep -E "sk-[A-Za-z0-9]{10,}"` | 命中 0 次，仓库与差异中无任何密钥字面值 |

## 真实 Kimi 联调验收状态（T013 / T020）

| 任务 | 分组 | 状态 | 说明 |
| --- | --- | --- | --- |
| T013 / T014 | `real-provider-kimi` | **通过** | runId `8ed7f0ca-a2c5-4821-8d87-017af0c124fb`，请求/实际模型 `kimi-k3.1-pro`，`durationMs=3383`，`inputTokens=140` / `outputTokens=92`，无自动重试；密钥前缀扫描 0 命中。详见 [kimi-real.md](./kimi-real.md)，已勾选 spec 001 `T027-K` |
| T020 / T021 | `real-provider-tools-kimi` | **通过** | runId `8ed7f0ca-a2c5-4821-8d87-017af0c124fb`（同次运行先通过 `T027-K`），`modelCalls=2`、`toolHttpRequests=1`、`reasoningObserved=true`、`reasoningReplayed=true`、`finalReplyHasFacts=true`、`durationMs=7439`、`inputTokens=692` / `outputTokens=109`；门禁 `Test evidence PASS: 2 required cases`；密钥前缀扫描 0 命中。详见 [kimi-real.md](./kimi-real.md)，已勾选 spec 001 `T042-K` |

## 仍未完成的 spec 001 关联任务

- `T027-D`、`T042-D`：DeepSeek 侧仍无真实凭证，保持未完成。
- `T027`、`T042` 父任务及依赖它们的 `T028`、`T044`、`T098–T100`：按 FR-008 须待 `-K` 与 `-D` 两个子项均完成后方可勾选。
