# 项目内的 Spec Kit

本项目固定使用官方 `specify-cli==1.2.5`，安装在根目录的 `.venv-speckit/`，通过 `uv pip` 安装。它不会替换全局 `specify`，也不依赖全局安装的版本。

## 使用

在仓库根目录检查项目内版本及集成状态：

```bash
./specifyw version
./specifyw integration status
```

启动 Claude Code 前，在当前终端激活项目内的 Python 环境，让命令和生成的脚本都使用同一版本：

```bash
source .venv-speckit/bin/activate
claude
```

然后在 **Claude Code 对话框**中使用以下命令（不是终端命令）：

```text
/speckit-constitution
/speckit-specify <你要实现的功能需求>
/speckit-clarify
/speckit-plan <技术约束与方案>
/speckit-tasks
/speckit-analyze
/speckit-implement
```

初始化只安装工具和模板；`.specify/memory/constitution.md` 目前是未填写模板，尚未形成已确认的项目原则，也未生成任何功能规格或实施计划。按课程要求，先结合需求文档和技术方案形成宪章，人工检查后再进入后续流程。

当前安装的是 Claude Code 集成。项目命令位于 `.claude/skills/`；模板、辅助脚本和默认工作流位于 `.specify/`。未安装扩展或额外 preset，关闭了不需要的 Claude runtime event hooks，核心命令可以直接使用。课程旧文档中的 `/speckit.specify` 对应当前的 `/speckit-specify`。

## 新检出或重建本地环境

需要已有的 Python 3.11+ 和 `uv`。在仓库根目录执行，`--no-python-downloads` 保证不会自动安装项目外的 Python：

```bash
uv venv --no-cache --no-python-downloads --python '>=3.11' .venv-speckit
uv pip install --no-cache --python .venv-speckit/bin/python -r tools/spec-kit/requirements.txt
./specifyw version
./specifyw integration status
```

已有 `.specify/` 和 `.claude/skills/` 时不需要重新 `init`。本次首次初始化使用：

```bash
./specifyw init --here --force --non-interactive --integration claude --script sh --integration-options='--events false'
```

`--force` 会允许覆盖同名受管文件，仅用于确认过文件范围的首次初始化。不要用重复初始化来重置已编写的课程成果。Python 虚拟环境不提交 Git；版本声明、项目模板与 skills 可随仓库共享。退出环境使用 `deactivate`。

参考：[官方安装指南](https://github.github.io/spec-kit/installation.html)、[Claude Code 集成](https://github.github.io/spec-kit/integrations/claude.html)。
