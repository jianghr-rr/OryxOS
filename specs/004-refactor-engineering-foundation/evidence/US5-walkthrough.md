# US5：按工程基线说明从头执行（T073，SC-008，quickstart §6）

日期 2026-11-23，仓库目录，Temurin 21.0.13+9，Apple M4。按 `docs/EngineeringBaseline.md` 的顺序执行文中命令，并用脚本计时。

## 结果

| 文档位置 | 命令 | 实际结果 | 与文档一致 |
| --- | --- | --- | --- |
| §2 | `./tools/dev/mvnw clean verify` | 退出码 0，640 秒；`Test evidence PASS: 334 required cases`；`Quality gate PASS: 460 baseline entries` | 是 |
| §2 | `./tools/dev/mvnw spotless:apply` | 退出码 0，1 秒；Java 工作区没有任何变化 | 是 |
| §2 | `./tools/dev/mvnw spotless:check` | 退出码 0，1 秒 | 是 |
| §1 | `./tools/dev/mvnw verify -Dspotbugs.skip=true` | 退出码 2：`Build gate: Isolation/test-gate override is not supported: -Dspotbugs.skip` | 是 |
| §2 | `./tools/dev/quality-baseline --check-shrink origin/main` | 退出码 0：`No baseline on origin/main; accepting initial baseline with 460 entries`（`main` 上还没有基线，首次合入前属预期） | 是 |
| §2 | `./tools/dev/quality-baseline --prune` | 退出码 0：`Baseline pruned: 0 removed, 460 kept`；修复后文件逐字节不变，见下文 | 修复后是 |
| §2 | `./tools/dev/install-hooks` | 退出码 0：`Installed: core.hooksPath=tools/dev/hooks`；随后按文档的卸载命令 `git config --unset core.hooksPath` 恢复为未设置 | 是 |
| §2 | `-Pdependency-scan dependency-check:aggregate` | **未执行**：当前 shell 没有 `NVD_API_KEY`，按维护者要求不索取、不在聊天或命令中出现 Key。该扫描由 CI 的 `Dependency Scan` 作业（Actions Secret）覆盖，结果待 T068 | 未验证 |
| §6、DoD 5 | 临时工作区、`SERVER_PORT=18080` 启动 `serve` 后访问 | `/swagger-ui.html` 200（跟随重定向）、`/v3/api-docs` 200；`/actuator/health`、`/health/liveness`、`/health/readiness`、`/info`、`/prometheus` 均为 200；`/actuator/env`、`/beans`、`/heapdump`、`/metrics` 均为 404 | 是 |
| §5、§9、§8 | `journalctl`、`systemctl show`、`docker stats`、GitHub 分支保护 | 需要服务器或仓库管理权限，本地无法执行；对应 T063、T076 | 未验证 |

引用检查：文中的 15 个仓库路径与 8 个相对链接中，除本文件和 `acceptance-summary.md`（走查时尚未生成）外全部存在；这两个文件现已生成。

## 计时（SC-008 ≤ 30 分钟）

上表脚本总耗时 **648 秒（约 10.8 分钟）**，其中完整校验占 640 秒。SC-008 要求的“切换日志形态启动并检索一条追踪标识”由 [US2-local.md](./US2-local.md) 的 2.1–2.3 完成，整套手工脚本耗时 20 秒。合计约 11 分钟，满足 30 分钟上限。依赖缓存在走查前已就绪；首次下载依赖的时间不在内。

## 走查中发现并修复的问题

`quality-baseline --prune` 在没有删除任何条目时，仍会把 `createdAt` 改写为当天日期；在有条目被删除时，还会把 `baseCommit` 改成当前 `HEAD`。这两个字段在契约中分别表示基线的创建日期和创建时的提交，不应被 prune 改写。

- 修复：`tools/dev/quality-gate.py` 在 prune 时保留原有的 `createdAt` 与 `baseCommit`，没有条目被删除时不写文件。
- 测试：在 `tools/dev/test_quality_gate.py` 中新增 `test_prune_keeps_creation_metadata_and_skips_noop_rewrite`，修改前失败，修改后通过；`tools/dev` 的 4 个测试文件共 26 个用例全部通过。
- 走查中被改写的 `baseline.json` 已还原；修复后再次执行 `--prune`，文件逐字节不变。
