# T094 HTTP 与发布 OpenAPI 验收

2026-11-13，第六批续作：**通过**。以新构建的发行 JAR 启动独立 JVM，实际 GET /v3/api-docs 为 OpenAPI 3.0.3、19 个操作、44 个 schema，既定契约比较差异为 0。

## 修复与防回退

- MVC 控制器的 HttpContract 声明真实响应数据类型、成功/错误状态和执行快照与入口错误的 oneOf；请求绑定仍来自实际 MVC 参数。
- ContractDtos 的 Swagger 注解补齐可空、可选、枚举、边界与唯一集合。PublicSchemas 读取实际 record 组件和泛型 ApiResponse，生成字段、引用及信封；内联工具请求不额外注册第45个schema。
- 固定 springdoc 使用 OpenAPI 3.0 语义。运行时没有读取、复制或打包设计 http-api.json，也没有另写一套44份静态schema。
- PublishedOpenApiTest 原有逐操作响应、参数、请求体和44schema语义比较断言保留。另加精确19/44计数、3.0.3版本、operationId、完整响应状态集合、请求体必填标记，以及新 record 字段/泛型信封自动派生测试。

本轮修复前重新运行已有测试，仍取得可编译行为失败；后续一次增量编译产物含 IDE 的 unresolved compilation stub，通过 tools/dev clean 重新编译消除，不把编译问题当产品红基线。原来的185项差异原样保留：[初次实际文档](US5-openapi-actual.json)、[初次差异](US5-openapi-diff.json)。当前材料为[修复后实际文档](US5-openapi-fixed.json)、[零差异](US5-openapi-fixed-diff.json)。

最终一致性检查还发现原断言未覆盖的19处operationId差异；先补严格断言并取得[新增行为失败](US5-openapi-operation-id-red.json)，再从实际注册路由派生稳定ID，保留原比较范围。

## 验证

| 验证 | 结果 | run ID |
| --- | --- | --- |
| 最终专项并构建新JAR | 2通过，0失败/错误/跳过 | 385824a4-38d9-4ab6-9882-e6f800c685d7 |
| us5-http，进程测试使用该发行JAR | 71通过，0失败/错误/跳过，12类 | 3005a5c4-59f1-4a17-bc50-4d2dc5b6ff18 |

71项定向组是首次元数据修复后的结果；新增operationId断言及最终JAR在后续全量176项中再次验证，见续作记录。综合组保留19操作黑盒及44schema实际序列化、错误矩阵/输入/身份/分页、真实60秒HTTP/CLI截止和截止后零新动作；UTC主机与业务时区、真实cron/重叠/停用手动/不补跑、正文热读与配置重启边界、真实SQLite故障及跨进程恢复均复跑。新增方法没有排除任何旧方法；71是此前70加1个DTO派生测试。

~~~bash
export ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home
cd /Users/jianghaoran/github/OryxOS
./tools/dev/mvnw -B -ntp package -Dgroups=us5-published-contract
./tools/dev/mvnw -B -ntp test -Dgroups=us5-http \
  -Doryxos.acceptance.jar="$PWD/oryxos-boot/target/oryxos-boot-1.0.0-SNAPSHOT.jar"
~~~

不传 acceptance.jar 时使用独立 main JVM，而非发行JAR；仅显式参数运行计作分发包证据。原始本次XML、日志和summary在 .oryxos-dev/evidence/us5-batch6-cont-http/。全量回归见[续作记录](US5-sixth-batch-continuation.md)。本地回环模型/接收器不替代真实模型、Mem0、通知或可信MCP验收。
