# Baseline Snapshot (S0) — 008-cli-boundary-refactor

- **Date**: 2026-11-24
- **Branch**: `008-lesson18-cli-boundary`（切自 `origin/main` `64c9b1e`）
- **Quality baseline entries** (`config/quality/baseline.json`): `444`
- **Current `us5-cli` test group**: 2 classes / 15 methods
  - `oryxos-cli`: `io.github.oryxlabs.oryxos.cli.CliOperationsTest` (6)
  - `oryxos-boot`: `io.github.oryxlabs.oryxos.boot.CliRuntimeContractTest` (9)
- **Main source changes planned**: 0 files（行为保持型边界对齐与验收 harness 补齐）
- **Spec-Kit analyze check**: `spec.md` / `plan.md` / `data-model.md` / `tasks.md` 逐项一致，无 `[NEEDS CLARIFICATION]` 残留，6 项保留偏差均对照宪章 v1.0.0 与 spec 001~007，无跨模块循环依赖。
