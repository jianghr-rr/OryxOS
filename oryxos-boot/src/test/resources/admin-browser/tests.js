// Real DOM + same-origin HTTP + production SQLite. Only remote services/faults are local fixtures.
const checks = [], frame = document.querySelector('#console');
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const doc = () => frame.contentDocument;
const text = () => doc()?.body.textContent || '';
async function wait(test, label, timeout = 15000) {const start = Date.now();while(!test()){if(Date.now()-start>timeout)throw Error(`等待失败：${label}\n${text().slice(-3000)}`);await sleep(30);}return test();}
function assert(value, message){if(!value)throw Error(message);}
function find(label, root = doc()){return [...root.querySelectorAll('button')].find(b => b.textContent.trim() === label && !b.hidden && (label!=='运行 Agent' || b.getAttribute('aria-label')==='运行 Agent local'));}
function input(selector, value){const field=doc().querySelector(selector);field.value=value;field.dispatchEvent(new Event('input',{bubbles:true}));}
async function api(path){const r=await fetch('/api/v1'+path);return (await r.json()).data;}
async function evidence(){return (await fetch('/__fixture__/evidence')).json();}
const writes = e => e.requests.filter(r => r.method !== 'GET');
async function nav(name){frame.contentWindow.location.hash=name;await wait(()=>doc().querySelector(`nav a[data-view="${name}"]`)?.getAttribute('aria-current')==='page',name);}
async function run(message){
  await nav('agents');await wait(()=>find('运行 Agent')&&!find('运行 Agent').disabled,'Agent可运行');find('运行 Agent').click();
  input('#run-content',message);doc().querySelector('#run-form').requestSubmit();
  await wait(()=>!doc().querySelector('#detail').hidden && !text().includes('正在核对运行状态') && !doc().querySelector('#user-id').disabled,'执行响应');
  return doc().querySelector('#detail').textContent;
}
async function check(name, action){await action();checks.push({name,result:'PASS'});document.querySelector('#result').textContent=JSON.stringify({status:'RUNNING',checks},null,2);}
try {
 localStorage.removeItem('oryxos.admin.userId');
 await new Promise(resolve=>{frame.addEventListener('load',resolve,{once:true});frame.src='/admin/';});
 await wait(()=>doc()?.querySelectorAll('.agent-card').length===3&&text().includes('READY'),'首次真实加载');
 await check('没有用户标识时禁运行；本地夹具标记可见',async()=>{assert([...doc().querySelectorAll('[data-execute]')].every(b=>b.disabled),'未设置身份不可运行');assert(text().includes('本地验证服务'),'本地夹具必须标记');});
 await check('用户标识可保存且发往后端',async()=>{input('#user-id','browser-review');doc().querySelector('#identity-form').requestSubmit();await wait(()=>!find('运行 Agent').disabled,'保存身份');});
 await check('共享CORE/ARCHIVAL记忆来自实际落库且HTML作为文本',async()=>{await nav('memory');await wait(()=>doc().querySelectorAll('.memory-card').length===2,'共享记忆');const memory=await api('/memory');assert(memory.core.length===1&&memory.archival.length===1,'实际两类记忆');assert(text().includes('<img src=x onerror=alert(1)>'),'纯文本内容');assert(doc().querySelectorAll('#content img').length===0,'禁止生成模型HTML元素');});
 await check('会话分页、筛选、历史更早页无丢失',async()=>{
  await nav('sessions');await wait(()=>doc().querySelectorAll('.record-row').length===20,'会话第一页');find('加载更多').click();await wait(()=>doc().querySelectorAll('.record-row').length>20,'下一页');
  const ids=[...doc().querySelectorAll('.record-row .mono')].map(e=>e.textContent);assert(new Set(ids).size===ids.length,'无重复行');assert(ids.length===(await api('/sessions?limit=100')).items.length,'全部会话与接口一致');
  input('.filters [name=userId]','browser-review');doc().querySelector('.filters').requestSubmit();await wait(()=>doc().querySelectorAll('.record-row').length===3,'按用户筛选');
  const sessions=await api('/sessions?userId=browser-review&limit=100');let s;for(const candidate of sessions.items){if((await api('/sessions/'+candidate.id+'?limit=100')).messages.length===24)s=candidate;}assert(s,'长历史会话存在');[...doc().querySelectorAll('.mono')].find(b=>b.textContent===s.id).click();
  await wait(()=>doc().querySelectorAll('.message').length===20,'历史第一页');find('加载更早消息').click();await wait(()=>doc().querySelectorAll('.message').length===24,'更早历史');
  assert(doc().querySelector('#detail').textContent.includes('当前标识与此活跃 HTTP 会话匹配'),'身份匹配提示');assert(doc().querySelectorAll('#detail img').length===0,'历史纯文本');
 });
 await check('空数据与错误分开，跳至主内容不切换视图',async()=>{await nav('sessions');input('.filters [name=userId]','no-such-user');doc().querySelector('.filters').requestSubmit();await wait(()=>text().includes('暂无记录'),'空数据');assert(!doc().querySelector('#content [role=alert]'),'空数据不是查询错误');doc().querySelector('.skip-link').click();assert(doc().activeElement.id==='main'&&frame.contentWindow.location.hash==='#sessions','跳至主内容保留当前视图');});
 await check('手动执行只发一次POST，实际JSON/落库与页面一致',async()=>{
  const before=await evidence();const output=await run('普通浏览器验证');assert(output.includes('执行成功 · 记录完整'),'普通成功');assert(output.includes('<img src=x onerror=alert(1)>'),'回复纯文本');
  const after=await evidence();assert(writes(after).length-writes(before).length===1,'一次POST');assert(after.models-before.models===1,'一次模型');assert(writes(after).at(-1).user==='browser-review','实际操作者标识');
  find('查询完整执行与审计').click();await wait(()=>doc().querySelector('#detail').textContent.includes('actual-fixture-model'),'完整审计');
  const last=(await api('/executions?limit=100')).items.find(e=>e.content?.includes('普通浏览器验证'));assert(last&&doc().querySelector('#detail').textContent.includes(last.id),'实际执行ID一致');
 });
 await check('刷新及重新打开只GET，不重复写入',async()=>{const before=await evidence();doc().querySelector('#refresh').click();await sleep(250);await new Promise(resolve=>{frame.addEventListener('load',resolve,{once:true});frame.contentWindow.location.reload();});await wait(()=>doc().querySelectorAll('.agent-card').length===3&&text().includes('READY'),'重新打开');assert((await evidence()).models===before.models,'刷新不调用模型');assert(writes(await evidence()).length===writes(before).length,'刷新不自动POST');assert(doc().querySelector('#user-id').value==='browser-review','身份保留');});
 await check('已纠正成功仍保留原始失败和纠正关联',async()=>{const before=await evidence();const output=await run('纠正参数验证');assert(output.includes('曾有已纠正错误'),'纠正警告');find('查询完整执行与审计').click();await wait(()=>doc().querySelector('#detail').textContent.includes('fixed-params'),'纠正审计');assert(doc().querySelector('#detail').textContent.includes('bad-params'),'原失败仍可见');assert(doc().querySelector('#detail').textContent.includes('RESOLVED'),'已解决状态');assert((await evidence()).effects-before.effects===1,'纠正仅一次副作用');});
 await check('UNKNOWN不显示全部成功且不重发',async()=>{const before=await evidence();const output=await run('未知结果验证');assert(output.includes('结果未确认（UNKNOWN）'),'UNKNOWN提示');assert(!output.includes('执行成功 · 记录完整'),'不伪造成功');assert((await evidence()).effects-before.effects===1,'未知副作用只发送一次');doc().querySelector('#refresh').click();await sleep(200);assert((await evidence()).effects-before.effects===1,'刷新不重发');});
 await check('定时启停以实际结果为准，停用任务可手动运行并查历史',async()=>{
  await nav('schedules');await wait(()=>find('启用'),'调度列表');find('启用').click();await wait(()=>find('停用')&&!find('停用').disabled,'启用已落库');assert((await api('/schedules')).items[0].enabled,'后端启用');find('停用').click();await wait(()=>find('启用')&&!find('启用').disabled,'停用已落库');assert(!(await api('/schedules')).items[0].enabled,'后端停用');
  const before=await evidence();find('立即执行').click();await wait(()=>!doc().querySelector('#detail').hidden&&!doc().querySelector('#user-id').disabled,'调度执行');assert(writes(await evidence()).length-writes(before).length===1,'调度一次POST');find('触发历史').click();await wait(()=>doc().querySelector('#detail').textContent.includes('DISPATCHED'),'触发历史');
 });
 await check('加载/失败/重新查询与离开视图竞态',async()=>{
  await fetch('/__fixture__/query?fail=true',{method:'POST'});await nav('agents');await wait(()=>text().includes('LOCAL_TEST_QUERY_FAILURE'),'真实查询错误');assert(!text().includes('没有已加载的 Agent'),'错误不显示空');
  await fetch('/__fixture__/query?delay=650',{method:'POST'});find('重新查询此页').click();await wait(()=>text().includes('正在加载'),'加载状态');await nav('schedules');await sleep(900);assert(doc().querySelector('#page-title').textContent==='定时任务'&&!doc().querySelector('.agent-card'),'迟到查询不覆盖新视图');
  await fetch('/__fixture__/query',{method:'POST'});
 });
 await check('真实记录故障暂停、资料不完整、明确确认恢复且不重放',async()=>{
  const before=await evidence();const output=await run('记录故障验证');assert(output.includes('记录异常'),'记录异常显示');await wait(()=>text().includes('PAUSED_RECORDING'),'暂停状态');assert([...doc().querySelectorAll('[data-execute]')].every(b=>b.disabled),'禁止新执行');
  await nav('runtime');await wait(()=>doc().querySelector('#acknowledge'),'恢复资料');assert(text().includes('故障资料不完整'),'不完整资料警告');assert(text().includes('unknown')||text().includes('UNKNOWN'),'未知动作资料');
  assert(doc().querySelector('#recover-submit').disabled,'未确认不得恢复');input('#recovery-notes','本地验证：已核对接收一次，保留缺口，不重放。');assert(doc().querySelector('#recover-submit').disabled,'仅说明不得恢复');
  await fetch('/__fixture__/repair',{method:'POST'});doc().querySelector('#acknowledge').click();assert(!doc().querySelector('#recover-submit').disabled,'明确确认可提交到后端复查存储');doc().querySelector('.recovery-form').requestSubmit();
  await wait(()=>text().includes('工作区恢复 READY'),'恢复成功');assert((await api('/runtime/state')).state==='READY','后端READY');assert((await evidence()).effects-before.effects===1,'恢复不重放已发生副作用');
 });
 await check('调度写入与恢复落库失败均不显示成功，确认消耗后必须重新核对',async()=>{
  const before=await evidence();
  await fetch('/__fixture__/fail-write?target=enabled',{method:'POST'});
  await nav('schedules');await wait(()=>find('启用')&&!find('启用').disabled,'失败前启用按钮');
  find('启用').click();await wait(()=>text().includes('没有自动重试')&&!doc().querySelector('#user-id').disabled,'启用写失败');
  assert(!find('停用'),'没有乐观显示启用成功');assert(!(await api('/schedules')).items[0].enabled,'DB仍停用');
  assert((await api('/runtime/state')).state==='PAUSED_RECORDING','写失败暂停');
  assert(writes(await evidence()).length-writes(before).length===1,'写失败无重试');
  await fetch('/__fixture__/repair',{method:'POST'});
  await fetch('/__fixture__/fail-write?target=recovery',{method:'POST'});
  await nav('runtime');await wait(()=>doc().querySelector('#acknowledge'),'写失败恢复资料');
  input('#recovery-notes','本地验证：仅恢复闸门，保留故障记录，不补跑。');doc().querySelector('#acknowledge').click();
  doc().querySelector('.recovery-form').requestSubmit();
  await wait(()=>text().includes('RECOVERY_UNAVAILABLE')&&!doc().querySelector('#user-id').disabled,'确认落库失败');
  assert(!text().includes('工作区恢复 READY'),'恢复失败不显示成功');assert((await api('/runtime/state')).state==='PAUSED_RECORDING','后端仍暂停');
  assert(!doc().querySelector('#acknowledge').checked&&doc().querySelector('#recover-submit').disabled,'失败消耗确认');
  assert(writes(await evidence()).length-writes(before).length===2,'恢复失败无重试');
  await fetch('/__fixture__/repair',{method:'POST'});
  doc().querySelector('#acknowledge').click();doc().querySelector('.recovery-form').requestSubmit();
  await wait(()=>text().includes('工作区恢复 READY'),'重新明确确认后恢复');
  assert((await api('/runtime/state')).state==='READY','恢复实际落库');
  assert((await evidence()).models===before.models&&(await evidence()).effects===before.effects,'恢复与失败控制均无外部动作');
 });
 const final=await evidence();document.querySelector('#result').textContent=JSON.stringify({status:'PASS',localOnly:true,checks,evidence:final},null,2);
} catch(error){document.querySelector('#result').textContent=JSON.stringify({status:'FAIL',localOnly:true,checks,error:error.stack},null,2);}
