<script setup lang="ts">
import { computed } from 'vue'
import { useData, withBase } from 'vitepress'
import architecture from '../../../../docs/images/architecture.svg'
import '../home.css'

const { lang } = useData()
const isZh = computed(() => lang.value === 'zh-CN')
const t = (zh: string, en: string) => isZh.value ? zh : en
const doc = (name: string) => withBase(`${isZh.value ? '/zh' : ''}/docs/${name}.html`)
const repo = 'https://github.com/jianghr-rr/OryxOS'

const capabilities = computed(() => [
  { icon: '◈', title: t('模型接入', 'Provider'), subtitle: t('显式映射 · 多模型适配', 'Explicit routing · model adapters'),
    code: 'Agent Profile\n  → ProviderService\n  → Named ChatModel\n  → Model API / local inference' },
  { icon: '↻', title: t('自主推理循环', 'ReAct loop'), subtitle: t('自实现循环 · 工具结果回填', 'Own the loop · observe tool results'),
    code: 'Reason → Act → Observe\n   ↑                │\n   └────────────────┘\nFinal response / iteration limit' },
  { icon: '▤', title: t('跨对话记忆', 'Memory'), subtitle: t('统一门面 · 可替换后端', 'One interface · pluggable stores'),
    code: 'MemoryService\n  ├─ Session history\n  └─ Long-term memory\n     Markdown / SQLite / Mem0' },
  { icon: '⌘', title: t('工具与 MCP', 'Tools & MCP'), subtitle: t('文件 · Shell · HTTP · 记忆 · 通知', 'Files · Shell · HTTP · memory · notifications'),
    code: 'Model tool call\n  → ToolExecutor → OryxTool\n  → Built-in / Java / MCP\n  → Result + audit record' },
  { icon: '↗', title: t('对外服务', 'Web Service'), subtitle: t('REST API · 业务系统接入', 'REST API · integrate with your systems'),
    code: 'CLI / REST / Scheduler\n  → AgentService\n  → ReAct + Provider + Memory + Tools\n  → Response + persisted session' },
])

const scenarios = computed(() => [
  [t('每日天气', 'Daily weather'), t('定时获取天气，整理穿搭建议，再推送到配置好的通知渠道。', 'Fetch the weather on a schedule, prepare clothing suggestions, and send a notification.')],
  [t('科技日报', 'Technology digest'), t('按记忆中的关注方向整理新闻，按需读取组稿规范，生成每日简报。', 'Use remembered interests and on-demand editorial instructions to prepare a daily news digest.')],
  [t('GitHub 日报', 'GitHub digest'), t('运行 Agent 目录中的采集脚本，将结构化结果整理成项目摘要。', 'Run a bundled collection script and turn structured results into a project digest.')],
  [t('运维助手', 'Operations assistant'), t('连接日志、监控和运维工具，辅助分析告警并记录处理过程。', 'Connect logs, monitoring, and operational tools to investigate alerts and record the work.')],
  [t('研发助手', 'Engineering assistant'), t('通过 MCP 接入代码与评审信息，生成变更摘要和待办建议。', 'Access code and review data through MCP to prepare change summaries and suggested next steps.')],
  [t('知识助手', 'Knowledge assistant'), t('组合内部检索工具和用户上下文，为团队提供知识查询入口。', 'Combine internal search tools with user context to help teams find relevant knowledge.')],
  [t('客户洞察', 'Customer insights'), t('接入企业 CRM 与业务 API，将客户背景整理为拜访简报。', 'Connect CRM and business APIs to assemble customer context into a briefing.')],
  [t('业务数据分析', 'Business analysis'), t('调用受控的数据工具，结合业务说明整理分析结果。', 'Use controlled data tools and business instructions to prepare analytical summaries.')],
])

const integrations = computed(() => [
  { icon: '📁', title: t('指令 + 现成 MCP', 'Instructions + existing MCP'),
    description: t('用一个 Agent 目录描述任务，复用现成工具。业务方从 Markdown 和配置开始。', 'Describe a task in an Agent directory and reuse existing tools. Start with Markdown and configuration.'),
    code: 'agents/my-agent/\n├── AGENT.md\n├── skills/\n└── scripts/', link: 'agents' },
  { icon: '🔌', title: t('自定义 MCP Server', 'Custom MCP server'),
    description: t('用熟悉的语言包装业务系统，通过 MCP 暴露工具，复用统一执行链路。', 'Wrap your business systems in your preferred language and expose tools through MCP.'),
    code: 'Python · TypeScript · Go\nJava · Rust · …\n\nMCP → OryxTool', link: 'tools' },
  { icon: '☕', title: t('原生 Java 工具', 'Native Java tools'),
    description: t('通过 @Tool 标注 Java 方法，复用 Spring Bean 和已有企业服务。', 'Expose Java methods with @Tool and reuse Spring beans and existing enterprise services.'),
    code: '@Tool\nJava method\n  → JSON Schema\n  → ToolExecutor', link: 'tools' },
])

const endpoints = computed(() => [
  { title: t('会话', 'Sessions'), rows: [
    ['POST /api/v1/sessions', t('创建会话', 'Create a session')],
    ['POST /api/v1/sessions/{id}/messages', t('向会话发送消息', 'Send a message')],
    ['GET /api/v1/sessions/{id}', t('查询对话历史', 'Read conversation history')],
    ['DELETE /api/v1/sessions/{id}', t('归档会话', 'Archive a session')],
  ] },
  { title: t('Agent 与运行资源', 'Agents & runtime resources'), rows: [
    ['POST /api/v1/agents/{name}/invoke', t('调用 Agent', 'Invoke an agent')],
    ['GET /api/v1/profiles', t('查询运行配置', 'List profiles')],
    ['GET /api/v1/memory', t('查询长期记忆', 'Read long-term memory')],
    ['GET /api/v1/tools', t('查询工具', 'List tools')],
  ] },
  { title: t('系统', 'System'), rows: [
    ['GET /api/v1/health', t('健康检查', 'Health check')],
    ['GET /api/v1/info', t('运行信息', 'Runtime information')],
  ] },
])
const buildCommand = './mvnw clean package\n\n# CLI\njava -jar oryxos-cli/target/oryxos-cli-1.0.0-SNAPSHOT.jar\n\n# HTTP application skeleton\njava -jar oryxos-boot/target/oryxos-boot-1.0.0-SNAPSHOT.jar'
</script>

<template>
  <div class="oryx-page">
    <section class="oryx-hero">
      <div class="oryx-hero-inner">
        <div class="oryx-badge"><span class="oryx-badge-dot" aria-hidden="true"></span>{{ t('Java 原生 · 私有部署 · Agent 运行底座', 'Java-native · Self-hosted · Agent infrastructure') }}</div>
        <h1 class="oryx-title"><span class="oryx-title-name">OryxOS</span></h1>
        <p class="oryx-title-sub">{{ t('让一群 Agent，运行在同一套底座上', 'One runtime. A team of agents.') }}</p>
        <p class="oryx-hero-desc">{{ t('一份配置定义一个 Agent。模型接入、推理循环、记忆、工具和审计，交给统一的 Agent Harness OS。面向企业私有环境，从单机内核开始。', 'Define an agent with configuration. Bring model access, reasoning, memory, tools, and audit into one Agent Harness OS. Built for private environments, starting with a single-node runtime.') }}</p>
        <div class="oryx-hero-actions">
          <a class="oryx-btn-primary" :href="doc('quick-start')">{{ t('开始构建', 'Get Started') }} →</a>
          <a class="oryx-btn-ghost" :href="doc('architecture')">{{ t('系统架构', 'Architecture') }}</a>
          <a class="oryx-btn-ghost" :href="repo" target="_blank" rel="noopener">GitHub</a>
        </div>
        <div class="oryx-hero-note">JDK 21 · Spring Boot · ReAct · MCP · REST API</div>
        <p class="oryx-status-note">{{ t('当前：九模块工程骨架、CLI 版本入口和 HTTP 启动入口已就绪。Agent 核心能力正在建设。', 'Current status: nine-module scaffold, CLI version entry, and HTTP application startup are ready. Agent capabilities are under development.') }}</p>
      </div>
    </section>

    <section class="oryx-section">
      <div class="oryx-section-inner oryx-problem">
        <div class="oryx-problem-text">
          <h2 class="oryx-section-title">{{ t('从模型回答，到 Agent 做事', 'From model responses to agent work') }}</h2>
          <p>{{ t('把模型接进来只是起点。真正运行 Agent，还需要上下文、工具边界和可持续的状态。', 'Connecting a model is the beginning. Running agents takes context, controlled tools, and durable state.') }}</p>
          <p class="oryx-problem-item"><strong>{{ t('① 一个 Agent 如何可靠运行？', '① How does an agent run reliably?') }}</strong>{{ t('控制推理循环，保留记忆，让工具执行有边界、过程有记录。', 'Control the reasoning loop, preserve memory, constrain tools, and record what happened.') }}</p>
          <p class="oryx-problem-item"><strong>{{ t('② 多个 Agent 如何共用基础能力？', '② How do agents share infrastructure?') }}</strong>{{ t('统一接入模型、工具与业务系统，用配置表达不同的业务任务。', 'Share model access, tools, and business integrations while defining each task through configuration.') }}</p>
          <p class="oryx-solution-line">{{ t('OryxOS 专注运行底座，让业务方专注于 Agent 要完成的工作。', 'OryxOS focuses on the runtime so teams can focus on the work their agents should do.') }}</p>
        </div>
        <div class="oryx-problem-compare">
          <div class="oryx-compare-item oryx-compare-bad">
            <div class="oryx-compare-label">{{ t('每个 Agent 都要面对', 'Every agent needs') }}</div>
            <div class="oryx-compare-rows">
              <div v-for="item in [t('模型与工具的接入', 'Model and tool integration'), t('上下文、会话与长期记忆', 'Context, sessions, and long-term memory'), t('工具权限与执行记录', 'Tool boundaries and execution records')]" :key="item" class="oryx-compare-row"><span class="oryx-compare-icon">→</span><span>{{ item }}</span></div>
            </div>
          </div>
          <div class="oryx-compare-item oryx-compare-good">
            <div class="oryx-compare-label">OryxOS</div>
            <div class="oryx-compare-rows">
              <div v-for="item in [t('一套引擎，三个触发入口', 'One engine, three trigger paths'), t('一个目录，定义一个 Agent', 'One directory defines an agent'), t('统一记忆、工具与审计接口', 'Shared memory, tool, and audit interfaces'), t('单机先行，逐步演进分布式', 'Single-node first, distributed over time')]" :key="item" class="oryx-compare-row"><span class="oryx-compare-icon oryx-icon-ok">✓</span><span>{{ item }}</span></div>
            </div>
          </div>
        </div>
      </div>
    </section>

    <section class="oryx-section oryx-flow-section">
      <div class="oryx-section-inner">
        <a :href="doc('architecture')"><img :src="architecture" :alt="t('OryxOS 单机运行时目标架构', 'OryxOS target architecture for the single-node runtime')" class="oryx-flow-img" width="1440" height="1320" loading="lazy" /></a>
        <p class="oryx-flow-caption">{{ t('单机阶段目标架构 · 点击查看设计说明', 'Single-node target architecture · Explore the design') }}</p>
      </div>
    </section>

    <section class="oryx-section oryx-primitives-section">
      <div class="oryx-section-inner oryx-primitives-inner">
        <div class="oryx-section-header"><div class="oryx-section-tag">{{ t('核心能力', 'Core capabilities') }}</div><h2 class="oryx-section-title">{{ t('五大能力，一条完整执行链路', 'Five capabilities. One execution loop.') }}</h2><p class="oryx-section-desc">{{ t('以下为运行时内核的设计目标，逐步实现与验证。', 'The runtime design below is being implemented and validated incrementally.') }}</p></div>
        <div class="oryx-primitives">
          <div v-for="item in capabilities" :key="item.title" class="oryx-primitive">
            <div class="oryx-primitive-header"><span class="oryx-primitive-icon" aria-hidden="true">{{ item.icon }}</span><div><h3 class="oryx-primitive-title">{{ item.title }}</h3><p class="oryx-primitive-subtitle">{{ item.subtitle }}</p></div></div>
            <pre class="oryx-code"><code>{{ item.code }}</code></pre>
          </div>
        </div>
      </div>
    </section>

    <section class="oryx-section">
      <div class="oryx-section-inner">
        <div class="oryx-section-header"><div class="oryx-section-tag">{{ t('应用方向', 'Use cases') }}</div><h2 class="oryx-section-title">{{ t('同一套底座，不同的业务 Agent', 'Shared infrastructure. Different agents.') }}</h2><p class="oryx-section-desc">{{ t('从三个计划中的验收 Demo 出发，扩展到企业业务场景。', 'Start with three planned validation demos, then extend to enterprise use cases.') }}</p></div>
        <div class="oryx-scenarios"><div v-for="(item, index) in scenarios" :key="index" class="oryx-scenario"><div class="oryx-scenario-num">{{ String(index + 1).padStart(2, '0') }}</div><div><h3 class="oryx-scenario-title">{{ item[0] }}</h3><p class="oryx-scenario-desc">{{ item[1] }}</p></div></div></div>
      </div>
    </section>

    <section class="oryx-section oryx-sdk-section">
      <div class="oryx-section-inner">
        <div class="oryx-section-header"><div class="oryx-section-tag">{{ t('扩展设计', 'Extension design') }}</div><h2 class="oryx-section-title">{{ t('三种接入方式，按需选择', 'Three ways to extend. Pick what fits.') }}</h2></div>
        <div class="oryx-sdk-cards"><div v-for="(item, index) in integrations" :key="item.title" class="oryx-sdk-card" :class="{ 'oryx-sdk-card-featured': index === 1 }"><div class="oryx-sdk-card-icon" aria-hidden="true">{{ item.icon }}</div><h3 class="oryx-sdk-card-title">{{ item.title }}</h3><p class="oryx-sdk-card-desc">{{ item.description }}</p><pre class="oryx-code"><code>{{ item.code }}</code></pre><a :href="doc(item.link)">{{ t('查看设计', 'Explore the design') }} →</a></div></div>
      </div>
    </section>

    <section class="oryx-section">
      <div class="oryx-section-inner">
        <div class="oryx-section-header"><div class="oryx-section-tag">{{ t('API 设计', 'API design') }}</div><h2 class="oryx-section-title">{{ t('通过 HTTP，接入已有业务系统', 'Connect your systems over HTTP') }}</h2><p class="oryx-section-desc">{{ t('首阶段规划的十个 REST 端点。当前工程骨架尚未实现这些接口。', 'Ten REST endpoints planned for the first stage. These endpoints are not implemented in the current scaffold.') }}</p></div>
        <div class="oryx-proto-grid"><div v-for="group in endpoints" :key="group.title" class="oryx-proto-group"><div class="oryx-proto-group-label">{{ group.title }}</div><div v-for="row in group.rows" :key="row[0]" class="oryx-proto-row"><code class="oryx-proto-subject">{{ row[0] }}</code><span class="oryx-proto-desc">{{ row[1] }}</span></div></div></div>
      </div>
    </section>

    <section class="oryx-section oryx-cta-section">
      <div class="oryx-section-inner oryx-cta">
        <h2 class="oryx-cta-title">{{ t('从一次本地构建开始', 'Start with a local build') }}</h2>
        <p class="oryx-cta-desc">{{ t('克隆仓库，使用 JDK 21 和 Maven Wrapper 运行当前工程。', 'Clone the repository and run the current scaffold with JDK 21 and the Maven Wrapper.') }}</p>
        <pre class="oryx-code oryx-cta-code"><code>{{ buildCommand }}</code></pre>
        <div class="oryx-cta-links"><a class="oryx-btn-primary" :href="doc('quick-start')">{{ t('构建与运行', 'Build & run') }}</a><a class="oryx-btn-ghost" :href="repo" target="_blank" rel="noopener">GitHub</a></div>
      </div>
    </section>
    <footer class="oryx-footer"><span>OryxOS · oryx-labs</span><a :href="doc('contributing')">{{ t('一起构建 Agent 运行底座', 'Build agent infrastructure with us') }} →</a></footer>
  </div>
</template>
