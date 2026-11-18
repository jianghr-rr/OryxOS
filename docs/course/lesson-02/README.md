# 第二次课：官方 OryxOS 隔离环境准备

记录日期：2026-11-12（Asia/Shanghai）。本轮范围为检查官方构建要求、安装课程专用工具、验证环境并记录结果；不修改业务代码，不启动服务或连接真实 Provider / Webhook。

## 1. 仓库与构建依据

| 用途 | 本地目录 | 来源 |
| --- | --- | --- |
| 实验与构建要求检查 | `/Users/jianghaoran/jikeshijian/oryxos` | `git@github.com:oryx-labs/oryxos.git` |
| 作业记录与辅助脚本 | `/Users/jianghaoran/github/OryxOS` | `git@github.com:jianghr-rr/OryxOS.git` |

实际检查、使用的官方提交：[`de34688c81665c397ce994c72b0ead8a68762cd3`](https://github.com/oryx-labs/oryxos/commit/de34688c81665c397ce994c72b0ead8a68762cd3)，提交时间 `2026-11-02 14:10:29 +0800`。本轮未拉取、切换或修改该提交。作业仓库的 Maven Wrapper 和工程骨架没有用于实验。

依据官方 checkout 的真实文件：

- [`pom.xml`](https://github.com/oryx-labs/oryxos/blob/de34688c81665c397ce994c72b0ead8a68762cd3/pom.xml)：项目版本 `0.1.9-RELEASE`，`java.version=21`；Enforcer 下限为 Java `[21,)`、Maven `[3.8.9,)`；父 POM 是 Spring Boot `4.1.2`，Spring AI BOM 为 `2.0.2`。
- [`.sdkmanrc`](https://github.com/oryx-labs/oryxos/blob/de34688c81665c397ce994c72b0ead8a68762cd3/.sdkmanrc)：声明 `java=21.0.12+1.1-tem`、`maven=3.9.16`。
- [`docs/README-reference.zh-CN.md`](https://github.com/oryx-labs/oryxos/blob/de34688c81665c397ce994c72b0ead8a68762cd3/docs/README-reference.zh-CN.md)：推荐 Java 21、Maven 3.9+，默认 mock Provider 无需 API Key。
- 实际没有 `mvnw`、`mvnw.cmd` 或 `.mvn/wrapper/`；`.mvn/` 只有 `jvm.config`，用于编译器包导出参数，不能当作 Maven Wrapper。
- [`oryxos-web/pom.xml`](https://github.com/oryx-labs/oryxos/blob/de34688c81665c397ce994c72b0ead8a68762cd3/oryxos-web/pom.xml)：`frontend-maven-plugin=2.0.2`，下载 Node `v20.18.0` 到该模块 `target/`，在 `generate-resources` 阶段执行 npm install、test、build。不要求替换全局 Node；`-DskipTests` 也不等于跳过这组前端任务。

## 2. 准备前检查

| 项目 | 实际结果 |
| --- | --- |
| 系统 | macOS 15.3.1（24D70），Darwin arm64 |
| 现有 JDK | Homebrew OpenJDK `17.0.16`；`JAVA_HOME=/opt/homebrew/opt/openjdk@17`；不满足官方 Java 21 下限 |
| Java 注册情况 | 初次登录 shell 中 `/usr/libexec/java_home -V` 报找不到 Java Runtime；显式使用已有 Homebrew 路径可运行 JDK 17。后续课程命令固定绝对路径，不依赖系统注册或 PATH 顺序 |
| Maven | `command -v mvn` 无结果；已有 `~/.m2`，包含 repository 和 wrapper，未复用 |
| 现有前端工具 | Node `v24.4.1`、npm `11.4.2`、pnpm `11.5.2`，由现有 NVM 路径提供 |
| Docker | CLI `28.3.2`；本轮未检查 daemon、未拉镜像、未启动容器 |
| Git | `2.39.5 (Apple Git-154)` |
| 官方工作区 | `git status --porcelain=v1 --untracked-files=all` 为空 |
| 作业工作区 | 已有 10 个已跟踪文件修改，以及 `.agents/`、`.specify/integrations/codex.manifest.json` 等未跟踪文件；展开后共 21 个既有改动/未跟踪文件，已记录内容哈希用于结束时核对 |

补充检查发现，Git 工作区干净不代表没有被忽略的产物：官方目录已有模块 `target/`、项目内 Node、`node_modules/`、`static/admin/`。例如 Node 文件 mtime 为 `2026-11-02 15:14:02 +0800`，`node_modules/` 为 `2026-11-11 15:06:53 +0800`，`static/admin/` 为 `2026-11-12 14:13:56 +0800`，均早于本轮准备。这些既有文件未清理，也不作为本轮完整构建成功的证据。本轮根项目 `validate` 使用/生成的根目录 `target/` 同样被官方 `.gitignore` 忽略。

## 3. 隔离方式与安装位置

采用官方二进制压缩包加本课命令包装脚本。没有安装全局版本管理器，没有运行 SDKMAN 自动安装或 auto-env，也没有执行 Homebrew install/link。归档安装方式参见 [Adoptium 文档](https://adoptium.net/installation/archives)；Maven 包与 SHA-512 来自 [Apache Maven 下载页](https://maven.apache.org/download.cgi)。

所有工具及可变状态位于两个 Git 仓库之外：

```text
/Users/jianghaoran/jikeshijian/.oryxos-course/
├── tools/jdk-21.0.13+9/Contents/Home/   # Eclipse Temurin JDK
├── tools/apache-maven-3.9.16/          # Apache Maven
├── downloads/                         # 原始压缩包、公开下载元数据
├── cache/maven/                       # Maven localRepository
├── cache/npm/                         # 后续前端构建的 npm 缓存
├── cache/xdg/                         # XDG 缓存
├── home/                              # Java user.home（不是修改系统 HOME）
├── tmp/                               # 临时文件
├── config/                            # 空 npmrc、隔离的 XDG 配置
├── data/                              # XDG 数据
├── runtime/                           # 预留课程运行时状态，尚未启动应用
├── verification/                      # Java 21 探针源码及 class（不入库）
└── logs/                              # 安装、验证及保护性基线记录
```

安装固定版本及校验值见 [`toolchain.env`](../../../tools/course/lesson-02/toolchain.env)：JDK `21.0.13+9`（macOS aarch64），Maven `3.9.16`。JDK 归档用 SHA-256、Maven 归档用 SHA-512 校验后才解压；这些是下载完整性校验，本轮未进行 GPG 签名验证。

`run.sh` 的隔离行为：

- 校验实验仓库 `origin` 为 `oryx-labs/oryxos`，并在该目录执行；拒绝把工具根目录放进 Git 工作区。
- 用新的子进程设置专用 `JAVA_HOME`、`MAVEN_HOME`、PATH；清除继承的 Java/Maven 参数、NVM 配置和密钥环境变量，父 shell 不变。
- 保留真实 `HOME`；另设 Java `user.home`、临时目录、XDG 与 npm 缓存。`MAVEN_SKIP_RC=1`，并显式传入本课空白 user/global settings 和独立 `maven.repo.local`，不读取个人 Maven 配置或复用 `~/.m2/repository`。Maven 配置机制参见 [官方说明](https://maven.apache.org/configure.html)。
- 本轮不更改 `.zshrc`、`.zprofile`、`.zshenv` 等启动文件，不安装系统 JDK，不修改 NVM、pnpm、Android SDK 或 acsp-microapp 的配置。
- 这是进程与目录隔离，不是容器或文件系统沙箱。以后 Maven 构建的模块 `target/`、项目内 Node/npm 依赖和前端静态资源仍写入**官方实验仓库**已忽略的路径；不会写入作业仓库。用户额外传入的 Maven/JVM 参数仍可覆盖设置，应避免自行覆盖隔离路径。
- `ORYXOS_ROOT` 只预设了工作区路径，不代表数据库、端口和服务启动已验证；后续启动应用前仍需独立配置数据库及其他相对路径。

## 4. 准确使用命令

脚本仅支持 macOS arm64，通过执行启用，不要 `source`；每条命令结束即退出课程环境，不需要“恢复”全局 Java 或 Node。

```bash
# 安装或校验固定工具链；可重复执行
/Users/jianghaoran/github/OryxOS/tools/course/lesson-02/setup.sh

# 显示实际官方 commit、目录和工具版本
/Users/jianghaoran/github/OryxOS/tools/course/lesson-02/run.sh check

# 在官方实验目录执行 Maven 的根项目 validate（不是完整构建）
/Users/jianghaoran/github/OryxOS/tools/course/lesson-02/run.sh mvn -B -ntp -N validate

# 单独查看课程 Java/Maven；无论当前目录在哪里都会使用官方 checkout
/Users/jianghaoran/github/OryxOS/tools/course/lesson-02/run.sh java -version
/Users/jianghaoran/github/OryxOS/tools/course/lesson-02/run.sh mvn --version
```

默认工具目录是 `$HOME/jikeshijian/.oryxos-course`，默认实验目录是 `$HOME/jikeshijian/oryxos`。如需调整，用单次命令前缀设置 `ORYXOS_COURSE_HOME`、`ORYXOS_EXPERIMENT_REPO`；工具目录必须在 Git 仓库之外且不含空白。脚本不会自动更新或切换官方 commit，复现实验前应检查 `run.sh check` 输出。

## 5. 本轮实际验证与结果

| 验证 | 实际结果 |
| --- | --- |
| 工具下载完整性 | JDK SHA-256、Maven SHA-512 均匹配固定值 |
| 安装及重复执行 | 修复 locale 后 `setup.sh` 退出 0；再次执行退出 0，输出 `Already installed` 并重新校验归档 |
| 课程 Java | `openjdk 21.0.13`，Temurin `21.0.13+9-LTS`；`javac 21.0.13` |
| 课程 Maven | `Apache Maven 3.9.16`；显示的 Maven home、Java runtime 都位于本课工具目录 |
| Java 21 编译运行 | 用 `javac --release 21` 编译仓库外临时探针，再用本课 Java 执行，退出 0，输出 `COURSE_PROBE_OK`；虚拟线程运行成功 |
| Java 进程隔离 | 探针确认 `user.home`、`java.io.tmpdir` 为本课路径，`user.dir` 为官方实验目录；专门注入的无敏感测试环境变量在子进程中不存在 |
| 误用防护 | 把作业仓库作为实验仓库会被拒绝；把工具目录放进作业仓库也会被拒绝，且没有创建该目录 |
| 官方 Maven 根项目检查 | `run.sh mvn -B -ntp -N validate` 退出 0，`BUILD SUCCESS`；Java/Maven 版本门禁、根项目依赖收敛、Spotless、Checkstyle 均通过，Checkstyle 违规数为 0 |
| 既有文件保护 | 原有 21 个改动/未跟踪文件的 SHA-256 均未变化；Git index 未变化；官方工作区仍干净、commit 未变化 |
| 全局环境保护 | shell 启动文件内容/存在性未变；`~/.m2` 的 9,562 个文件的集合、大小、mtime 未变；父进程 PATH/JAVA_HOME/MAVEN_HOME/M2_HOME 未变 |
| 课程命令退出后 | 仍为原来的 JDK `17.0.16`、Node `v24.4.1`、npm `11.4.2`、pnpm `11.5.2`；全局 `mvn` 仍未找到 |
| 脚本与记录 | `bash -n`、Maven settings XML 解析、文档本地链接检查通过 |

Maven 检查完成时间为 `2026-11-12 16:25:22 +08:00`，耗时 `03:21 min`，专用 Maven 缓存当时约 `58M`。`-N` 表示只运行根 POM，不遍历模块；日志中的 `BUILD SUCCESS` 不代表业务 Java 编译、模块测试或全量质量门禁已通过。

Java 探针关键输出：

```text
virtual-thread=OK
java.version=21.0.13
user.home=/Users/jianghaoran/jikeshijian/.oryxos-course/home
java.io.tmpdir=/Users/jianghaoran/jikeshijian/.oryxos-course/tmp
user.dir=/Users/jianghaoran/jikeshijian/oryxos
inherited-test-secret=absent
COURSE_PROBE_OK
```

本机完整证据保留在 `/Users/jianghaoran/jikeshijian/.oryxos-course/logs/`：`setup.log`（首次失败）、`setup-retry.log`、`setup-idempotent.log`、`java-probe.log`、`maven-validate.log`、`maven-validate.exit-code`、两个 `reject-*.log`，以及 `baseline.json` / `preservation-check.json`。这些本机日志和基线不提交。

## 6. 问题与后续待办

- 官方 `.sdkmanrc` 固定的 `21.0.12+1.1-tem` 对应版本查询 `https://api.adoptium.net/v3/assets/version/21.0.12%2B1.1?architecture=aarch64&image_type=jdk&os=mac&vendor=eclipse` 返回 HTTP 404。这只能说明本次查询未取到包。改从官方 Java 21 latest API 获取可用的 `jdk-21.0.13+9` 元数据，再将精确 URL 和哈希固定到脚本；没有修改官方 `.sdkmanrc`。
- 首次安装在归档下载后执行 `shasum` 时失败：继承的 `LC_ALL=C.UTF-8` 导致 macOS 自带 Perl 报 `Could not change LC_CTYPE locale to C.UTF-8`。脚本已仅在校验子命令使用 `LC_ALL=C`；完整的 `.partial` 下载文件会先验证再复用，避免重新下载。未修改全局 locale。
- 尚未执行完整 reactor 编译、单元测试、前端安装构建、`package` 或 `verify`；尚未启动 OryxOS、创建数据库或配置真实 API Key/Webhook。以上属于后续课程验证，不能根据工具版本可用就判定完成。
- 后续若需要完整构建，在本课包装器下使用 `run.sh mvn -B -ntp package -DskipTests`，记录前端 Node 下载和 npm 任务的实际结果。该命令本轮未执行。
- 后续运行服务前，确认独立数据库路径、运行目录及端口；本轮未对 acsp-microapp 做构建或运行回归。

## 7. 作业提交边界

本课可提交文件仅包括本 README 和 `tools/course/lesson-02/` 内的脚本、公开工具元数据与空白 Maven settings。JDK、Maven、下载包、缓存、日志、构建产物、数据库、密钥、真实 Webhook 均不纳入 Git。本轮不执行暂存、提交或推送。

后续提交时逐文件选择（不要使用 `git add .`）：

```bash
cd /Users/jianghaoran/github/OryxOS
git add -- \
  docs/course/lesson-02/README.md \
  tools/course/lesson-02/common.sh \
  tools/course/lesson-02/toolchain.env \
  tools/course/lesson-02/settings.xml \
  tools/course/lesson-02/setup.sh \
  tools/course/lesson-02/run.sh
git diff --cached --name-only
git diff --cached --check
```

提交前确认暂存列表只有本课文件；如果其中已存在其他工作，先按各项工作的提交边界整理，不要混入本课提交。
