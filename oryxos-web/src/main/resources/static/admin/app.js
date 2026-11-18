import {get, saveUser, userId} from './api.js';
import {el, button, notice, failure} from './ui.js';
import {renderReadView} from './read-views.js';
import {installControls, renderRuntime} from './control-views.js';

const views = {
  agents: ['Agent', '查看已加载配置，发起一次有记录的任务。'],
  sessions: ['会话历史', '按身份与入口查看完整对话，保留会话创建时的模型信息。'],
  memory: ['共享记忆', '已确认的长期记忆，在同一工作区的 Agent 之间共享。'],
  schedules: ['定时任务', '查看文件定义、控制运行状态，核对每一次触发。'],
  executions: ['执行记录', '从最终回复回到模型调用、工具动作与记录证据。'],
  runtime: ['故障与恢复', '先核查动作与记录缺口，再明确确认恢复新执行。']
};
let view = 'agents', detailGeneration = 0, runtimeGeneration = 0;
const ctx = {
  content: document.querySelector('#content'), detail: document.querySelector('#detail'), actionNotice: document.querySelector('#action-notice'),
  runtime: null, info: null, pending: false, recoveryForm: null,
  timezone: () => ctx.info?.timezone || 'Asia/Shanghai',
  canRun: () => ctx.runtime?.state === 'READY' && !ctx.pending && Boolean(userId()),
  setRuntime(state) {
    const old = ctx.runtime;ctx.runtime = state;
    document.querySelector('#runtime-status').textContent = state?.state || '状态未知';
    const n = document.querySelector('#runtime-notice');
    n.replaceChildren(notice(!state ? '无法确认工作区状态，新执行已禁用。' : state.state === 'READY' ? `工作区就绪 · 在途调用 ${state.inFlightCount} · 当前用户标识：${userId() || '尚未设置，请先保存'}` : `工作区暂停或未就绪（${state.state}），新执行已禁用。${state.safeReason || ''}`, state?.state === 'READY' ? 'success' : 'warning'));
    if (state?.recoveryRequired) n.append(el('a', {href: '#runtime'}, '查看故障与恢复 →'));
    if (ctx.recoveryForm && (state?.incidentId !== ctx.recoveryForm.incidentId || !state?.recoveryRequired)) {ctx.recoveryForm.checkbox.checked = false;if(view === 'runtime') ctx.refreshView();}
    else if (view === 'runtime' && old?.incidentId !== state?.incidentId) ctx.refreshView();
    ctx.updateControls();
  },
  async refreshRuntime() {
    const generation = ++runtimeGeneration;
    try {const state = await get('/runtime/state');if(generation === runtimeGeneration) ctx.setRuntime(state);return generation === runtimeGeneration ? state : ctx.runtime;}
    catch(error) {if(generation === runtimeGeneration){ctx.setRuntime(null);document.querySelector('#runtime-notice').append(failure(error));}return null;}
  },
  updateControls() {
    document.querySelectorAll('[data-execute]').forEach(b => {b.disabled = !ctx.canRun() || b.dataset.unavailable === 'true';});
    const r = ctx.recoveryForm;
    if (r && r.submit.isConnected) {
      const ready = !ctx.pending && userId() && ctx.runtime?.incidentId === r.incidentId && ctx.runtime?.recoveryRequired && ctx.runtime?.inFlightCount === 0;
      r.submit.disabled = !ready || !r.checkbox.checked || !r.notes.value.trim();
      document.querySelector('#recovery-block').textContent = ready ? '勾选确认并填写说明后可提交。' : '恢复暂不可提交：请确认用户标识、当前故障且没有在途调用；后端会重新检查存储可写性。';
    }
    document.querySelector('#identity-form button').disabled = ctx.pending;
    document.querySelector('#user-id').disabled = ctx.pending;
  },
  detailHost(title) {
    detailGeneration++;ctx.detail.hidden = false;const body = el('div');
    ctx.detail.replaceChildren(el('div', {class: 'card-heading'}, el('h2', {}, title), button('关闭详情', () => {detailGeneration++;ctx.detail.hidden = true;}, {class: 'quiet'})), body);
    ctx.detail.focus();return body;
  },
  async readDetail(title, load, render) {
    const host = ctx.detailHost(title), generation = detailGeneration;host.append(notice('正在加载详情…'));
    try {const data = await load();if(generation === detailGeneration) host.replaceChildren(render(data));}
    catch(error){if(generation === detailGeneration) host.replaceChildren(failure(error), button('重新查询详情', () => ctx.readDetail(title, load, render)));}
  },
  refreshView() {
    ctx.content.replaceChildren();ctx.recoveryForm = null;
    if(view === 'runtime') renderRuntime(ctx);else renderReadView(ctx, view);
    ctx.updateControls();
  }
};
installControls(ctx);
document.querySelector('.skip-link').addEventListener('click', event => {
  event.preventDefault();document.querySelector('#main').focus();
});
function navigate() {
  view = Object.hasOwn(views, location.hash.slice(1)) ? location.hash.slice(1) : 'agents';detailGeneration++;ctx.detail.hidden = true;
  document.querySelector('#page-title').textContent = views[view][0];document.querySelector('#page-description').textContent = views[view][1];
  document.querySelectorAll('nav a').forEach(link => {if(link.dataset.view === view)link.setAttribute('aria-current', 'page');else link.removeAttribute('aria-current');});
  ctx.refreshView();
}
document.querySelector('#user-id').value = userId();
document.querySelector('#identity-form').addEventListener('submit', event => {
  event.preventDefault();ctx.actionNotice.hidden = false;
  try {const stored = saveUser(document.querySelector('#user-id').value);ctx.actionNotice.replaceChildren(notice(stored ? `已保存用户标识：${userId()}。这不是登录认证。` : `本次用户标识：${userId()}；浏览器未允许持久保存。`, 'success'));detailGeneration++;ctx.detail.hidden = true;ctx.setRuntime(ctx.runtime);}
  catch(e){ctx.actionNotice.replaceChildren(failure(e));}
});
document.querySelector('#refresh').addEventListener('click', async () => {await ctx.refreshRuntime();ctx.refreshView();});
window.addEventListener('hashchange', navigate);
try {const pending = sessionStorage.getItem('oryxos.admin.unconfirmedWrite');if(pending){ctx.actionNotice.hidden = false;ctx.actionNotice.append(notice(`上次请求未收到确认：${pending}。请先核查执行记录，不要重复发送。`, 'warning'), el('a', {href: '#executions'}, '查看执行记录'));}} catch { /* best effort */ }
navigate();ctx.refreshRuntime();
get('/info').then(info => {ctx.info = info;document.querySelector('#workspace-id').textContent = info.workspaceId;document.querySelector('#timezone').textContent = info.timezone;document.querySelector('#backend').textContent = info.memoryBackend;document.querySelector('#version').textContent = `v${info.version}`;}).catch(error => {document.querySelector('#workspace-id').textContent = '工作区信息不可用';ctx.actionNotice.hidden = false;ctx.actionNotice.append(failure(error));});
// Background polling is GET-only. Every explicit mutation also checks fresh state before dispatch.
setInterval(() => {if(!document.hidden && !ctx.pending)ctx.refreshRuntime();}, 15000);
