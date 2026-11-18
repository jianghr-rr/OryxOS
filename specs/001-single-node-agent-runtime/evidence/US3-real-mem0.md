# US3 真实 Mem0 联调前提与未完成状态

**未完成：T051、T057、T058。** 本地假Mem0的HTTP协议与故障验证不代表真实语义检索通过。

| 任务 | 本批实际进展 | 仍缺少什么 |
| --- | --- | --- |
| T051 | OSS2.2.1/commit锁定、源码SHA、Python传递依赖与哈希锁；Python/pgvector manifest digest核实；个人Compose/构建脚本/显式模型与持久卷配置 | 可用Docker daemon；实际应用镜像build与digest核验，不能用基础镜像digest代替 |
| T057 | RealMem0IT已编译、登记real-mem0；无skip/模拟降级；固定正反例与重启/离线CORE/归属检查已准备 | 私有工作区runtime.yaml、MEM0_API_KEY、实际提炼/embedding模型名与有效服务密钥/额度/网络、个人容器与镜像ID；运行实测并人工核对投影无新增事实 |
| T058 | 本地代码/行为和spec/plan/tasks检查完成 | 依赖T057真实结果，因此本出口未勾选 |

实际前检：

```bash
ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home \
  ./tools/dev/mvnw -B -ntp -pl oryxos-memory -am test \
  -Doryxos.tests.mode=real -Dgroups=real-mem0
python3 tools/mem0/build.py --build
```

第一条因缺少 `-Doryxos.real.workspace` 明确失败（1测试/1失败/0跳过），没有发出Mem0/模型请求或重启容器。
runId `6c8793e8-e011-4274-b8be-ce1e09684ee8`，记录 `.oryxos-dev/us3-real-preflight-final.log`。第二条在连接
`unix:///Users/jianghaoran/.docker/run/docker.sock` 时失败，未开始镜像构建、未启动服务，
记录 `.oryxos-dev/us3-mem0-build-preflight-final.log`。没有读取/暴露账号秘密，也不猜测凭证是否有效。

完整准备、配置与用户可执行命令见 [tools/mem0/README](../../../tools/mem0/README.md)。
测试的 `allowRestart=true` 明确允许停止/启动所指定、标签与源码commit匹配的本项目Mem0容器；
用于核对真实服务重启，不操作Docker daemon或其他项目容器。
成功时保存实际远端投影、源ID、workspace、模型/镜像与重启时间，人工核对后才能关闭T057。
返回原文一致性、HTTP200、投影ID或元数据归属单独都不能证明语义正确或全部事实被索引。

原有T027/T028（两家真实普通模型）、T042/T044（两家真实工具多轮）继续未完成；
本批未执行这些外部联调，未修改其既有结果。US3-A7正式会话重启证据继续留T093。
