import {get, write, segment, userId, ApiError} from './api.js';
import {el, button, notice, failure, facts, pre, date} from './ui.js';
import {executionBody, executionDetail} from './read-views.js';

export function installControls(ctx) {
  const dialog = document.querySelector('#run-dialog'), form = document.querySelector('#run-form'), input = document.querySelector('#run-content');
  let selected = null;
  ctx.openRun = agent => {
    if (!ctx.canRun() || agent.availability !== 'AVAILABLE') return;
    selected = agent.name;document.querySelector('#run-title').textContent = `运行 Agent · ${selected}`;
    document.querySelector('#run-identity').textContent = `操作者：${userId()} · HTTP · 新建一次性会话`;
    input.value = '';input.setCustomValidity('');dialog.showModal();input.focus();
  };
  document.querySelector('#close-run').addEventListener('click', () => dialog.close());
  input.addEventListener('input', () => input.setCustomValidity(''));
  form.addEventListener('submit', event => {
    event.preventDefault();const content = input.value;
    if (!content.trim() || new TextEncoder().encode(content).length > 32768) {input.setCustomValidity('请输入非空内容，且不超过 32768 UTF-8 字节。');input.reportValidity();return;}
    if (!selected || !ctx.canRun()) return;
    const name = selected;dialog.close();perform(ctx, `运行 ${name}`, () => write(`/agents/${segment(name)}/invoke`, 'POST', {content}), result => showResult(ctx, result));
  });
  ctx.toggleSchedule = s => perform(ctx, `${s.enabled ? '停用' : '启用'} ${s.id}`, () => write(`/schedules/${segment(s.id)}/enabled`, 'PUT', {enabled: !s.enabled}), () => ctx.refreshView());
  ctx.runSchedule = s => perform(ctx, `立即执行 ${s.id}`, () => write(`/schedules/${segment(s.id)}/run`, 'POST'), result => showResult(ctx, result));
}
function showResult(ctx, result) {
  const host = ctx.detailHost('本次执行结果');host.append(executionBody(ctx, result), button('查询完整执行与审计', () => executionDetail(ctx, result.id), {class: 'primary'}));
}
async function perform(ctx, label, operation, done, {recoveryId = null} = {}) {
  if (ctx.pending) return;
  if (!userId()) {ctx.actionNotice.replaceChildren(notice('请先保存用户标识。', 'warning'));ctx.actionNotice.hidden = false;return;}
  ctx.pending = true;ctx.updateControls();ctx.actionNotice.hidden = false;ctx.actionNotice.replaceChildren(notice(`${label}：正在核对运行状态…`));
  let dispatched = false;
  try {
    const state = await ctx.refreshRuntime();
    if (!state) throw new ApiError('无法确认运行状态，尚未发送写请求。', 'RUNTIME_UNKNOWN');
    if (recoveryId) {
      if (state.incidentId !== recoveryId || state.currentIncident?.id !== recoveryId || !state.recoveryRequired) throw new ApiError('故障状态已变化，请重新查看并确认当前资料。', 'INCIDENT_CHANGED');
      if (state.inFlightCount > 0) throw new ApiError('仍有在途调用，尚未发送恢复请求。', 'RECOVERY_NOT_READY');
    } else if (state.state !== 'READY') throw new ApiError('工作区未就绪，尚未发送执行或调度控制请求。', 'EXECUTION_PAUSED');
    ctx.actionNotice.replaceChildren(notice(`${label}：已提交一次，等待后端结果。关闭页面不等于撤销。`, 'warning'));
    try { sessionStorage.setItem('oryxos.admin.unconfirmedWrite', label); } catch { /* best effort */ }
    dispatched = true;const result = await operation();
    try { sessionStorage.removeItem('oryxos.admin.unconfirmedWrite'); } catch { /* best effort */ }
    ctx.actionNotice.replaceChildren(notice(`${label}：已收到响应，请以下方状态和记录为准。`));done(result);
  } catch (error) {
    if (error.status) { try { sessionStorage.removeItem('oryxos.admin.unconfirmedWrite'); } catch { /* best effort */ } }
    ctx.actionNotice.replaceChildren(failure(error));
    if (dispatched) ctx.actionNotice.append(notice('没有自动重试。请先核查执行与动作记录，再决定后续操作。', 'warning'), button('查看执行记录', () => {location.hash = 'executions';}));
    if (error.data?.id && error.data?.status) showResult(ctx, error.data);
    else if (error.data?.executionId) ctx.actionNotice.append(button(`查询执行 ${error.data.executionId}`, () => executionDetail(ctx, error.data.executionId)));
  } finally {ctx.pending = false;await ctx.refreshRuntime();ctx.updateControls();}
}
export function renderRuntime(ctx) {
  const host = ctx.content, state = ctx.runtime;
  if (!state) {host.append(notice('运行状态未知。请先刷新查询；新执行保持禁用。', 'warning'));return;}
  host.append(facts([['状态', state.state], ['存储可写', state.storageWritable], ['在途调用', state.inFlightCount], ['暂停原因', state.safeReason ?? '无'], ['当前故障 ID', state.incidentId ?? '无']]));
  if (!state.recoveryRequired && !state.incidentId) {host.append(notice('当前没有待确认的恢复事件。', 'success'));return;}
  const incident = state.currentIncident;
  if (!incident || incident.id !== state.incidentId) {host.append(notice('故障资料不完整，无法确认当前故障身份。请刷新或检查服务；不可提交恢复。', 'danger'));return;}
  host.append(el('h2', {}, '人工核查资料'), notice(incident.detailsComplete ? '资料已提供；仍需核对外部动作与未保存记录。' : '故障资料不完整：空列表不代表没有发生动作。不要据此重复发送任务。', 'warning'), facts([['检测时间', date(incident.detectedAt, ctx.timezone())], ['证据来源', incident.evidenceSources.join(' / ')], ['故障说明', incident.safeReason], ['缺失记录', incident.missingRecordSummary || '无额外说明']]));
  host.append(el('h3', {}, '受影响执行'), ...(incident.affectedExecutionIds.length ? incident.affectedExecutionIds.map(id => button(id, () => executionDetail(ctx, id), {class: 'text-button mono'})) : [notice('没有可确认的执行标识；不表示未执行。')]));
  for (const [title, actions] of [['已知结果的动作', incident.knownActions], ['结果未知的动作（UNKNOWN）', incident.unknownActions]]) host.append(el('h3', {}, title), ...(actions.length ? actions.map(a => el('article', {class: 'memory-card'}, facts([['类型 / 名称', `${a.kind} / ${a.name ?? '未知'}`], ['结果 / 副作用', `${a.outcome} / ${a.sideEffectState}`], ['执行 ID', a.executionId], ['尝试 ID', a.attemptId], ['call ID', a.toolCallId]]), pre(a.safeSummary))) : [notice('暂无可确认的动作资料；请结合缺失记录说明核查。')]));
  const form = el('form', {class: 'recovery-form'}), checkbox = el('input', {id: 'acknowledge', type: 'checkbox', required: true}), notes = el('textarea', {id: 'recovery-notes', rows: 3, maxlength: 2000, required: true});
  const submit = el('button', {type: 'submit', id: 'recover-submit', class: 'primary', disabled: true}, '确认恢复（不重放）');
  form.append(el('h3', {}, '完成核查后恢复'), notice('请先在系统外修复存储等故障，并核对可能已送达的通知。恢复只开放后续新任务，不补跑、不重放。'), el('label', {class: 'checkbox-label'}, checkbox, '已核对缺失记录，不重放已发生动作'), el('label', {for: 'recovery-notes'}, '核对说明（勿填写秘密）'), notes, el('p', {id: 'recovery-block', class: 'muted'}), submit);
  ctx.recoveryForm = {incidentId: incident.id, checkbox, notes, submit};
  form.addEventListener('input', () => ctx.updateControls());
  form.addEventListener('submit', event => {
    event.preventDefault();if (!checkbox.checked || !notes.value.trim() || submit.disabled) return;
    const payload = {incidentId: incident.id, acknowledgeMissingRecords: true, notes: notes.value.trim()};
    // A failure consumes this confirmation too. A new attempt always requires explicit rechecking.
    checkbox.checked = false;ctx.updateControls();
    perform(ctx, '人工恢复', () => write('/runtime/recover', 'POST', payload), result => {
      ctx.setRuntime(result);ctx.actionNotice.replaceChildren(notice(result.state === 'READY' ? '人工确认已落库，工作区恢复 READY。没有重放旧动作。' : '恢复响应未确认 READY，请继续核查。', result.state === 'READY' ? 'success' : 'warning'));ctx.refreshView();
    }, {recoveryId: incident.id});
  });
  host.append(form);ctx.updateControls();
}
