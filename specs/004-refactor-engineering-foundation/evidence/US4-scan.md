# US4：依赖扫描拦截验证（T068）

日期 2026-11-23。验证分支 `004-verify-cve-scan`（[PR #1](https://github.com/jianghr-rr/OryxOS/pull/1)，不合入）在 `oryxos-boot/pom.xml` 中加入 `commons-collections:commons-collections:3.2.1`。除这一处外，它与功能分支 `004-refactor-engineering-foundation`（[PR #2](https://github.com/jianghr-rr/OryxOS/pull/2)）的代码完全相同：每次修改功能分支后都会把它合并进验证分支。

| 场景 | 运行 | 结果 |
| --- | --- | --- |
| 加入带已知高危 CVE 的依赖 | PR #1 的 `Dependency Scan`（head `29389fc` 合并后） | 失败，只列出 `commons-collections-3.2.1.jar (pkg:maven/commons-collections/commons-collections@3.2.1, cpe:2.3:a:apache:commons_collections:3.2.1:…): CVE-2015-6420(9.8)` |
| 不含该依赖 | [PR #2 的 `Dependency Scan` 45127674169](https://github.com/jianghr-rr/OryxOS/actions/runs/45127674169)（head `29389fc`） | 通过，用时 36 秒（命中漏洞库缓存） |
| 带理由的抑制生效 | 上述两次运行 | 自有 CLI 模块被误配为 GitHub CLI、httpcore5/httpcore5-h2 5.5 被误配为预发布版，以及 Spring Framework 6.2.19、Spring AI 1.1.8 的已接受例外，都没有再报出 |

失败输出里包含依赖坐标（purl）、CVE 编号和 CVSS 分数，满足 spec 中 US4 验收场景 1；PR #2 通过满足场景 2；抑制生效且每条都写了理由，满足场景 3。

PR #2 在变绿之前经过了几轮复扫，存量漏洞的升级、误报和已接受例外的逐条依据见 [US4-cve-triage.md](./US4-cve-triage.md)。
