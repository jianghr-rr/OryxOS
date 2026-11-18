/** No retries, redirects to other origins, or write-on-load behavior. */
export class ApiError extends Error {
  constructor(message, code = 'NETWORK_UNKNOWN', status = 0, data = null, traceId = null) {
    super(message); Object.assign(this, {code, status, data, traceId});
  }
}
const key = 'oryxos.admin.userId';
let identity = '';
try { identity = localStorage.getItem(key) || ''; } catch { /* Storage may be disabled. */ }
export function userId() { return identity; }
export function saveUser(value) {
  const candidate = value.trim();
  if (!/^[\x20-\x7e]{1,128}$/.test(candidate)) throw new ApiError('请输入 1–128 位可见 ASCII 用户标识。', 'INVALID_INPUT');
  identity = candidate;
  try { localStorage.setItem(key, identity); } catch { return false; }
  return true;
}
export function query(path, params = {}) {
  const values = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) if (value !== null && value !== undefined && value !== '') values.set(key, String(value));
  return path + (values.size ? `?${values}` : '');
}
export async function request(path, {method = 'GET', body} = {}) {
  if (!path.startsWith('/') || path.startsWith('//')) throw new ApiError('请求路径无效。', 'INVALID_INPUT');
  const headers = {'Accept': 'application/json'};
  if (identity && /^[\x20-\x7e]{1,128}$/.test(identity)) headers['X-Oryx-User-Id'] = identity;
  if (method !== 'GET' && !headers['X-Oryx-User-Id']) throw new ApiError('请先保存用户标识；它用于记录操作者，不代表登录。', 'INVALID_INPUT');
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  let response;
  try { response = await fetch(`/api/v1${path}`, {method, headers, body: body === undefined ? undefined : JSON.stringify(body), credentials: 'same-origin', mode: 'same-origin', redirect: 'error', cache: 'no-store'}); }
  catch { throw new ApiError(method === 'GET' ? '无法连接后端，请检查服务后刷新查询。' : '请求结果未确认（UNKNOWN）。请核查执行记录，不要重复提交。'); }
  let envelope;
  try { envelope = await response.json(); }
  catch { throw new ApiError('后端响应无法确认，请核查记录。', 'RESPONSE_UNKNOWN', response.status); }
  if (!response.ok) throw new ApiError(envelope.message || '请求失败', envelope.code || 'REQUEST_FAILED', response.status, envelope.data, envelope.traceId);
  if (!envelope || !Object.hasOwn(envelope, 'data')) throw new ApiError('后端未返回有效数据。', 'RESPONSE_UNKNOWN', response.status);
  return envelope.data;
}
export const get = (path, params) => request(query(path, params));
export const write = (path, method, body) => request(path, {method, body});
export const segment = value => encodeURIComponent(value);
