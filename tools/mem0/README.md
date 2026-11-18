# 个人隔离的 Mem0 OSS 服务

这是 US3 的真实联调准备，不是已运行的服务。当前 Docker daemon 不可用，应用镜像尚未构建，
`container-lock.json.applicationDigest` 为 null；T051、T057、T058 均未完成。
MD/SQLite 不需要这些容器。所有命令从个人仓库执行，不使用官方课程脚本或 ACSP 配置。

## 已锁定与待核实部分

- Mem0 OSS 2.2.1，源码 commit `94c3fe9f238f3dbf29c9ce98643bd71eb13077cd`。
- 源码压缩包、Python 3.12/Linux amd64 的传递依赖版本及下载哈希已核验；见 `container-lock.json`、`requirements.lock`。
- Python 基础镜像与 pgvector 镜像的 manifest digest 已从 Docker Hub 核验并固定到 Dockerfile/Compose。
- 应用镜像的 build、digest、启动与真实模型效果仍待核实，不能用基础镜像 digest 代替。
- 固定 Compose 项目 `oryxos-personal-mem0`；仅监听 `127.0.0.1:18888`，PostgreSQL 不映射宿主端口。
  PostgreSQL、Mem0 history 使用本项目命名卷；显式设置 `MEM0_TELEMETRY=false`，启用 API key 鉴权。
- 服务只接收明确保存的 ARCHIVAL 内容和检索词。提炼与 embedding 会访问配置的外部模型服务；CORE 只在 OryxOS 本地保存。

来源：[锁定服务源码](https://github.com/mem0ai/mem0/tree/94c3fe9f238f3dbf29c9ce98643bd71eb13077cd/server)、
[Mem0 2.2.1](https://pypi.org/project/mem0ai/2.2.1/)。未复制或执行上游开发 Compose。

## 准备及构建

```bash
cd /Users/jianghaoran/github/OryxOS
# 校验并准备源码；不构建、不启动服务
python3 tools/mem0/build.py
# 需已可用的 Docker daemon、buildx、Linux amd64 构建能力及网络
python3 tools/mem0/build.py --build
```

构建缓存、Docker CLI 配置和证据位于 `.oryxos-dev/`。脚本只在本地构建并载入镜像，
不会 push 或启动容器。默认使用已有 Docker Desktop socket；自定义 daemon 可设置
`ORYXOS_DOCKER_HOST`。不修改用户全局 Docker config/context。
构建成功后，`mem0-build/verified-image.json` 记录源码、依赖校验和、image ID、BuildKit manifest digest，
`mem0-build/image.env` 提供经 `docker image inspect` 核实的不可变 image ID。
核对这些真实证据后才能补齐 T051；不要自行填写虚构 digest。

## 配置并显式启动个人服务

必须具备：有效的模型服务密钥、可用提炼模型、embedding 模型及一致的向量维度、可访问服务地址，
以及新的私有数据库/API/JWT 密钥。当前 Compose 使用同一个 OpenAI 兼容服务配置提炼与 embedding；
需账号实际支持两种模型，不使用占位名称冒充可用配置。

```bash
cp -n tools/mem0/settings.example .oryxos-dev/mem0.env
chmod 600 .oryxos-dev/mem0.env
# 私下填写全部 REPLACE_*；ORYXOS_MEM0_IMAGE 取自构建成功后的 image.env
export DOCKER_CONFIG="$PWD/.oryxos-dev/docker"
export DOCKER_HOST="${ORYXOS_DOCKER_HOST:-unix://$HOME/.docker/run/docker.sock}"
env -u DOCKER_CONTEXT -u DOCKER_AUTH_CONFIG -u DOCKER_TLS_VERIFY -u DOCKER_CERT_PATH docker compose --env-file .oryxos-dev/mem0.env -f tools/mem0/compose.yaml config --quiet
# 配置核实后，用户显式执行以下启动命令
env -u DOCKER_CONTEXT -u DOCKER_AUTH_CONFIG -u DOCKER_TLS_VERIFY -u DOCKER_CERT_PATH docker compose --env-file .oryxos-dev/mem0.env -f tools/mem0/compose.yaml up -d
# 查看本项目容器 ID；不输出包含秘密的完整 inspect/config
env -u DOCKER_CONTEXT -u DOCKER_AUTH_CONFIG -u DOCKER_TLS_VERIFY -u DOCKER_CERT_PATH docker compose --env-file .oryxos-dev/mem0.env -f tools/mem0/compose.yaml ps -q mem0
```

Linux 可将 `DOCKER_HOST` 指向实际个人 daemon socket。停止用 `docker compose ... stop`，
不要用 `down -v` 删除持久化验收数据。更换 embedding 模型/维度不等于自动迁移原向量数据。

## OryxOS 真实验收入口

另建全新私有工作区，例如 `.oryxos-dev/real-mem0-workspace`。`runtime.yaml` 的必要配置：

```yaml
schema_version: 1
timezone: Asia/Shanghai
memory:
  backend: mem0
  archival_context_characters: 4000
  endpoint: http://127.0.0.1:18888
  api_key: '${MEM0_API_KEY}'
  timeout_seconds: 20
  search_top_k: 10
  search_threshold: 0.7
```

在当前终端私下提供 `MEM0_API_KEY`，或按项目契约使用该工作区的权限受控 `secrets.local.yaml`。
不要把密钥放到 Maven `-D` 参数中。构建工具只为 `real-mem0` 转发客户端 `MEM0_API_KEY`，
不会转发模型服务密钥、DeepSeek/Kimi 或 ACSP 的环境变量。

```bash
export ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home
./tools/dev/mvnw -B -ntp -pl oryxos-memory -am test \
  -Doryxos.tests.mode=real -Dgroups=real-mem0 \
  -Doryxos.real.workspace="$PWD/.oryxos-dev/real-mem0-workspace" \
  -Doryxos.real.mem0.container=REPLACE_PERSONAL_CONTAINER_ID \
  -Doryxos.real.mem0.imageId=REPLACE_VERIFIED_SHA256_IMAGE_ID \
  -Doryxos.real.mem0.llm=REPLACE_ACTUAL_EXTRACTION_MODEL \
  -Doryxos.real.mem0.embedding=REPLACE_ACTUAL_EMBEDDING_MODEL \
  -Doryxos.real.mem0.allowRestart=true
```

测试前检查 Compose project/service、源码 commit、镜像 ID 以及服务 `/configure` 返回的两个模型名。
该用例会停止并重新启动**所指定且匹配本项目标签的 Mem0 容器**，验证离线 CORE 和服务重启后的持久化。
`allowRestart=true` 是这一具体操作的显式开关。不会重启 Docker daemon 或其他容器。
若前提缺失，测试失败且零跳过，不降级到假 Mem0。

固定语料见 `oryxos-memory/src/test/resources/memory/semantic-cases.json`：保存“我关注 AI 芯片”，
同义查询“人工智能硬件偏好”须命中，“饮食忌口”须无匹配。另查 workspace 隔离、跨 Agent 共享和重启。
证据在 `.oryxos-dev/evidence/us3-real/<runId>/mem0.json`，包含实际远端投影供人工与保存原文对照；
必须确认无新增偏好并记录审核结论。即使自动断言通过，`semanticCoverage` 仍为 `NOT_PROVEN`，
人工审核未完成不能关闭 T057/T058。默认生产 chat 仍等待 US5 正式 Session/Execution 存储，
本测试使用正式记忆存储与临时执行上下文，不证明 Session 跨进程恢复。

## 重新生成 Python 锁

仅在需复核依赖锁时使用已安装 uv；缓存和可选 Python 安装目录均在个人仓库中：

```bash
UV_CACHE_DIR="$PWD/.oryxos-dev/uv" \
UV_PYTHON_INSTALL_DIR="$PWD/.oryxos-dev/python" UV_NATIVE_TLS=true \
uv pip compile --python-version 3.12 --python-platform x86_64-unknown-linux-gnu \
  --generate-hashes tools/mem0/requirements.in -o tools/mem0/requirements.lock
```

锁变化后须审查版本并更新 `container-lock.json` 的 `requirementsSha256`，重新构建和验证。
