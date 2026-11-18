import {get, segment, userId} from './api.js';
import {el, button, facts, pre, date, notice, failure, executionSummary, details, plain} from './ui.js';

/** A failed next page retains both the visible rows and the cursor that failed. */
export function pager(host, {path, params = {}, render, items = d => d.items, before, empty = '暂无记录。', key = item => item.id ?? item.name}) {
  const rows = el('div', {class: 'rows'}), status = el('div', {role: 'status', 'aria-live': 'polite'});
  const seen = new Set(); let cursor = null, loading = false, count = 0;
  const next = button('加载更多', () => load(), {class: 'secondary', hidden: true});
  host.append(rows, status, next);
  async function load() {
    if (loading) return; loading = true; next.disabled = true; status.replaceChildren(notice('正在加载…')); host.setAttribute('aria-busy', 'true');
    try {
      const data = await get(path, {...params, limit: 20, cursor});
      if (!host.isConnected) return;
      before?.(data, count === 0);
      for (const item of items(data)) if (!seen.has(key(item))) { seen.add(key(item)); rows.append(render(item)); count++; }
      cursor = data.nextCursor ?? null;
      status.replaceChildren(notice(count ? `已显示 ${count} 条${cursor ? '，可继续加载。' : '，已到末页。'}` : empty));
      next.hidden = !cursor;
    } catch (error) {
      if (host.isConnected) status.replaceChildren(failure(error), button('重新查询此页', load, {class: 'secondary'}));
    } finally { loading = false; next.disabled = false; host.removeAttribute('aria-busy'); }
  }
  load();
}
function filter(host, fields, apply) {
  const form = el('form', {class: 'filters'});
  for (const [name, title, options] of fields) {
    const input = options ? el('select', {name}, options.map(([value, label]) => el('option', {value}, label))) : el('input', {name, placeholder: '全部', autocomplete: 'off'});
    form.append(el('label', {}, title, input));
  }
  form.append(el('button', {type: 'submit', class: 'secondary'}, '筛选查询'));
  const results = el('div'); host.append(form, results);
  const refresh = () => { results.replaceChildren(); apply(results, Object.fromEntries(new FormData(form))); };
  form.addEventListener('submit', event => { event.preventDefault(); refresh(); }); refresh();
}
export function renderReadView(ctx, view) {
  const host = ctx.content;
  if (view === 'agents') pager(host, {path: '/profiles', empty: '没有已加载的 Agent。请配置工作区文件后重启服务。', render: a => {
    const action = button('运行 Agent', () => ctx.openRun(a), {'data-execute': true, disabled: !ctx.canRun() || a.availability !== 'AVAILABLE', 'data-unavailable': a.availability !== 'AVAILABLE', 'aria-label': `运行 Agent ${a.name}`, class: 'primary'});
    return el('article', {class: 'agent-card'}, el('div', {class: 'card-heading'}, el('div', {}, el('span', {class: 'tiny-label'}, 'AGENT'), el('h2', {}, a.name)), el('span', {class: `status ${a.availability === 'AVAILABLE' ? 'success' : 'danger'}`}, a.availability === 'AVAILABLE' ? '可用' : '不可用')),
      el('p', {class: 'description'}, a.description || '未提供说明'), a.safeError ? notice(a.safeError, 'danger') : null,
      facts([['模型服务', a.providerName || '未配置'], ['模型', a.model || '未配置'], ['允许工具', a.tools.join(' / ') || '无'], ['通知目标', a.notificationTargets.join(' / ') || '无'], ['定时任务', a.scheduleIds.join(' / ') || '无']]),
      details('配置与正文版本', facts([['启动配置', a.loadedRevision], ['当前正文', a.bodyRevision]])), el('div', {class: 'card-footer'}, el('small', {}, '一次性运行 · 独立会话'), action));
  }});
  if (view === 'sessions') filter(host, [['agentName', 'Agent'], ['userId', '用户标识'], ['channel', '入口', [['', '全部入口'], ['http', 'HTTP'], ['cli', 'CLI'], ['scheduler', '定时调度']]], ['archived', '归档状态', [['', '全部'], ['false', '活跃'], ['true', '已归档']]]], (results, params) => pager(results, {path: '/sessions', params, render: s => el('article', {class: 'record-row'}, el('div', {}, button(s.id, () => sessionHistory(ctx, s.id), {class: 'text-button mono'}), el('p', {}, `${s.identity.agentName} · ${s.identity.channel} / ${s.identity.userId}`)), el('div', {}, el('span', {class: 'status'}, `${s.status} · ${s.kind}`), el('p', {class: 'muted'}, date(s.createdAt, ctx.timezone()))))}));
  if (view === 'memory') {
    const core = el('div'), archival = el('div');host.append(notice('工作区共享：不同 Agent 读取同一组已确认记忆。切换后端不会迁移或合并数据。'), el('h2', {}, 'CORE · 核心记忆'), core, el('h2', {}, 'ARCHIVAL · 归档记忆'), archival);
    pager(archival, {path: '/memory', items: d => d.archival, before: (d, first) => { if (first) core.replaceChildren(notice(`当前后端：${d.backend} · 工作区共享`), ...d.core.map(m => memoryCard(ctx, m)), ...(d.core.length ? [] : [notice('暂无核心记忆。')])); }, render: m => memoryCard(ctx, m), empty: '暂无归档记忆。'});
  }
  if (view === 'schedules') filter(host, [['agentName', 'Agent']], (results, params) => pager(results, {path: '/schedules', params, render: s => el('article', {class: 'schedule-card'},
    el('div', {class: 'card-heading'}, el('h2', {}, s.id), el('span', {class: 'status'}, s.enabled ? '已启用' : '已停用')),
    facts([['文件定义', s.configuredPresent ? '存在' : '已移除，仅保留历史'], ['Cron', s.cron], ['业务时区', s.timezone], ['下次运行', date(s.nextRunAt, s.timezone)], ['上次状态', s.lastStatus ?? '尚未运行']]), pre(s.message),
    el('div', {class: 'actions'}, button(s.enabled ? '停用' : '启用', () => ctx.toggleSchedule(s), {'data-execute': true, 'data-unavailable': !s.configuredPresent, disabled: !s.configuredPresent || !ctx.canRun(), 'aria-label': `${s.enabled ? '停用' : '启用'} ${s.id}`}), button('立即执行', () => ctx.runSchedule(s), {'data-execute': true, 'data-unavailable': !s.configuredPresent, disabled: !s.configuredPresent || !ctx.canRun(), 'aria-label': `立即执行 ${s.id}`}), button('触发历史', () => scheduleHistory(ctx, s.id), {class: 'quiet', 'aria-label': `触发历史 ${s.id}`})), s.lastExecutionId ? button('查看上次执行', () => executionDetail(ctx, s.lastExecutionId), {class: 'text-button'}) : null)}));
  if (view === 'executions') filter(host, [['agentName', 'Agent'], ['sessionId', '会话 UUID']], (results, params) => pager(results, {path: '/executions', params, render: e => el('article', {class: 'record-row'}, el('div', {}, button(e.id, () => executionDetail(ctx, e.id), {class: 'text-button mono'}), el('p', {}, `${e.identity.agentName} · ${e.identity.channel} / ${e.identity.userId}`)), el('div', {}, executionSummary(e), el('p', {class: 'muted'}, date(e.startedAt, e.timezone))))}));
}
function memoryCard(ctx, m) { return el('article', {class: 'memory-card'}, pre(m.content), el('small', {class: 'muted'}, `来源 Agent：${m.sourceAgentName ?? '未知'} · ${date(m.createdAt, ctx.timezone())} · ${m.id}`)); }
export function executionBody(ctx, e) {
  return el('div', {}, executionSummary(e), facts([['执行 ID', e.id], ['会话 ID', e.sessionId], ['身份', `${e.identity?.channel} / ${e.identity?.userId} / ${e.identity?.agentName}`], ['执行状态', e.status], ['记录状态', e.recordingStatus], ['在途调用', e.inFlightCount], ['停止原因', e.stopReason], ['业务时区', e.timezone], ['开始 / 截止', `${date(e.startedAt, e.timezone)} / ${date(e.deadlineAt, e.timezone)}`], ['完成时间', date(e.finishedAt, e.timezone)], ['曾有 / 未解决工具错误', `${plain(e.hasToolErrors)} / ${plain(e.hasUnresolvedToolErrors)}`]]),
    el('h3', {}, '最终回复'), pre(e.content ?? '尚无已确认回复'), e.error ? notice(`${e.error.code} · ${e.error.message}`, 'danger') : null,
    el('h3', {}, '模型调用'), ...(e.llmCalls?.map(a => details(`第 ${a.iteration} 轮 · ${a.providerName} / ${a.model} · ${a.status}`, facts([['尝试 ID', a.attemptId], ['耗时 ms', a.durationMs], ['输入用量', a.inputTokens], ['输出用量', a.outputTokens], ['错误', a.errorCode]]), true)) || [notice('此响应未附模型审计，请查询执行详情。')]),
    el('h3', {}, '工具尝试 · 保留原始失败及纠正关联'), ...(e.toolAttempts?.map(t => details(`${t.name} · ${t.outcome} · ${t.resolutionStatus}`, el('div', {}, facts([['call ID', t.toolCallId], ['尝试 ID / 次数', `${t.attemptId} / ${t.attempt}`], ['副作用', t.sideEffectState], ['耗时 ms', t.durationMs], ['错误', t.errorCode], ['纠正原调用', t.correctionOfCallId], ['解决该错误的调用', t.resolvedByCallId]]), pre(t.safeResult)), true)) || [notice('此响应未附工具审计，请查询执行详情。')]), e.toolAttempts?.length === 0 ? notice('没有已记录的工具尝试。') : null);
}
export function executionDetail(ctx, id) { ctx.readDetail('执行详情', () => get(`/executions/${segment(id)}`), e => executionBody(ctx, e)); }
function scheduleHistory(ctx, id) {
  const host = ctx.detailHost(`触发历史 · ${id}`);
  pager(host, {path: `/schedules/${segment(id)}/executions`, render: e => el('article', {class: 'record-row'}, el('div', {}, el('strong', {}, e.status), el('p', {}, `${e.triggerKind} · ${date(e.createdAt, ctx.timezone())}`), el('p', {}, e.reason || '—')), e.executionId ? button('查看执行', () => executionDetail(ctx, e.executionId)) : notice('未创建执行记录'))});
}
function sessionHistory(ctx, id) {
  const host = ctx.detailHost('会话历史'), meta = el('div'), messages = el('div'), status = el('div', {role: 'status'});
  const all = new Map(); let before = null, loading = false;
  const older = button('加载更早消息', load, {hidden: true, class: 'secondary'});host.append(meta, older, messages, status);
  async function load() {
    if (loading) return; loading = true; older.disabled = true; status.replaceChildren(notice('正在加载历史…'));
    try {
      const d = await get(`/sessions/${segment(id)}`, {limit: 20, beforeSeq: before}); if (!host.isConnected) return;
      const s = d.session, matches = s.identity.channel === 'http' && s.identity.userId === userId() && s.status === 'ACTIVE' && s.kind === 'CONVERSATION';
      meta.replaceChildren(facts([['会话 ID', s.id], ['身份', `${s.identity.channel} / ${s.identity.userId} / ${s.identity.agentName}`], ['创建时服务 / 模型', `${s.providerName} / ${s.model}`], ['类型 / 状态', `${s.kind} / ${s.status}`]]), notice(matches ? '当前标识与此活跃 HTTP 会话匹配，可通过 HTTP 续聊。本页仅查看历史。' : '当前标识不具备此会话的续聊身份或生命周期条件。本页为受信任环境的只读查看，不代表登录授权。'));
      for (const m of d.messages) all.set(m.seq, m);
      messages.replaceChildren(...[...all.values()].sort((a,b) => a.seq - b.seq).map(m => el('article', {class: 'message'}, el('div', {class: 'message-head'}, el('strong', {}, `${m.role} / ${m.seq}`), el('span', {}, date(m.createdAt, ctx.timezone()))), pre(m.content), m.toolCalls?.length ? details('工具提议', pre(m.toolCalls)) : null, m.toolCallId ? notice(`${m.toolCallId} · ${m.toolOutcome}`) : null, button('查看关联执行', () => executionDetail(ctx, m.executionId), {class: 'text-button'}))));
      before = d.nextBeforeSeq;older.hidden = !before;status.replaceChildren(notice(all.size ? `已显示 ${all.size} 条消息${before ? '' : '，无更早消息'}。` : '此会话尚无消息。'));
    } catch (e) { if (host.isConnected) status.replaceChildren(failure(e), button('重新查询历史', load)); }
    finally {loading = false;older.disabled = false;}
  }
  load();
}
