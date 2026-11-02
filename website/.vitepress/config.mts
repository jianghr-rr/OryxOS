import { defineConfig } from 'vitepress'

const base = '/OryxOS/'
const origin = 'https://jianghr-rr.github.io'
const repository = 'https://github.com/jianghr-rr/OryxOS'

function navigation(zh: boolean) {
  const prefix = zh ? '/zh' : ''
  return [
    { text: zh ? '首页' : 'Home', link: `${prefix}/` },
    { text: zh ? '文档' : 'Docs', link: `${prefix}/docs/what` },
    { text: zh ? '路线图' : 'Roadmap', link: `${prefix}/docs/roadmap` },
    { text: zh ? '参与贡献' : 'Contribute', link: `${prefix}/docs/contributing` },
  ]
}

function sidebar(zh: boolean) {
  const prefix = zh ? '/zh/docs/' : '/docs/'
  const item = (name: string, en: string, cn: string) => ({
    text: zh ? cn : en, link: `${prefix}${name}`,
  })
  return {
    [prefix]: [
      { text: zh ? '快速入门' : 'Getting started', items: [
        item('what', 'What is OryxOS?', 'OryxOS 是什么'),
        item('quick-start', 'Build & run', '构建与运行'),
      ] },
      { text: zh ? '设计与能力' : 'Design & capabilities', items: [
        item('architecture', 'Architecture', '系统架构'),
        item('agents', 'Define an Agent', '定义 Agent'),
        item('tools', 'Tools & integration', '工具与接入'),
      ] },
      { text: zh ? '项目' : 'Project', items: [
        item('roadmap', 'Roadmap', '路线图'),
        item('contributing', 'Contributing', '参与贡献'),
      ] },
    ],
  }
}

export default defineConfig({
  title: 'OryxOS',
  description: 'A Java-native, self-hosted AI Agent Harness OS. One runtime for a team of agents.',
  base,
  srcExclude: ['README.md'],
  cleanUrls: false,
  appearance: 'force-light',
  head: [
    ['link', { rel: 'icon', type: 'image/svg+xml', href: `${base}favicon.svg` }],
    ['meta', { name: 'theme-color', content: '#ffffff' }],
    ['meta', { property: 'og:site_name', content: 'OryxOS' }],
    ['meta', { property: 'og:type', content: 'website' }],
  ],
  transformHead({ pageData }) {
    if (pageData.isNotFound) return []
    const path = pageData.relativePath.replace(/index\.md$/, '').replace(/\.md$/, '.html')
    const url = `${origin}${base}${path}`
    return [
      ['link', { rel: 'canonical', href: url }],
      ['meta', { property: 'og:url', content: url }],
      ['meta', { property: 'og:title', content: `${pageData.title || 'OryxOS'} · OryxOS` }],
      ['meta', { property: 'og:description', content: pageData.description }],
    ]
  },
  locales: {
    root: {
      label: 'English', lang: 'en-US',
      themeConfig: { nav: navigation(false), sidebar: sidebar(false) },
    },
    zh: {
      label: '简体中文', lang: 'zh-CN', link: '/zh/',
      description: 'Java 原生、可私有部署的 AI Agent Harness OS。一份配置定义一个 Agent，一个底座运行一群 Agent。',
      themeConfig: {
        nav: navigation(true), sidebar: sidebar(true),
        outline: { label: '本页内容' },
        docFooter: { prev: '上一页', next: '下一页' },
        sidebarMenuLabel: '目录', returnToTopLabel: '返回顶部',
        darkModeSwitchLabel: '外观', langMenuLabel: '语言',
      },
    },
  },
  themeConfig: {
    siteTitle: false,
    logo: { src: '/brand-logo.svg', alt: 'OryxOS home' },
    socialLinks: [{ icon: 'github', link: repository }],
    search: { provider: 'local' },
    editLink: { pattern: `${repository}/edit/main/website/:path` },
  },
  sitemap: { hostname: `${origin}${base}` },
})
