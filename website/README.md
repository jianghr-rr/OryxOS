# OryxOS website

中英文项目首页与文档，使用 VitePress，独立于 Maven 工程。默认英文首页位于 `/OryxOS/`，中文首页位于 `/OryxOS/zh/`。

## 本地开发

使用 Node.js 22 或更新版本，在仓库根目录执行：

```bash
npm ci
npm run docs:dev
```

打开终端输出的地址，默认是 `http://127.0.0.1:5173/OryxOS/`。

```bash
npm run docs:build
npm run docs:preview -- --port 4173
```

生产预览地址为 `http://127.0.0.1:4173/OryxOS/`。静态产物输出到 `website/.vitepress/dist/`，不提交产物、缓存或 `node_modules`。

## 文件结构

| 文件 | 用途 |
| --- | --- |
| `.vitepress/config.mts` | 子路径、语言、导航、搜索和 sitemap |
| `.vitepress/theme/components/Home.vue` | 中英文首页内容与区块 |
| `.vitepress/theme/home.css` | 首页布局和响应式样式 |
| `.vitepress/theme/custom.css` | VitePress 文档与导航主题 |
| `index.md`、`zh/index.md` | 两种语言的首页入口 |
| `docs/`、`zh/docs/` | 两种语言的入门与设计文档 |
| `public/` | Logo 和 favicon |

架构图直接导入仓库的 `docs/images/architecture.svg`，与项目 README 共用同一源文件。导航 Logo 来自 `docs/images/logo.svg`，去掉了自动深色配色以适配全站浅色主题；更新品牌图时同步 `public/brand-logo.svg` 和图标。

首页内容以仓库设计文档为依据。当前能运行的是 Maven 工程骨架、CLI 版本入口和 HTTP 应用启动；Agent、模型、记忆、工具及业务 API 必须继续标注为设计或规划，直到实现并验证。

## GitHub Pages

发布目标：[https://jianghr-rr.github.io/OryxOS/](https://jianghr-rr.github.io/OryxOS/)。

1. 在 GitHub 仓库 Settings → Pages 中，将 Build and deployment 的 Source 设为 **GitHub Actions**。
2. Custom domain 留空；仓库不需要 `CNAME` 文件。
3. 提交并推送网站相关文件到 `main`，或在 Actions 中手动运行 **Deploy website to GitHub Pages**。
4. 等待 build 和 deploy 都成功，再访问目标地址。

工作流位于 `../.github/workflows/deploy-website.yml`。启用 Pages 和首次发布需在 GitHub 上实际完成，本地构建不代表站点已经上线。

新增站内链接时使用 VitePress 的 `withBase()`，文档页保持 `.html` 路径，避免 GitHub Pages 子路径下的静态资源或直达链接失效。将来更换域名时，需一起修改 `config.mts` 的 `base`、`origin`，并配置 Pages 自定义域名和 DNS。

## 维护与验证

修改后运行 `npm run docs:build`，并检查首页、语言切换、搜索、文档跳转以及移动端排版。发布前可运行 `npm audit` 检查依赖。

保留 VitePress 1.6.4，与参考站技术栈一致。其默认 Vite 5 依赖包含已知漏洞，因此根 `package.json` 通过 `overrides` 固定 Vite 6.4.5；现用 Vue 插件支持 Vite 6。升级 VitePress 后应重新评估此覆盖，并验证开发服务器、静态构建与预览。

布局和基础样式参考 [robustmq/mq9 的 website](https://github.com/robustmq/mq9/tree/37dbe403228587e383244e1786e3879ff24702d1/website)：白底大标题、对比区块、架构图、能力卡片、场景、接入方式和行动入口。文案与素材已替换为 OryxOS，未接入参考站的统计或评论配置。
