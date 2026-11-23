# P0：工具兼容性实测（T019）

环境：Temurin 21.0.13+9，Maven 3.9.16（Wrapper），`tools/dev/mvnw`，2026-11-21。

## G1 Spotless + google-java-format

| 尝试 | 结果 | 结论 |
| --- | --- | --- |
| Spotless 3.12.0 + google-java-format 1.37.0 + `<removeUnusedImports/>` | 每个文件都报 `google-java-format(java.lang.reflect.InvocationTargetException)` | 不可用 |
| 去掉 `<removeUnusedImports/>`（它会另外拉取 Spotless 默认的 2.0.0） | 同上 | 不可用 |
| 直接调用 Spotless 胶水类 `GoogleJavaFormatFormatterFunc` 复现 | `NoSuchMethodError: JavaFormatterOptions$Style.valueOf(String)`：1.37.0 中 `Style` 已改为 record，Spotless 4.12 胶水按 2.x API 编译 | 根因确认 |
| 同一胶水 + google-java-format **2.0.0**，带 `.mvn/jvm.config` 的 `--add-exports` | 正常格式化 | **采用** |
| 同上但不带 `--add-exports` | `IllegalAccessError ... jdk.compiler does not export com.sun.tools.javac...` | `.mvn/jvm.config` 必需 |
| `./tools/dev/mvnw spotless:check`（2.0.0） | 约 2 秒报告 `The following files had format violations` | 可运行 |

处理：google-java-format 版本由 1.37.0 改为 2.0.0（与 research §1 的保守选择不同；2.0.0 的字节码目标同为 Java 21）。Spotless 的 `googleJavaFormat` 步骤本身就会删除未使用的 import 并排序，因此不再单独配置 `removeUnusedImports`。

## G2–G4 Checkstyle / PMD / SpotBugs

格式检查绑定在 `process-sources`，现有代码格式化之前无法编译，`tools/dev` 又禁止跳过检查。因此这三项在 T020 全仓格式化之后实测。

**完整构建**：`./tools/dev/mvnw -B clean verify`，692 秒，九个模块 Maven 均 SUCCESS，`Test evidence PASS: 322 required cases`。门禁按预期因无基线失败，首行为 `Quality gate FAIL: G2(checkstyle) 1174 new, G3(pmd) 895 new, G4(spotbugs) 48 new`。耗时包含首次下载插件依赖；与 T0 的正式对比在 T024/T074 用缓存就绪的构建测量。

**PMD 规则集加载**：没有未知规则名。`AvoidCatchingNPE` 与 `AvoidCatchingThrowable` 能加载，但 PMD 7.29.0 将其标为弃用（PMD 8 移除）。已改为 `errorprone.xml/AvoidCatchingGenericException`，并把 `typesThatShouldNotBeCaught` 限定为 `NullPointerException,Throwable`，与原两条规则语义一致；改后不再有弃用警告。

**SpotBugs**：九个模块全部生成 `spotbugsXml.xml`。

**单项耗时**（编译产物已存在，离线 `-o`，9 个模块；以 `compile` 单独运行的 1 秒为基准）：

| 命令 | 秒 | 工具净耗时约 |
| --- | --- | --- |
| `spotless:check` | 2 | 2 |
| `compile checkstyle:check` | 5 | 4 |
| `compile pmd:pmd` | 5 | 4 |
| `compile spotbugs:spotbugs` | 22 | 21 |

**发现**：单独执行 `pmd:pmd`（不带 `compile`）会因无法解析 reactor 内兄弟模块失败（`Could not resolve dependencies ... oryxos-core`）。提交前钩子因此执行 `compile pmd:pmd checkstyle:checkstyle`，而不是 T060 原文的 `spotless:check pmd:pmd checkstyle:checkstyle`（`compile` 已经过 `process-sources` 上的 `spotless:check`）。
