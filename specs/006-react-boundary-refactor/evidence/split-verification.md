# 纯迁移验证（T003–T007）

- `ReActContractTest` 6 个方法原样迁入：`ReActLoopTest` 3 个、`ContextLoaderTest` 3 个；断言未改，原类已删除。
- `tools/dev/test-groups.json` 的 `us2-loop`：`ReActContractTest` 条目拆为两个新类条目，登记数仍为 30。

```text
./tools/dev/mvnw -B -ntp test -Dgroups=us2-loop
Tests run: 3 ... in io.github.oryxlabs.oryxos.core.context.ContextLoaderTest
Tests run: 3 ... in io.github.oryxlabs.oryxos.core.react.ReActLoopTest
BUILD SUCCESS
Test evidence PASS: 30 required cases; run db7cea78-b8f3-4631-8c9e-1403e27ea8a3
```

方法名比对（quickstart §3）：`comm -23 react-contract-methods-before.txt after.txt` 输出为空。
