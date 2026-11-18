# F0 完成与验证证据

日期：2026-11-12（Asia/Shanghai）。范围：T001–T018；US1–US5未实施。无提交、推送或发布。

## 实现与需求

| 范围 | 已实现 | 依据 |
| --- | --- | --- |
| 隔离工程 | 个人tools/dev入口、独立Maven settings/global settings/Wrapper与仓库缓存，复用原九模块 | T001–T006；FR-030、041；宪章III/VII |
| 轻命令与工作区 | 完整JAR帮助/版本/init/status、稳定workspace UUID、默认Agent和Bootstrap模板、幂等初始化、根优先级与规范路径 | T009、T012；FR-001、030 |
| 安全配置 | 单次有界YAML读取/版本哈希、重复键/tag/alias/深度拒绝、显式秘密引用、局部Provider错误、统一时区、脱敏投影 | T008、T010、T016；FR-003、005、028；RV-04/06的F0部分 |
| 运行基础 | 核心RuntimeLauncher接口、Boot实现、进程锁、单调截止、原子许可和暂停、无参数serve、默认回环 | T011、T015；FR-030、033、040的基础部分 |
| 存储基础 | SQLite WAL/FULL/foreign_keys/busy_timeout=5000、最多4连接、显式checksum迁移、受控JPA生命周期 | T013–T017；FR-018、038、040的基础部分 |
| 阶段状态 | 更新tasks/plan/spec/CLAUDE及宪章效力元数据；质量清单只读，原则与产品决定不变 | T007、T018；AS-01、宪章VII |

只有schema_migrations元数据表在正式F0迁移中创建；测试Probe表/实体只在测试资源。
无Session、Agent执行、模型/工具审计、调度或记忆业务表；业务仓库随US3/US5实体任务接入。
默认Agent模板不等于有效Agent，status明确显示NOT_IMPLEMENTED及未探测模型账号。

## 环境与命令

个人仓库：`/Users/jianghaoran/github/OryxOS`。JDK Temurin 21.0.13+9；Maven Wrapper 3.9.16；
Boot 3.5.16。通过ORYXOS_JAVA_HOME只读选择既有JDK：
`/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home`。
所有个人构建可变状态在本仓库 `.oryxos-dev/` 或各模块 `target/`；不调用课程run.sh。

```bash
export ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home
cd /Users/jianghaoran/github/OryxOS
./tools/dev/mvnw -B -ntp clean verify
python3 tools/dev/test_report_gate.py
python3 tools/dev/verify-foundation.py
```

实际结果：父项目及九模块全部BUILD SUCCESS；8个测试类共18用例，失败0、错误0、跳过0。
当前必跑报告全部匹配本次runId `bdcdebf7-2ee7-4707-8a59-5470c45ab438`。
Surefire test阶段同时发现普通测试和*IT，verify不重复执行。没有使用H2。

| 验证输入 | 预期与实际结果 |
| --- | --- |
| 不存在/错误JDK；从/tmp调用构建 | 明确拒绝错误JDK；异地调用仍锁定个人仓库，JDK21/Maven3.9.16 |
| 旧入口、尚未实现的init、空存储工厂与生命周期 | 先运行行为断言得到真实失败；实现后通过。编译失败未计为行为基线 |
| 完整boot JAR --help/--version；薄cli JAR --version | 退出0、版本1.0.0-SNAPSHOT；无Spring/Tomcat/工作区创建 |
| 两次init，中间编辑USER.md | UUID稳定、所有已有文件内容哈希相同，不创建DB；外逃目录symlink拒绝且外部目录无新增文件 |
| --root/系统属性/环境/default与真实symlink | 按约定优先级；规范路径与DB位置一致；真实JAR异地cwd、无参数serve、系统属性覆盖环境均通过 |
| 两Provider中一项缺secret/无效地址/选项 | 单项UNAVAILABLE，另一项配置可用；全局重复键/坏YAML才CONFIGURATION_ERROR；不探测公网账户 |
| CANARY、坏YAML、秘密引用、本地秘密权限 | 环境优先、缺失/递归/直接密钥拒绝，受控本地秘密要求私有权限；错误/URL/stderr投影无秘密 |
| 截止/停执行/工作区暂停 | 单调时钟到期拒绝许可，停止一项不阻断另一项；暂停无法通过普通首次启动方法重开 |
| SQLite/JPA与测试实体 | pragma值全部符合约定，外键拒绝坏引用，提交跨资源重开可读、回滚无残留；旧EntityManager与池均关闭 |
| checksum篡改、损坏DB、DB路径为目录 | 初始化失败，不删库、不以空库假成功；诊断Spring/真实HTTP进程存活 |
| 关闭闸门下重建存储工厂 | 新资源可用、旧资源关闭；门禁仍PAUSED_RECORDING，不冒充人工确认恢复 |
| 两个真实JVM同root；运行时chat | 第二个serve/chat退出4；正常退出释放锁；离线chat明确未实现退出3 |
| 漏IT、跳过必跑方法、旧XML、未知/未登记组、mode不匹配 | 报告核查器/构建入口均失败；DiscoverySuffixIT只匹配新增*IT规则 |

F0没有业务执行ID；模型、MCP、通知及其他业务外部副作用计数均为0。
回环HTTP只用于测试夹具与基础服务器；测试工作区是临时目录。
完整JAR验收脚本自动停止其进程，并核对损坏数据库原始字节未被重建覆盖。

依赖树和effective-pom已核对：AI1.1.8、MCP0.18.3、Picocli4.7.7、SQLite3.51.3.0、
validator1.5.9、Springdoc2.9.1、Hibernate core/community同为6.6.53.Final；无模块环、无cli→boot或memory→storage。
`git diff --check`、shell语法、任务104项编号/路径/依赖与文档引用检查通过。

验证原始日志、Surefire XML、环境比较与脚本结果保存在被忽略的 `.oryxos-dev/evidence/`；
本报告是可入库的脱敏摘要。2416份全局设置/官方仓库跟踪文件对比实现前哈希无变化；
ACSP的11份根构建配置在最终发行验证前后哈希无变化。本轮未构建/修改ACSP或官方实验仓库，
也未变更全局Java/Node/Maven配置；既有无关修改仍保留。

## 操作与边界

```bash
ORYXOS_WS=$(mktemp -d /tmp/oryxos-f0.XXXXXX)
./tools/dev/oryxos --root "$ORYXOS_WS" init
./tools/dev/oryxos --root "$ORYXOS_WS" init
./tools/dev/oryxos --root "$ORYXOS_WS" status
./tools/dev/oryxos --root "$ORYXOS_WS" serve
# Ctrl-C停止。另开终端可运行status；第二个serve被拒绝。
```

serve默认127.0.0.1:8080；只有基础HTTP装配。`/api/v1/health`等业务路径仍返回404。
status不创建DB、不验证DB健康；DB/配置异常的当前诊断在启动日志和受控装配状态，
正式故障详情、跨重启运行日志及人工恢复API属于US5。
存储资源可创建不代表业务READY；F0始终不开放Agent执行。

尚未验证真实DeepSeek/Kimi、Webhook、MCP、Mem0、三个业务Demo或性能指标；它们都不属于F0。
下一批US1为T019–T028：Agent定义/可用性、Provider中立契约、显式映射、低层单次请求、
协议字段/工具名称往返、错误与日志、本地双Provider验证及两家真实普通请求；后者需真实配置。
本轮在F0停止，不提前实施任何US1任务。
