export function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs)) {
    if (key.startsWith('on')) node.addEventListener(key.slice(2), value);
    else if (key === 'class') node.className = value;
    else if (value !== false && value !== null && value !== undefined) node.setAttribute(key, value === true ? '' : value);
  }
  for (const child of children.flat(Infinity)) if (child !== undefined && child !== null) node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  return node;
}
export const button = (label, action, attrs = {}) => el('button', {type: 'button', onclick: action, ...attrs}, label);
export const plain = value => value == null ? '未知' : typeof value === 'object' ? JSON.stringify(value, null, 2) : String(value);
export const pre = value => el('pre', {}, plain(value));
export function date(value, timezone) {
  if (!value) return '—';
  try { return new Intl.DateTimeFormat('zh-CN', {timeZone: timezone || 'Asia/Shanghai', dateStyle: 'medium', timeStyle: 'medium', hour12: false}).format(new Date(value)); }
  catch { return String(value); }
}
export function facts(entries) {
  return el('dl', {class: 'facts'}, entries.map(([key, value]) => el('div', {}, el('dt', {}, key), el('dd', {}, plain(value)))));
}
export function notice(text, tone = 'muted') { return el('p', {class: `notice ${tone}`}, text); }
export function failure(error) {
  return el('div', {class: 'notice danger', role: 'alert'}, el('strong', {}, `${error.code || 'QUERY_FAILED'} · 查询或操作失败`), el('p', {}, error.message), error.traceId ? el('small', {}, `traceId: ${error.traceId}`) : null);
}
export function executionState(e) {
  const tools = e.toolAttempts || [];
  if (e.recordingStatus === 'INCOMPLETE' || e.status === 'PARTIAL_RECORDING_FAILURE') return {tone: 'danger', label: '记录异常 · 需要人工核查'};
  if (e.recordingStatus === 'UNKNOWN' || e.stopReason === 'SIDE_EFFECT_UNCERTAIN' || tools.some(t => t.outcome === 'UNKNOWN' || t.sideEffectState === 'UNKNOWN')) return {tone: 'warning', label: '结果未确认（UNKNOWN）· 请先核查'};
  if (e.status === 'TIMED_OUT') return {tone: 'warning', label: '执行超时 · 核查迟到结果，勿自动重发'};
  if (e.status === 'RUNNING') return {tone: 'muted', label: '执行中 · 尚未收尾'};
  if (e.status === 'SUCCEEDED' && e.recordingStatus === 'COMPLETE' && e.hasUnresolvedToolErrors === false) return e.hasToolErrors ? {tone: 'warning', label: '执行成功 · 曾有已纠正错误'} : {tone: 'success', label: '执行成功 · 记录完整'};
  return {tone: 'danger', label: `${e.status || 'UNKNOWN'} · 未全部完成，请核查`};
}
export function executionSummary(e) { const state = executionState(e); return el('span', {class: `status ${state.tone}`}, state.label); }
export function details(title, content, open = false) { return el('details', {open}, el('summary', {}, title), content); }
