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

格式检查绑定在 `process-sources`，现有代码格式化之前无法编译，`tools/dev` 又禁止跳过检查。因此这三项在 T020 全仓格式化之后实测，结果补在下方。
