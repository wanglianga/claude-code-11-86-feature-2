/* 公交灰度实验评审控制台 —— 纯原生 JS */
const EXP_CODE = 'PILOT-R123-202606';
const $ = (s, el = document) => el.querySelector(s);
const $$ = (s, el = document) => [...el.querySelectorAll(s)];

let EXP = null;
let ME = null;
const loaded = {};

const PATH_META = {
  FULL_TREATMENT: { label: '全程处理', cls: 'blue' },
  FULL_CONTROL: { label: '纯净对照', cls: 'gray' },
  CROSS_MIXED: { label: '交叉/混合暴露', cls: 'purple' },
  UNKNOWN: { label: '未知(保留)', cls: 'red' }
};
const ARM_CLS = { TREATMENT: 'blue', CONTROL: 'gray' };

function fmt(x, d = 1) {
  if (x === null || x === undefined || x === '') return '—';
  const n = Number(x);
  return Number.isFinite(n) ? n.toLocaleString('zh-CN', { maximumFractionDigits: d, minimumFractionDigits: d }) : String(x);
}
function asObj(v) {
  if (v && typeof v === 'string') { try { return JSON.parse(v); } catch { return v; } }
  return v;
}
function pct(x, d = 1) { return x === null || x === undefined ? '—' : (Number(x) * 100).toFixed(d) + '%'; }
function dt(x) {
  if (!x) return '—';
  try { return new Date(x).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }); }
  catch { return String(x); }
}
function badge(text, cls = 'gray') { return `<span class="badge ${cls}">${text}</span>`; }
function armBadge(a) { return a ? badge(a === 'TREATMENT' ? '处理' : '对照', ARM_CLS[a] || 'gray') : '—'; }

async function api(path, opts = {}) {
  const res = await fetch('/api' + path, {
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    ...opts
  });
  if (res.status === 401) { location.href = '/login.html'; throw new Error('未登录'); }
  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : {}; } catch { data = text; }
  if (!res.ok) {
    const msg = (data && data.message) ? data.message : ('HTTP ' + res.status);
    const err = new Error(msg); err.status = res.status; throw err;
  }
  return data;
}

function toast(msg, isErr = false) {
  const t = $('#toast');
  t.textContent = msg;
  t.className = 'toast show' + (isErr ? ' err' : '');
  clearTimeout(toast._h);
  toast._h = setTimeout(() => t.className = 'toast', 4200);
}

async function guardAction(fn, okMsg) {
  try { const r = await fn(); toast(okMsg || '操作完成'); return r; }
  catch (e) {
    if (e.status === 403) toast('403：当前账号无权执行该操作，请使用对应角色账号', true);
    else toast('操作失败：' + e.message, true);
    throw e;
  }
}

/* ---------------- 初始化与导航 ---------------- */
async function init() {
  ME = await api('/me');
  if (!ME.authenticated) { location.href = '/login.html'; return; }
  $('#username').textContent = ME.username;
  $('#avatar').textContent = (ME.username[0] || '?').toUpperCase();
  $('#roles').textContent = (ME.roles || []).join(' / ');

  const list = await api('/experiments');
  const row = list.find(e => e.code === EXP_CODE) || list[0];
  EXP = await api('/experiments/' + row.id);
  ['treatment_rules', 'primary_metrics', 'safety_metrics', 'analysis_config', 'network_config']
    .forEach(k => { EXP[k] = asObj(EXP[k]); });

  $$('#nav button').forEach(b => b.addEventListener('click', () => switchTab(b.dataset.tab)));
  await switchTab('overview');
}

const TAB_TITLES = {
  overview: ['试点总览', '目标人群、核心事实与扩围状态'],
  registry: ['实验登记与冻结', '合格线路/班次、规则、种子、概率、指标与周期一次性冻结并留指纹'],
  network: ['网络图与设计选择', '连接关系仅来自实验前刷卡换乘比例，簇在登记时冻结'],
  assignment: ['随机分配', '确定性 SHA-256 种子，平衡分配，固定 50% 饱和度，可离线复算'],
  exposure: ['暴露路径台账', '逐段关联计划分配、GPS 实际运行、折返/加班偏离与完整路径'],
  support: ['共同支持', '按时段 × 起讫区间 × 天气 × 可达性检查两臂可比范围'],
  analysis: ['客流结果估计', '乘客旅程加权 ITT、跨线暴露、CACE 与随机化推断区间'],
  migration: ['客流迁移 · 旅程重建', '刷卡 + 车载定位 + 换乘记录重建实际旅程；确定性/概率双引擎估计同一 OD 的迁移与换乘损失区间'],
  guardrails: ['投诉与满载护栏', '独立于平均候车改善；任一硬护栏超限即停止扩围'],
  review: ['离线审阅', '冻结证据包、确定性复算与完整 JSON 导出'],
  expansion: ['扩围证据', '主要指标 + 护栏证据形成 GO / HOLD / BLOCKED 结论']
};

async function switchTab(tab) {
  $$('#nav button').forEach(b => b.classList.toggle('active', b.dataset.tab === tab));
  $$('.tab-panel').forEach(p => p.classList.remove('active'));
  $('#tab-' + tab).classList.add('active');
  $('#top-title').textContent = TAB_TITLES[tab][0];
  $('#top-sub').textContent = TAB_TITLES[tab][1];
  if (!loaded[tab]) {
    loaded[tab] = true;
    try { await RENDER[tab](); } catch (e) { $('#tab-' + tab).innerHTML = `<div class="banner red">加载失败：${e.message}</div>`; }
  }
}

function renderGlobalBanner() {
  const d = EXP.latestDecision;
  if (!d || d.decision === 'NOT_EVALUATED') { $('#global-banner').innerHTML = ''; return; }
  if (d.decision === 'BLOCKED') {
    $('#global-banner').innerHTML = `<div class="banner red"><span class="big">⛔</span>
      <div><b>扩围已被护栏自动阻止（BLOCKED）。</b> ${d.reason || ''}
      <div class="small">最新结论时间 ${dt(d.created_at)} · 签署 ${d.decided_by || 'system'}</div></div></div>`;
  } else if (d.decision === 'GO') {
    $('#global-banner').innerHTML = `<div class="banner green"><span class="big">✅</span><div><b>扩围评审通过（GO）。</b> ${d.reason || ''}</div></div>`;
  } else {
    $('#global-banner').innerHTML = `<div class="banner amber"><span class="big">⏸️</span><div><b>暂缓扩围（HOLD）。</b> ${d.reason || ''}</div></div>`;
  }
}

/* ---------------- 总览 ---------------- */
RENDER = {};
RENDER.overview = async function () {
  renderGlobalBanner();
  const [ov, guard] = await Promise.all([
    api(`/experiments/${EXP.id}/overview`),
    api(`/experiments/${EXP.id}/guardrails`)
  ]);
  const blocked = (guard.latest || []).filter(g => g.breached);
  $('#tab-overview').innerHTML = `
    <div class="grid cards-4">
      ${stat('登记旅程总数', ov.journeys, '含未知状态，全部保留')}
      ${stat('完整旅程', ov.completeJourneys, '进入效应估计')}
      ${stat('未知旅程', ov.unknownJourneys, '未完成刷卡/中途改线', 'red')}
      ${stat('命名核验样例', ov.demoJourneys, '可逐路径追溯', 'accent')}
      ${stat('计划班次实例', ov.tripInstances, 'GPS/AVL 关联实际运行')}
      ${stat('班次随机单元', ov.assignmentUnitsTrip, '线路×日期×时段×班次')}
      ${stat('簇—周单元', ov.assignmentUnitsCluster, '2 簇 × 4 周')}
      ${stat('投诉 / 外部事件', ov.complaints + ' / ' + ov.events, '按服务时间关联')}
    </div>

    <div class="grid cards-2" style="margin-top:14px">
      <div class="card">
        <h3>🧪 虚构样例脚本</h3>
        <p class="section-desc">R1 与 R2 共享中心枢纽，实验前基线 <b>40%</b> 的 R1/R2 旅程发生两线换乘；R3 与它们基本不换乘。</p>
        <ul>
          <li><b>J-DEMO-DIVERT-01</b>：R1 处理班次实际<b>短线折返</b>，乘客到枢纽换乘 R2 <b>对照</b>班次 → 完整路径必须是 <span class="badge purple">交叉/混合</span>。</li>
          <li><b>J-DEMO-PURER2-01</b>：同一 R2 对照班次上的普通 R2 乘客 → 必须留在 <span class="badge gray">纯净对照</span>。</li>
          <li><b>J-DEMO-CROSS-01</b>：对照起乘、枢纽换乘处理线路 → 交叉暴露。</li>
          <li><b>J-DEMO-NONEXEC-01</b>：随机分配为处理但司机未执行 → ITT 仍计处理臂，实际暴露为旧规则。</li>
          <li>暴雨红色预警停运（2026-06-15 早高峰）产生未知/滞留旅程，投诉提交时间滞后服务 1–6 天。</li>
        </ul>
        <div class="pill-row">
          <button class="btn primary sm" onclick="switchTab('exposure')">查看暴露路径核验</button>
          <button class="btn sm" onclick="switchTab('assignment')">核验 50% 分配</button>
          <button class="btn sm" onclick="switchTab('analysis')">查看两方案估计差异</button>
        </div>
      </div>
      <div class="card">
        <h3>🚧 护栏最新状态</h3>
        ${blocked.length ? `<div class="banner red" style="margin:6px 0"><span class="big">⛔</span><div>${blocked.map(b => `${b.ruleName} 观测 <b>${b.observed}</b> 越过阈值 <b>${b.threshold}</b>`).join('<br/>')}</div></div>`
          : '<div class="banner green" style="margin:6px 0">暂无超限护栏</div>'}
        <table class="data"><thead><tr><th>护栏</th><th class="num">观测</th><th class="num">阈值</th><th>状态</th></tr></thead><tbody>
        ${(guard.latest || []).map(g => `<tr>
          <td>${g.ruleName}</td><td class="num mono">${fmt(g.observed, g.metricSource === 'overload_share' || g.metric_source === 'overload_share' ? 4 : 2)}</td>
          <td class="num mono">${g.threshold}</td>
          <td>${g.breached ? badge('超限·停止扩围', 'red') : g.action === 'INSUFFICIENT_EVIDENCE' ? badge('证据不足', 'amber') : badge('正常', 'green')}</td>
        </tr>`).join('')}
        </tbody></table>
        <div style="margin-top:10px"><button class="btn danger sm" onclick="switchTab('expansion')">前往扩围证据页</button></div>
      </div>
    </div>`;
};

function stat(k, v, hint, cls = '') {
  return `<div class="card stat ${cls}"><div class="k">${k}</div><div class="v mono">${v}</div><div class="hint">${hint || ''}</div></div>`;
}

/* ---------------- 登记 ---------------- */
RENDER.registry = async function () {
  const e = EXP;
  const pretty = v => `<pre class="json">${JSON.stringify(v, null, 2)}</pre>`;
  $('#tab-registry').innerHTML = `
    <div class="card">
      <div class="toolbar" style="margin-top:0">
        <h2 style="margin:0">${e.name}</h2>
        ${badge(e.status, e.status === 'REGISTERED' ? 'green' : 'amber')}
        ${badge(e.active_design === 'TRIP_RANDOMIZED' ? '主设计：班次随机' : '主设计：簇周随机', 'blue')}
      </div>
      <dl class="kv">
        <dt>实验编号</dt><dd><code>${e.code}</code></dd>
        <dt>随机种子</dt><dd class="mono">${e.seed}</dd>
        <dt>分配概率/饱和度</dt><dd>${pct(e.assignment_prob, 0)}（平衡分配，实际严格 50%） · 试点 ${e.pilot_weeks} 周</dd>
        <dt>试点期间</dt><dd>${e.start_date} → ${e.end_date}</dd>
        <dt>目标旅程人群</dt><dd>${e.target_population}</dd>
        <dt>登记指纹</dt><dd class="hash">${e.registration_hash || '（尚未登记）'}</dd>
        <dt>登记时间</dt><dd>${dt(e.registered_at)}</dd>
      </dl>
      <h3>处理规则（冻结）</h3>${pretty(e.treatment_rules)}
      <div class="grid cards-2">
        <div><h3>主要指标</h3>${pretty(e.primary_metrics)}</div>
        <div><h3>安全指标</h3>${pretty(e.safety_metrics)}</div>
      </div>
    </div>

    <div class="card" style="margin-top:14px">
      <h3>登记一个新实验（接口演示）</h3>
      <p class="section-desc">提交编号、种子与实验前换乘边，服务会冻结线路簇并生成两套 50% 分配。分析师及以上角色可操作。</p>
      <textarea id="new-exp-body" rows="16"></textarea>
      <div class="toolbar"><button class="btn primary" id="create-exp">创建 DRAFT 并冻结登记</button><span id="create-out" class="small"></span></div>
    </div>`;

  $('#new-exp-body').value = JSON.stringify({
    code: 'PILOT-DEMO-' + Math.floor(Math.random() * 9000 + 1000),
    name: '通过界面登记的灰度实验',
    seed: 'SEED-DEMO-001',
    assignmentProb: 0.5,
    pilotWeeks: 4,
    startDate: '2026-06-01',
    edgeThreshold: 0.15,
    minSupportPerArm: 25,
    routes: [{ code: 'R1', name: '一路' }, { code: 'R2', name: '二路' }],
    preEdges: [{ fromRoute: 'R1', toRoute: 'R2', transferJourneys: 400, totalJourneys: 1000 }]
  }, null, 2);

  $('#create-exp').addEventListener('click', async () => {
    const out = $('#create-out');
    out.textContent = '提交中…';
    try {
      const body = JSON.parse($('#new-exp-body').value);
      const created = await guardAction(() => api('/experiments', { method: 'POST', body: JSON.stringify(body) }));
      const reg = await guardAction(() => api(`/experiments/${created.id}/register`, { method: 'POST' }));
      out.innerHTML = `<span class="expected-ok">已登记 id=${created.id}，指纹 ${reg.registrationHash.slice(0, 16)}…（可在 Swagger 中查看）</span>`;
    } catch (e) { out.innerHTML = `<span class="expected-bad">${e.message}</span>`; }
  });
};

/* ---------------- 网络 ---------------- */
RENDER.network = async function () {
  const n = await api(`/experiments/${EXP.id}/network`);
  const o = n.designOptions;
  $('#tab-network').innerHTML = `
    <div class="card">
      <h3>实验前刷卡换乘边（冻结，不使用试点结果重算）</h3>
      <div class="table-wrap"><table class="data">
        <thead><tr><th>起点线路</th><th>终点线路</th><th class="num">换乘旅程</th><th class="num">基线总旅程</th><th class="num">换乘比例</th><th>强度</th></tr></thead>
        <tbody>${n.preTransferEdges.map(e => `<tr>
          <td><b>${e.from_route}</b></td><td><b>${e.to_route}</b></td>
          <td class="num">${e.transfer_journeys}</td><td class="num">${e.total_journeys}</td>
          <td class="num mono">${pct(e.ratio, 1)}</td>
          <td><div class="bar" style="width:160px"><div class="t" style="width:${Math.min(100, e.ratio * 220)}%;background:${e.ratio >= 0.15 ? 'var(--purple)' : '#9fb2cc'}"></div></div></td>
        </tr>`).join('')}</tbody></table></div>
    </div>

    <div class="grid cards-3" style="margin-top:14px">
      ${n.clusters.map(c => `<div class="card" style="text-align:center">
        <div class="muted small">冻结簇</div><div style="font-size:22px;font-weight:800;margin:6px 0">${c.cluster_code}</div>
        <div class="pill-row" style="justify-content:center">${c.route_codes.split(',').map(r => badge(r, 'teal')).join('')}</div>
      </div>`).join('')}
    </div>

    <div class="banner blue" style="margin-top:14px"><span class="big">🧭</span><div>${o.recommendation}</div></div>

    <div class="design-compare">
      <div class="design-card recommend">
        <h3>方案 A：线路—班次随机 ${badge('主估计', 'blue')}</h3>
        <dl class="kv">
          <dt>有效随机单元</dt><dd><b class="mono">${o.tripRandomization.effectiveUnits}</b>（${o.tripRandomization.strata} 个 ${o.tripRandomization.unit}层）</dd>
          <dt>饱和度</dt><dd>${o.tripRandomization.saturation}</dd>
          <dt>优点</dt><dd>${o.tripRandomization.advantage}</dd>
          <dt>代价</dt><dd>${o.tripRandomization.risk}</dd>
        </dl>
      </div>
      <div class="design-card">
        <h3>方案 B：线路簇—周随机 ${badge('降污染', 'purple')}</h3>
        <dl class="kv">
          <dt>有效随机单元</dt><dd><b class="mono">${o.clusterWeekRandomization.effectiveUnits}</b>（${o.clusterWeekRandomization.clusters} 簇 × 4 周）</dd>
          <dt>饱和度</dt><dd>${o.clusterWeekRandomization.saturation}</dd>
          <dt>优点</dt><dd>${o.clusterWeekRandomization.advantage}</dd>
          <dt>代价</dt><dd>${o.clusterWeekRandomization.risk}</dd>
        </dl>
      </div>
    </div>`;
};

/* ---------------- 随机分配 ---------------- */
let assignDesign = 'TRIP_RANDOMIZED';
RENDER.assignment = async function () {
  $('#tab-assignment').innerHTML = `
    <div class="toolbar" style="margin-top:0">
      <div class="pill-row">
        <button class="filter-chip active" data-d="TRIP_RANDOMIZED">线路—具体班次（${'~720'}）</button>
        <button class="filter-chip" data-d="CLUSTER_WEEK">簇—周（8）</button>
      </div>
      <span class="small muted">同一 SHA-256 种子，任何环境重算结果一致</span>
    </div>
    <div id="assign-body"></div>`;
  $$('[data-d]').forEach(b => b.addEventListener('click', async () => {
    assignDesign = b.dataset.d;
    $$('[data-d]').forEach(x => x.classList.toggle('active', x === b));
    await drawAssignments();
  }));
  await drawAssignments();
};

async function drawAssignments() {
  const d = await api(`/experiments/${EXP.id}/assignments?design=${assignDesign}`);
  const share = pct(d.treatmentShare, 1);
  const rows = d.units.slice(0, 200);
  $('#assign-body').innerHTML = `
    <div class="card">
      <div class="grid cards-3">
        <div class="stat"><div class="k">单元总数</div><div class="v mono">${d.total}</div></div>
        <div class="stat blue"><div class="k">处理 / 对照</div><div class="v mono">${d.treatment} / ${d.control}</div></div>
        <div class="stat green"><div class="k">处理占比（固定 50%）</div><div class="v mono">${share}</div></div>
      </div>
      <div class="bar" style="height:16px;margin-top:12px"><div class="t" style="width:${d.treatmentShare * 100}%"></div></div>
      <div class="bar-label"><span>处理 ${d.treatment}</span><span>对照 ${d.control}</span></div>
      <p class="small muted" style="margin-top:8px">每个平衡层内按哈希排序取前半，偶数层严格一半；边缘包含概率 = ${d.units[0] ? fmt(d.units[0].inclusion_prob, 2) : '0.50'}。</p>
    </div>
    <div class="table-wrap" style="margin-top:14px;max-height:560px">
      <table class="data"><thead><tr><th>单元</th><th>层</th><th>标签</th><th>周</th><th>臂</th><th class="num">π</th><th>哈希前缀</th></tr></thead>
      <tbody>${rows.map(u => `<tr>
        <td><code>${u.unit_key}</code></td><td class="small">${u.stratum}</td><td>${u.label || ''}</td>
        <td>${u.week_index ?? ''}</td><td>${armBadge(u.arm)}</td>
        <td class="num mono">${fmt(u.inclusion_prob, 2)}</td>
        <td class="hash">${(u.hash_value || '').slice(0, 12)}…</td></tr>`).join('')}
      </tbody></table>
      ${d.units.length > 200 ? `<div class="small muted" style="padding:8px 12px">仅渲染前 200 行，完整 ${d.units.length} 行可通过 API 获取。</div>` : ''}
    </div>`;
}

/* ---------------- 暴露路径 ---------------- */
let exposureFilter = 'ALL';
RENDER.exposure = async function () {
  $('#tab-exposure').innerHTML = `
    <div class="card">
      <h3>命名样例自动核验</h3>
      <div id="demo-checks" class="small">加载中…</div>
    </div>
    <div class="toolbar">
      <div class="pill-row" id="path-chips"></div>
      <input type="text" id="exposure-q" placeholder="搜索旅程号 / 备注，如 DIVERT" />
      <button class="btn primary" id="exposure-search">搜索</button>
    </div>
    <div id="exposure-counts" class="grid cards-4" style="margin-bottom:12px"></div>
    <div class="table-wrap" style="max-height:600px">
      <table class="data"><thead><tr>
        <th>旅程</th><th>状态</th><th>完整路径</th><th>簇口径</th><th>分配(班/簇)</th>
        <th>标记</th><th class="num">候车(s)</th><th class="num">拥挤暴露</th><th class="num">人数</th></tr></thead>
      <tbody id="exposure-rows"></tbody></table>
    </div>`;

  $('#exposure-search').addEventListener('click', loadExposureRows);
  $('#exposure-q').addEventListener('keydown', e => { if (e.key === 'Enter') loadExposureRows(); });
  await loadDemoChecks();
  await loadExposureRows();
};

const DEMO_EXPECT = {
  'J-DEMO-DIVERT-01': { path: 'CROSS_MIXED', cross: true, diverted: true, arm: 'TREATMENT', text: 'R1处理→短线折返→R2对照分流' },
  'J-DEMO-PURER2-01': { path: 'FULL_CONTROL', text: '同车普通R2乘客仍为纯净对照' },
  'J-DEMO-CROSS-01': { path: 'CROSS_MIXED', cross: true, arm: 'CONTROL', text: '对照起乘→换乘处理线路' },
  'J-DEMO-FULLT-01': { path: 'FULL_TREATMENT', text: '两段均处理' },
  'J-DEMO-FULLC-01': { path: 'FULL_CONTROL', text: '两段均对照' },
  'J-DEMO-NONEXEC-01': { path: 'FULL_CONTROL', arm: 'TREATMENT', text: '分配T但未执行：实际旧规则/分配仍T' },
  'J-DEMO-INCOMPLETE-01': { path: 'UNKNOWN', text: '未完成刷卡保留未知' },
  'J-DEMO-MIDCHANGE-01': { path: 'UNKNOWN', text: '中途改线保留未知' },
  'J-DEMO-STORM-01': { path: 'UNKNOWN', text: '暴雨停运中途改线未知' }
};

async function loadDemoChecks() {
  const d = await api(`/experiments/${EXP.id}/exposure?q=J-DEMO&limit=50`);
  const byCode = Object.fromEntries(d.rows.map(r => [r.journey_code, r]));
  let allOk = true;
  const html = Object.entries(DEMO_EXPECT).map(([code, x]) => {
    const r = byCode[code];
    if (!r) { allOk = false; return `<tr><td><code>${code}</code></td><td class="expected-bad">缺失</td></tr>`; }
    const pathOk = r.path_label === x.path;
    const crossOk = x.cross === undefined || r.cross_line === x.cross;
    const divOk = x.diverted === undefined || r.diverted === x.diverted;
    const armOk = x.arm === undefined || r.assigned_arm_trip === x.arm;
    const ok = pathOk && crossOk && divOk && armOk;
    if (!ok) allOk = false;
    return `<tr class="clickable" onclick="openJourney('${code}')">
      <td><code>${code}</code></td>
      <td>${PATH_META[r.path_label] ? badge(PATH_META[r.path_label].label, PATH_META[r.path_label].cls) : r.path_label}</td>
      <td>${armBadge(r.assigned_arm_trip)}</td>
      <td>${r.cross_line ? badge('跨线', 'purple') : ''} ${r.diverted ? badge('折返分流', 'amber') : ''}</td>
      <td class="small muted">${x.text}</td>
      <td>${ok ? '<span class="expected-ok">✓ 符合预期</span>' : `<span class="expected-bad">✗ ${!pathOk ? '路径应为' + x.path : ''} ${!armOk ? '臂不符' : ''}</span>`}</td>
    </tr>`;
  }).join('');
  $('#demo-checks').innerHTML = `
    <div class="banner ${allOk ? 'green' : 'red'}" style="margin:4px 0 10px">
      ${allOk ? '✅ 全部 ' + Object.keys(DEMO_EXPECT).length + ' 个命名样例的完整路径/臂/交叉标记均符合预期' : '⚠️ 存在不符合预期的样例'}</div>
    <div class="table-wrap"><table class="data"><tbody>${html}</tbody></table></div>`;
}

async function loadExposureRows() {
  const q = $('#exposure-q') ? $('#exposure-q').value.trim() : '';
  const d = await api(`/experiments/${EXP.id}/exposure?pathLabel=${exposureFilter}&q=${encodeURIComponent(q)}&limit=200`);
  const total = d.counts.reduce((s, c) => s + Number(c.n), 0);
  $('#path-chips').innerHTML = ['ALL', ...Object.keys(PATH_META)].map(k =>
    `<button class="filter-chip ${exposureFilter === k ? 'active' : ''}" data-p="${k}">${k === 'ALL' ? '全部' : PATH_META[k].label}</button>`).join('');
  $$('#path-chips [data-p]').forEach(b => b.addEventListener('click', () => { exposureFilter = b.dataset.p; loadExposureRows(); }));

  $('#exposure-counts').innerHTML = d.counts.map(c => {
    const meta = PATH_META[c.path_label] || { label: c.path_label, cls: 'gray' };
    return `<div class="card stat"><div class="k">${badge(meta.label, meta.cls)}</div>
      <div class="v mono">${c.n}</div><div class="hint">加权 ${fmt(c.weighted_n, 0)} 人次 · ${pct(c.n / total, 1)}</div></div>`;
  }).join('');

  $('#exposure-rows').innerHTML = d.rows.map(r => `<tr class="clickable" onclick="openJourney('${r.journey_code}')">
    <td><code>${r.journey_code}</code>${r.note ? `<div class="small muted">${r.note.slice(0, 42)}…</div>` : ''}</td>
    <td>${badge(r.status === 'COMPLETE' ? '完成' : r.status, r.status === 'COMPLETE' ? 'green' : 'red')}</td>
    <td>${PATH_META[r.path_label] ? badge(PATH_META[r.path_label].label, PATH_META[r.path_label].cls) : r.path_label}</td>
    <td><span class="small">${({ CLUSTER_TREATMENT: '簇处理', CLUSTER_CONTROL: '簇对照', CLUSTER_CROSS: '簇交叉', UNKNOWN: '未知' })[r.cluster_path_label] || r.cluster_path_label}</span></td>
    <td>${armBadge(r.assigned_arm_trip)} / ${armBadge(r.assigned_arm_cluster)}</td>
    <td>${r.cross_line ? badge('cross', 'purple') : ''} ${r.diverted ? badge('折返', 'amber') : ''} ${r.effective_treatment ? badge('实际新规则', 'blue') : ''}</td>
    <td class="num mono">${fmt(r.wait_seconds, 0)}</td>
    <td class="num mono">${fmt(r.crowd_exposure, 1)}</td>
    <td class="num mono">${r.passenger_count}</td>
  </tr>`).join('') || '<tr><td colspan="9" class="muted" style="padding:20px;text-align:center">无匹配记录</td></tr>';
}

window.openJourney = async function (code) {
  const d = await api(`/experiments/${EXP.id}/journeys/${encodeURIComponent(code)}`);
  const j = d.journey;
  const led = d.ledger || {};
  let trace = [];
  try { trace = JSON.parse(led.path_detail_text || '[]'); } catch {}
  const expCls = { TREATMENT: 'TREATMENT', CONTROL: 'CONTROL', CROSS: 'CROSS', UNKNOWN: 'UNKNOWN' };
  $('#modal').innerHTML = `
    <button class="btn sm close" onclick="closeModal()">关闭</button>
    <h2><code>${j.journey_code}</code></h2>
    <div class="pill-row" style="margin:6px 0 12px">
      ${badge(j.status, j.status === 'COMPLETE' ? 'green' : 'red')}
      ${PATH_META[led.path_label] ? badge('完整路径：' + PATH_META[led.path_label].label, PATH_META[led.path_label].cls) : ''}
      ${armBadge(j.anchor_arm_trip)}
      ${j.note ? `<span class="small muted">${j.note}</span>` : ''}
    </div>
    <dl class="kv">
      <dt>服务时间</dt><dd>${dt(j.service_ts)} · ${j.period_band} · ${j.od_band} · 天气 ${j.weather_class}${j.accessibility ? '' : ' · <b class="expected-bad">可达性受损</b>'}</dd>
      <dt>端到端候车</dt><dd>${fmt(j.wait_seconds, 0)} 秒（首乘 ${fmt(j.first_wait_seconds, 0)} + 换乘 ${fmt(j.transfer_wait_seconds, 0)}）</dd>
      <dt>拥挤暴露</dt><dd>${fmt(j.crowd_exposure, 1)}（严重拥挤分钟 ${fmt(j.severe_crowd_minutes, 1)}，最大 LF ${fmt(j.max_load_factor, 2)}）</dd>
      <dt>路径概率</dt><dd class="mono">${led.path_probability ? fmt(led.path_probability, 4) : '—（未知旅程不计算）'}</dd>
    </dl>
    <h3>逐段关联（计划分配 × GPS 实际执行）</h3>
    <div class="leg-flow">
      ${d.legs.map((l, i) => `
        <div class="leg-box ${expCls[l.leg_exposure] || ''}">
          <div class="small muted">第 ${l.leg_index} 段 ${l.is_transfer ? '· 换乘' : ''}</div>
          <b>${l.route_code}</b> <span class="small">${l.board_stop || ''} → ${l.alight_stop || ''}</span>
          <div class="small" style="margin-top:4px">
            计划臂 ${armBadge(l.planned_arm)}<br/>
            GPS ${l.gps_run_code ? '<code>' + l.gps_run_code + '</code>' : '无实际运行'}<br/>
            规则实际执行：${l.run_rule_executed === true || l.run_rule_executed === 't' ? '<span class="expected-ok">是</span>' : '<span class="expected-bad">否</span>'}<br/>
            偏离：${l.run_deviation || l.deviation || 'NONE'}${l.delay_seconds ? ' · 延误 ' + l.delay_seconds + 's' : ''}<br/>
            段暴露：<b>${l.leg_exposure}</b> · LF ${fmt(l.load_factor, 2)}
          </div>
        </div>
        ${i < d.legs.length - 1 ? '<div class="leg-arrow">→</div>' : ''}`).join('')}
    </div>
    <h3>判定追踪</h3>
    <ul class="trace">${trace.map(t => `<li>${t}</li>`).join('')}</ul>`;
  $('#modal-back').classList.add('open');
};
window.closeModal = () => $('#modal-back').classList.remove('open');
$('#modal-back')?.addEventListener('click', e => { if (e.target.id === 'modal-back') closeModal(); });

/* ---------------- 共同支持 ---------------- */
RENDER.support = async function () {
  const [d, events] = await Promise.all([
    api(`/experiments/${EXP.id}/support`),
    api(`/experiments/${EXP.id}/events`)
  ]);
  const g = d.gaps;
  const eventTypeMeta = {
    WEATHER_RAINSTORM: ['暴雨/停运', 'red'], WEATHER_RAIN: ['降雨', 'blue'],
    CONSTRUCTION: ['道路施工', 'amber'], ROUTE_CHANGE: ['临时改线', 'purple']
  };
  $('#tab-support').innerHTML = `
    <div class="grid cards-4">
      <div class="stat"><div class="k">分层总数</div><div class="v mono">${d.strata.length}</div></div>
      <div class="stat amber"><div class="k">班次方案缺口层</div><div class="v mono">${g.gap_strata_trip}</div><div class="hint">涉及 ${g.gap_journeys_trip} 旅程</div></div>
      <div class="stat amber"><div class="k">簇周方案缺口层</div><div class="v mono">${g.gap_strata_cluster}</div><div class="hint">涉及 ${g.gap_journeys_cluster} 旅程</div></div>
      <div class="stat red"><div class="k">口径</div><div class="v" style="font-size:15px;padding-top:8px">缺口只报告覆盖，不外推</div></div>
    </div>
    <div class="banner blue" style="margin-top:12px">${d.policy}。支持维度：时段 × 起讫区间 × 天气 × 服务可达性；每臂加权样本阈值取自实验配置（默认 25）。</div>
    <div class="table-wrap" style="margin-top:12px;max-height:620px">
      <table class="data"><thead><tr>
        <th>时段</th><th>OD 区间</th><th>天气</th><th>可达</th><th class="num">旅程</th><th class="num">加权</th>
        <th class="num">班 T/C 权重</th><th>班次支持</th><th class="num">簇 T/C 权重</th><th>簇支持</th><th>缺口原因</th>
      </tr></thead><tbody>
      ${d.strata.map(s => `<tr>
        <td>${s.period_band}</td><td>${s.od_band}</td><td>${s.weather_class}</td>
        <td>${s.accessibility ? '是' : badge('受损', 'red')}</td>
        <td class="num">${s.n_total}</td><td class="num">${fmt(s.weighted_total, 0)}</td>
        <td class="num small">${fmt(s.w_t_trip, 0)} / ${fmt(s.w_c_trip, 0)}</td>
        <td>${s.supported_trip ? badge('支持', 'green') : badge('缺口', 'amber')}</td>
        <td class="num small">${fmt(s.w_t_cluster, 0)} / ${fmt(s.w_c_cluster, 0)}</td>
        <td>${s.supported_cluster ? badge('支持', 'green') : badge('缺口', 'amber')}</td>
        <td class="small muted">${s.gap_reason || ''}</td>
      </tr>`).join('')}
      </tbody></table>
    </div>

    <div class="card" style="margin-top:14px">
      <h3>外部事件时间线（按事件发生时间关联服务，而非投诉时间）</h3>
      <div class="table-wrap"><table class="data">
        <thead><tr><th>类型</th><th>级别</th><th>事件</th><th>起止</th><th>影响线路</th><th>停运</th><th>来源</th></tr></thead>
        <tbody>${events.map(ev => {
          const meta = eventTypeMeta[ev.event_type] || [ev.event_type, 'gray'];
          return `<tr>
            <td>${badge(meta[0], meta[1])}</td><td>${ev.severity || ''}</td><td>${ev.title}</td>
            <td class="small mono">${dt(ev.start_ts)} ~ ${dt(ev.end_ts)}</td>
            <td>${ev.affected_routes}</td>
            <td>${ev.suspension ? badge('停运', 'red') : '—'}</td><td class="small muted">${ev.source || ''}</td>
          </tr>`;
        }).join('')}</tbody>
      </table></div>
      <p class="small muted" style="margin-bottom:0">生成旅程与计算支持层时，天气等级/可达性按服务时刻与上述事件窗口重叠判定；投诉表同时保留 complaint_time 与 service_event_time，护栏统计只使用后者。</p>
    </div>`;
};

/* ---------------- 分析 ---------------- */
RENDER.analysis = async function () {
  const d = await api(`/experiments/${EXP.id}/analysis`);
  const rs = d.results;
  const itt = rs.filter(r => r.estimator_code === 'ITT' && r.scope === 'COMMON_SUPPORT');
  const ri = rs.find(r => r.estimator_code === 'RI_PERMUTATION');
  const cross = rs.find(r => r.estimator_code === 'CROSS_RATE');
  const cace = rs.find(r => r.estimator_code === 'CACE_WALD');

  $('#tab-analysis').innerHTML = `
    <div class="card">
      <h3>主要结果：乘客旅程加权 ITT（共同支持域，95% 区间）</h3>
      <p class="section-desc">负值表示候车/拥挤下降。按乘客人数加权，标准误按随机化单元聚类 CR1（班次方案单元多 → 区间窄；簇周仅 8 个簇—周 → 区间宽）。</p>
      ${ciBlock(itt.filter(r => r.metric_code === 'wait_seconds'), '端到端候车时间差（秒，处理 − 对照）')}
      <div style="height:14px"></div>
      ${ciBlock(itt.filter(r => r.metric_code === 'crowd_exposure'), '拥挤暴露差（ΣLF×分钟）')}
    </div>

    <div class="grid cards-3" style="margin-top:14px">
      <div class="card"><h3>跨线暴露率</h3>
        <div class="v mono" style="font-size:30px;font-weight:800;color:var(--purple)">${cross ? pct(cross.estimate, 1) : '—'}</div>
        <div class="small muted">Wilson 95% CI ${cross ? pct(cross.ci_low, 1) + ' ~ ' + pct(cross.ci_high, 1) : ''} · ${cross ? cross.n_journeys : ''} 完整旅程</div>
        <div class="small" style="margin-top:6px">共享枢纽异臂搭乘/短线折返分流的完整路径占比，单独成类，不混入纯净对照。</div>
      </div>
      <div class="card"><h3>CACE / Wald IV</h3>
        <div class="v mono" style="font-size:30px;font-weight:800">${cace ? fmt(cace.estimate, 1) : '—'}<span style="font-size:14px"> s</span></div>
        <div class="small muted">CI ${cace ? fmt(cace.ci_low, 1) + ' ~ ' + fmt(cace.ci_high, 1) : ''} · 以随机分配为“全程实际接受”的工具变量</div>
        <div class="small" style="margin-top:6px">处理司机不执行规则时的依从者效应；<b>不是</b>把实际执行者子集直接称为随机效应。</div>
      </div>
      <div class="card"><h3>簇方案随机化推断</h3>
        <div class="v mono" style="font-size:30px;font-weight:800">p=${ri ? fmt(ri.p_value, 3) : '—'}</div>
        <div class="small muted">枚举 2⁴=16 种每周方向 · plus-one 分母 17</div>
        <div class="small" style="margin-top:6px">仅 2 个线路簇时，RI 是比渐近 CI 更可信的不确定性证据；点估计更大（内部化 R1/R2 溢出）但证据不精确。</div>
      </div>
    </div>

    <div class="banner amber" style="margin-top:14px"><span class="big">⚠️</span><div>
      <b>解释纪律：</b>“按实际暴露路径/执行者分组”的均值在下表标记为 AS_TREATED_ASSOCIATION，仅为观察性关联；
      因果结论只来自随机分配臂的 ITT 与带假设的 CACE。无共同支持的旅程不进入这些估计，只在“共同支持”页报告缺口。</div></div>

    <div class="card" style="margin-top:14px">
      <h3>全部估计结果（含 PATH_MEAN / AS_TREATED / ALL_ELIGIBLE 对照口径）</h3>
      <div class="table-wrap" style="max-height:560px"><table class="data">
        <thead><tr><th>设计</th><th>估计量</th><th>指标</th><th>范围</th><th class="num">估计</th><th class="num">SE</th><th class="num">CI 低</th><th class="num">CI 高</th><th class="num">p</th><th class="num">N</th><th class="num">单元</th></tr></thead>
        <tbody>${rs.map(r => `<tr>
          <td>${r.design_type === 'TRIP_RANDOMIZED' ? '班次' : '簇周'}</td>
          <td><code>${r.estimator_code}</code></td><td class="small">${r.metric_code}</td><td class="small">${r.scope}</td>
          <td class="num mono"><b>${fmt(r.estimate, 2)}</b></td><td class="num mono">${fmt(r.se, 2)}</td>
          <td class="num mono">${fmt(r.ci_low, 2)}</td><td class="num mono">${fmt(r.ci_high, 2)}</td>
          <td class="num mono">${r.p_value === null || r.p_value === undefined ? '—' : fmt(r.p_value, 3)}</td>
          <td class="num">${r.n_journeys}</td><td class="num">${r.n_units}</td>
        </tr>`).join('')}</tbody></table></div>
    </div>`;
};

function ciBlock(rows, title) {
  if (!rows.length) return '';
  const maxAbs = Math.max(...rows.map(r => Math.max(Math.abs(r.ci_low || 0), Math.abs(r.ci_high || 0), 1)));
  return `<h3 style="margin-bottom:4px">${title}</h3>` + rows.map(r => {
    const left = ((r.ci_low + maxAbs) / (2 * maxAbs)) * 100;
    const width = ((r.ci_high - r.ci_low) / (2 * maxAbs)) * 100;
    const dot = ((r.estimate + maxAbs) / (2 * maxAbs)) * 100;
    const cross = r.ci_low < 0 && r.ci_high > 0;
    const name = r.design_type === 'TRIP_RANDOMIZED' ? '班次随机（多单元，窄区间）' : '簇—周随机（8 单元，宽区间）';
    return `<div class="ci-row">
      <div><b>${name}</b><div class="small muted">${r.n_journeys} 旅程 / ${r.n_units} 单元</div></div>
      <div class="ci-track">
        <div class="ci-line ${cross ? 'zero-cross' : ''}" style="left:${left}%;width:${width}%"></div>
        <div class="ci-dot ${cross ? 'zero-cross' : ''}" style="left:${dot}%"></div>
      </div>
      <div class="small mono">${fmt(r.estimate, 1)} [${fmt(r.ci_low, 1)}, ${fmt(r.ci_high, 1)}]</div>
    </div>`;
  }).join('');
}

/* ---------------- 护栏 ---------------- */
RENDER.guardrails = async function () {
  await drawGuardrails(false);
};

async function drawGuardrails(afterAction) {
  const g = await api(`/experiments/${EXP.id}/guardrails`);
  $('#tab-guardrails').innerHTML = `
    <div class="toolbar" style="margin-top:0">
      <button class="btn primary" id="eval-g">重新评估护栏（分析师）</button>
      <span class="small muted">投诉按 service_event_time 归集；满载来自 GPS/AVL 与 load_count</span>
    </div>
    <div class="grid cards-3">
      ${g.latest.map(x => guardCard(x)).join('')}
    </div>
    <div class="card" style="margin-top:14px">
      <h3>评估明细（含时间关联核验与 Top 满载班次）</h3>
      ${g.latest.map(x => `<h3>${x.ruleName || x.rule_name} <code>${x.rule_code}</code></h3>
        <pre class="json">${JSON.stringify(asObj(x.detail), null, 2)}</pre>`).join('')}
    </div>`;
  $('#eval-g').addEventListener('click', async () => {
    $('#eval-g').disabled = true; $('#eval-g').innerHTML = '<span class="spinner"></span> 评估中';
    try {
      await guardAction(() => api(`/experiments/${EXP.id}/guardrails/evaluate`, { method: 'POST' }), '护栏已用最新数据评估');
      await drawGuardrails(true);
    } finally { const b = $('#eval-g'); if (b) { b.disabled = false; b.textContent = '重新评估护栏（分析师）'; } }
  });
}

function guardCard(x) {
  const isPctMetric = x.metric_source === 'overload_share';
  const obs = isPctMetric ? pct(x.observed, 2) : fmt(x.observed, 2);
  const thr = isPctMetric ? pct(x.threshold, 2) : x.threshold;
  const cls = x.breached ? 'red' : x.action === 'INSUFFICIENT_EVIDENCE' ? 'amber' : 'green';
  return `<div class="card stat ${x.breached ? 'red' : ''}">
    <div class="k">${x.ruleName || x.rule_name}</div>
    <div class="v mono">${obs}</div>
    <div class="hint">阈值 ${thr} ${x.comparator === 'GT' ? '（超过即超限）' : ''} · 样本 ${x.sampleN ?? x.sample_n}/${x.minSample ?? x.min_sample}</div>
    <div style="margin-top:8px">${x.breached ? badge('⛔ 超限 · STOP_EXPANSION', 'red') : x.action === 'INSUFFICIENT_EVIDENCE' ? badge('证据不足 · HOLD', 'amber') : badge('✓ 正常 · OK', 'green')}</div>
  </div>`;
}

/* ---------------- 离线审阅 ---------------- */
RENDER.review = async function () {
  $('#tab-review').innerHTML = `
    <div class="card">
      <h3>可复现性</h3>
      <p class="section-desc">所有分配来自 <code>SHA-256(seed::unitKey)</code> 的确定性排序；点击右侧按钮会用库中冻结数据清空并重算暴露账、支持层、分析与护栏，数字应保持一致。</p>
      <div class="pill-row">
        <button class="btn primary" id="recompute">▶ 重新运行确定性流水线</button>
        <button class="btn" id="download-bundle">⬇ 下载离线审阅包 JSON</button>
        <button class="btn" id="show-bundle">👁 在页面中展开审阅包</button>
      </div>
      <div class="small muted" style="margin-top:10px">登记指纹：<span class="hash">${EXP.registration_hash}</span></div>
    </div>
    <div class="card" style="margin-top:14px"><div id="bundle-meta" class="small muted">审阅包包含：实验冻结配置、实验前边、簇、两套分配清单与哈希、事件、命名旅程追踪、支持层、全部估计、护栏评估与扩围历史。</div>
      <pre class="json" id="bundle-json" style="display:none;margin-top:10px"></pre>
    </div>`;

  $('#recompute').addEventListener('click', async () => {
    const b = $('#recompute'); b.disabled = true; b.innerHTML = '<span class="spinner"></span> 重算中…';
    try {
      await guardAction(() => api(`/experiments/${EXP.id}/pipeline/run`, { method: 'POST' }), '流水线已重算：请刷新各标签查看');
      Object.keys(loaded).forEach(k => { if (k !== 'review') loaded[k] = false; });
      EXP = await api('/experiments/' + EXP.id);
    } finally { b.disabled = false; b.textContent = '▶ 重新运行确定性流水线'; }
  });

  let bundle = null;
  async function getBundle() { if (!bundle) bundle = await api(`/experiments/${EXP.id}/review-bundle`); return bundle; }
  $('#show-bundle').addEventListener('click', async () => {
    const b = await getBundle();
    const pre = $('#bundle-json');
    pre.style.display = 'block';
    pre.textContent = JSON.stringify(b, null, 2);
  });
  $('#download-bundle').addEventListener('click', async () => {
    const b = await getBundle();
    const blob = new Blob([JSON.stringify(b, null, 2)], { type: 'application/json' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = `review-bundle-${EXP.code}.json`;
    a.click();
    URL.revokeObjectURL(a.href);
  });
};

/* ---------------- 扩围证据 ---------------- */
RENDER.expansion = async function () {
  const d = await api(`/experiments/${EXP.id}/expansion`);
  const latest = d.latest || {};
  const cls = latest.decision === 'BLOCKED' ? 'red' : latest.decision === 'GO' ? 'green' : 'amber';
  $('#tab-expansion').innerHTML = `
    <div class="banner ${latest.decision === 'BLOCKED' ? 'red' : latest.decision === 'GO' ? 'green' : 'amber'}">
      <span class="big">${latest.decision === 'BLOCKED' ? '⛔' : latest.decision === 'GO' ? '✅' : '⏸️'}</span>
      <div><b style="font-size:16px">当前扩围结论：${latest.decision || 'NOT_EVALUATED'}</b>
        <div>${latest.reason || '尚未运行证据评估'}</div>
        <div class="small">${latest.created_at ? dt(latest.created_at) + ' · ' + (latest.decided_by || '') : ''}</div>
      </div>
    </div>

    <div class="toolbar">
      <button class="btn danger" id="eval-exp">运行护栏评估并自动形成结论（审批人）</button>
      <span class="small muted">任一硬护栏超限 → BLOCKED，且 GO 人工改判也会被服务端拒绝</span>
    </div>

    <div class="grid cards-2">
      <div class="card">
        <h3>人工记录扩围结论</h3>
        <select id="manual-decision" class="toolbar" style="padding:8px 10px">
          <option value="HOLD">HOLD 暂缓</option>
          <option value="BLOCKED">BLOCKED 停止扩围</option>
          <option value="GO">GO 扩围（护栏超限时服务端拒绝）</option>
        </select>
        <textarea id="manual-reason" rows="3" placeholder="结论理由（留痕）"></textarea>
        <div class="toolbar"><button class="btn primary" id="manual-submit">提交结论</button></div>
      </div>
      <div class="card">
        <h3>结论历史</h3>
        <div class="table-wrap" style="max-height:260px">
          <table class="data"><thead><tr><th>结论</th><th>理由</th><th>签署</th><th>时间</th></tr></thead>
          <tbody>${(d.history || []).map(h => `<tr>
            <td>${badge(h.decision, h.decision === 'BLOCKED' ? 'red' : h.decision === 'GO' ? 'green' : 'amber')}</td>
            <td class="small">${h.reason || ''}</td><td>${h.decided_by}</td><td class="small">${dt(h.created_at)}</td>
          </tr>`).join('')}</tbody></table>
        </div>
      </div>
    </div>`;

  $('#eval-exp').addEventListener('click', async () => {
    try {
      const r = await guardAction(() => api(`/experiments/${EXP.id}/expansion/evaluate`, { method: 'POST' }));
      toast('结论：' + r.decision);
      delete loaded.expansion;
      EXP = await api('/experiments/' + EXP.id);
      RENDER.expansion();
    } catch { /* toast handled */ }
  });
  $('#manual-submit').addEventListener('click', async () => {
    const decision = $('#manual-decision').value;
    const reason = $('#manual-reason').value;
    try {
      await guardAction(() => api(`/experiments/${EXP.id}/expansion/decide`, {
        method: 'POST', body: JSON.stringify({ decision, reason })
      }), '已记录 ' + decision);
      delete loaded.expansion; RENDER.expansion();
    } catch (e) {
      if (e.status === 409) toast('服务端拒绝：' + e.message, true);
    }
  });
};

/* =====================================================================
   客流迁移 · 旅程重建（feature-2）
   刷卡 + AVL 定位 + 换乘记录 -> 上车账/轨迹对齐/候选下车站/OD 双引擎区间/抽样核验/发布门控
   ===================================================================== */
const MIG = { sub: 'matrix', wb: null, onlyMig: true, align: 'ALL', outageOnly: false, jMulti: false };

const MIG_VERIFY_META = {
  CONFLICT: ['抽样冲突 · 禁止单点', 'red'],
  CORROBORATED: ['抽样相容', 'green'],
  INSUFFICIENT: ['证据不足(n<10)', 'amber'],
  NO_SURVEY: ['无抽样', 'gray']
};
const MIG_ALIGN_META = {
  ALIGNED: ['精确对齐', 'green'],
  NEAREST_FALLBACK: ['最近班次兜底', 'blue'],
  GPS_GAP_FUZZY: ['定位中断·模糊对齐', 'purple'],
  UNMATCHED: ['未匹配', 'red']
};
function migHM(m) {
  if (m === null || m === undefined) return '—';
  const h = Math.floor(Number(m) / 60), mm = Number(m) % 60;
  return String(h).padStart(2, '0') + ':' + String(mm).padStart(2, '0');
}
function migIv(point, lo, hi, d = 1) {
  return `<span class="mono"><b>${fmt(point, d)}</b> <span class="muted small">[${fmt(lo, d)} , ${fmt(hi, d)}]</span></span>`;
}
function migBar(point, max, color) {
  const w = Math.max(2, Math.min(100, (Number(point) / Math.max(Number(max), 1e-9)) * 100));
  const bg = color === 'purple' ? 'var(--purple)' : color === 'red' ? 'var(--red,#d64545)' : '#9fb2cc';
  return `<div class="bar" style="min-width:80px;height:8px"><div style="height:100%;width:${w}%;background:${bg};border-radius:999px"></div></div>`;
}
function migModal(html) {
  $('#modal').innerHTML = `<button class="btn sm close" onclick="closeModal()">关闭</button>` + html;
  $('#modal-back').classList.add('open');
}

RENDER.migration = async function () {
  const wb = await api('/migration/workbench');
  MIG.wb = wb;
  const ov = wb.overview || {};
  const g = wb.gate || {};
  const dg = wb.diagnostics || {};
  const conflicts = g.conflictOds || [];
  const gateBanner = conflicts.length
    ? `<div class="banner red"><span class="big">⛔</span><div>
         <b>抽样调查与旅程推断在 ${conflicts.join('、')} 上冲突：不得发布单点迁移量。</b>
         <div class="small">系统只允许发布双引擎+抽样的包络区间（INTERVAL_ONLY）；任何报告级/该 OD 的单点发布都会被服务端以 409 拒绝，且无角色可绕过。定位中断的个案始终保留多个候选下车站。</div>
       </div></div>`
    : `<div class="banner green"><span class="big">✅</span><div><b>抽样与推断相容，可发布单点迁移量。</b></div></div>`;

  $('#tab-migration').innerHTML = `
    <div class="banner blue" style="margin-top:0"><span class="big">🚇</span><div>
      <b>这是客流迁移/旅程重建，不是父任务的实验干扰处理。</b>
      从 AFC 刷卡、AVL 车载定位与换乘记录重建乘客实际旅程，量化<b>短线折返</b>是否制造额外换乘损失。
      两条路线估计<b>同一 OD 站对</b>：<b>确定性时刻表分配</b>（快、可解释，拥堵/无刷下车会错配）与
      <b>概率旅程推断</b>（候选路线+换乘等待后验，给不确定性，但先验需校准：留出准确率 ${pct(dg.detValidationAccuracy, 1)}，n=${dg.detValidationN}）。
    </div></div>
    ${gateBanner}
    <div class="grid cards-4" style="margin-top:12px">
      ${stat('上车观察账', ov.boardingLedger, '首乘刷卡 × AVL 对齐')}
      ${stat('重建旅程', ov.rebuiltJourneys, '双引擎同时给结论')}
      ${stat('OD 流量行', ov.odFlowRows, '23 站对 × 两引擎')}
      ${stat('多候选旅程', ov.multiCandidateJourneys, '后验分散/定位中断保留', 'accent')}
      ${stat('定位中断个案', ov.locationOutageCases, '保留 ≥2 下车站候选', 'purple')}
      ${stat('短线折返个案', ov.shortTurnCases, 'R1 在中心枢纽折返')}
      ${stat('额外换乘旅程', ov.extraTransferJourneys, '折返导致的新增换乘')}
      ${stat('抽样冲突 OD', (ov.conflictOds || []).length, '冲突即禁发单点', (ov.conflictOds || []).length ? 'red' : '')}
    </div>
    <div class="toolbar" style="margin:14px 0 10px">
      <div class="pill-row" id="mig-subnav">
        ${[
      ['matrix', '④ OD 流量矩阵/区间'],
      ['boardings', '① 上车观察账'],
      ['trajectories', '② 车辆轨迹对齐'],
      ['journeys', '③ 候选下车/重建旅程'],
      ['verification', '⑤ 抽样核验'],
      ['publication', '📣 发布门控']
    ].map(([k, label]) => `<button class="filter-chip ${MIG.sub === k ? 'active' : ''}" data-sub="${k}" onclick="migSub('${k}')">${label}</button>`).join('')}
      </div>
      <span style="flex:1"></span>
      <button class="btn primary sm" id="mig-rebuild">▶ 从原始数据重算重建</button>
    </div>
    <div id="mig-body"></div>`;

  $('#mig-rebuild').addEventListener('click', async () => {
    try {
      const r = await guardAction(() => api('/migration/rebuild', { method: 'POST' }), '已从原始 AFC/AVL/换乘数据重算');
      MIG.wb = await api('/migration/workbench');
      await migRenderSub();
      if (r && r.reportGate) toast(`重建完成：${r.boardingCases} 个案 / ${r.odPairs} OD / 冲突 ${r.reportGate.conflictOds.length}`);
    } catch { /* toast handled */ }
  });

  await migRenderSub();
};

window.migSub = async function (s) {
  MIG.sub = s;
  $$('#mig-subnav .filter-chip').forEach(b => b.classList.toggle('active', b.dataset.sub === s));
  await migRenderSub();
};

async function migRenderSub() {
  const el = $('#mig-body');
  if (!el) return;
  el.innerHTML = '<div class="muted" style="padding:24px;text-align:center">加载中…</div>';
  try { await MIG_VIEW[MIG.sub](el); }
  catch (e) { el.innerHTML = `<div class="banner red">加载失败：${e.message}</div>`; }
}

const MIG_VIEW = {};

/* ---------- ④ OD 矩阵 ---------- */
MIG_VIEW.matrix = async function (el) {
  const rows = (MIG.wb && MIG.wb.matrix) || [];
  const vmap = {};
  (MIG.wb.verification || []).forEach(v => { vmap[v.origin_stop + '|' + v.dest_stop] = v; });
  const maxHigh = Math.max(1, ...rows.map(r => Math.max(r.probabilistic ? r.probabilistic.mig_high : 0)));
  const shown = rows.filter(r => !MIG.onlyMig || (r.probabilistic && r.probabilistic.mig_point >= 0.5));
  el.innerHTML = `
    <div class="card">
      <div class="toolbar" style="margin-top:0">
        <h3 style="margin:0">旅程流量矩阵 · 同一 OD 的双引擎估计与换乘损失区间</h3>
        <span style="flex:1"></span>
        <button class="filter-chip ${MIG.onlyMig ? 'active' : ''}" onclick="migToggleOnlyMig()">${MIG.onlyMig ? '只看有迁移量 ✓' : '只看有迁移量'}</button>
      </div>
      <p class="section-desc">括号内为 95% 区间。概率引擎区间来自候选路径后验的一/二阶矩；确定性区间由刷下车留出准确率校准的分配错误带 + 时刻表等待敏感包络。红色 OD 为抽样冲突。</p>
      <div class="table-wrap"><table class="data">
        <thead><tr>
          <th>OD 站对</th><th class="num">上客</th>
          <th>确定·旅程</th><th>确定·迁移量</th><th class="num">确定·人均损失(s)</th>
          <th>概率·旅程</th><th>概率·迁移量</th><th style="min-width:120px">迁移量条</th><th class="num">概率·人均损失(s)</th><th>核验</th>
        </tr></thead>
        <tbody>${shown.map(r => {
          const d = r.deterministic || {}, p = r.probabilistic || {};
          const v = vmap[r.origin + '|' + r.dest];
          const vm = v ? MIG_VERIFY_META[v.status] : null;
          const conflict = v && v.status === 'CONFLICT';
          return `<tr style="${conflict ? 'background:#fdeeec' : ''}">
            <td><b>${r.origin}</b> → <b>${r.dest}</b>${conflict ? ' <span class="badge red">冲突</span>' : ''}</td>
            <td class="num mono">${r.originBoardings}</td>
            <td>${migIv(d.journey_point, d.journey_low, d.journey_high)}</td>
            <td>${migIv(d.mig_point, d.mig_low, d.mig_high)}</td>
            <td class="num">${d.mig_point > 0.05 ? migIv(d.loss_mean_sec, d.loss_low_sec, d.loss_high_sec, 0) : '—'}</td>
            <td>${migIv(p.journey_point, p.journey_low, p.journey_high)}</td>
            <td>${migIv(p.mig_point, p.mig_low, p.mig_high)}</td>
            <td>${migBar(p.mig_point, maxHigh, 'purple')}</td>
            <td class="num">${p.mig_point > 0.05 ? migIv(p.loss_mean_sec, p.loss_low_sec, p.loss_high_sec, 0) : '—'}</td>
            <td>${vm ? badge(vm[0], vm[1]) : '<span class="muted">无抽样</span>'}</td>
          </tr>`;
        }).join('')}</tbody>
      </table></div>
      <div class="small muted" style="margin-top:8px">总额外换乘损失（概率，小时）：
        <b>${fmt(rows.reduce((s, r) => s + ((r.probabilistic || {}).total_loss_hours || 0), 0), 2)}</b> 小时
        · 区间 [${fmt(rows.reduce((s, r) => s + ((r.probabilistic || {}).total_loss_low || 0), 0), 2)},
        ${fmt(rows.reduce((s, r) => s + ((r.probabilistic || {}).total_loss_high || 0), 0), 2)}] 小时（逐 OD 求和）。
      </div>
    </div>`;
};
window.migToggleOnlyMig = async function () { MIG.onlyMig = !MIG.onlyMig; await MIG_VIEW.matrix($('#mig-body')); };

/* ---------- ① 上车观察账 ---------- */
MIG_VIEW.boardings = async function (el) {
  const q = MIG.align !== 'ALL' ? `&alignStatus=${MIG.align}` : '';
  const o = MIG.outageOnly ? '&outage=true' : '';
  const d = await api(`/migration/boardings?limit=400${q}${o}`);
  const rows = d.rows || [];
  el.innerHTML = `
    <div class="card">
      <div class="toolbar" style="margin-top:0;flex-wrap:wrap">
        <h3 style="margin:0">上车观察账（首乘刷卡 × AVL 班次对齐）</h3>
        <span style="flex:1"></span>
        <div class="pill-row">
          ${['ALL', 'ALIGNED', 'NEAREST_FALLBACK', 'GPS_GAP_FUZZY', 'UNMATCHED'].map(a =>
            `<button class="filter-chip ${MIG.align === a ? 'active' : ''}" onclick="migSetAlign('${a}')">${a === 'ALL' ? '全部' : (MIG_ALIGN_META[a] ? MIG_ALIGN_META[a][0] : a)}</button>`).join('')}
        </div>
        <button class="filter-chip ${MIG.outageOnly ? 'active' : ''}" onclick="migToggleOutage()">仅定位中断</button>
        <input id="mig-q" placeholder="搜索卡号/站/班次" value="${MIG.bq || ''}"
          style="padding:7px 10px;border:1px solid var(--line);border-radius:8px"
          onkeydown="if(event.key==='Enter')migBoardSearch()"/>
        <button class="btn sm" onclick="migBoardSearch()">搜索</button>
      </div>
      <div class="table-wrap" style="max-height:62vh"><table class="data">
        <thead><tr><th>账/卡号</th><th>时间</th><th>上车站</th><th>线路/车</th><th>对齐班次</th><th>对齐状态</th><th>标记</th><th class="num">概率迁移</th><th>确定终点</th></tr></thead>
        <tbody>${rows.map(b => {
          const am = MIG_ALIGN_META[b.align_status] || [b.align_status, 'gray'];
          return `<tr class="clickable" onclick="migBoarding('${b.boarding_code}')">
            <td class="mono small">${b.card_code}</td>
            <td class="mono">${migHM(b.board_minute)}</td>
            <td>${b.board_station}</td>
            <td>${b.route_code}<span class="muted small"> / ${b.vehicle_code || '—'}</span></td>
            <td class="mono small">${b.matched_trip || '—'}</td>
            <td>${badge(am[0], am[1])}</td>
            <td class="small">${b.location_outage ? badge('定位中断', 'purple') : ''} ${b.short_turn ? badge('短线折返', 'amber') : ''} ${b.has_tap_out ? badge('有下车', 'green') : ''} ${b.observed_transfer ? badge('有换乘', 'blue') : ''}</td>
            <td class="num mono">${b.mig_prob !== null && b.mig_prob !== undefined ? fmt(b.mig_prob, 2) : '—'}</td>
            <td class="small">${b.det_dest || '—'}</td>
          </tr>`;
        }).join('')}</tbody></table></div>
      <div class="small muted" style="margin-top:6px">共 ${rows.length} 条（上限 400）。点击行查看候选下车站与双引擎路径后验。</div>
    </div>`;
};
window.migSetAlign = async function (a) { MIG.align = a; await MIG_VIEW.boardings($('#mig-body')); };
window.migToggleOutage = async function () { MIG.outageOnly = !MIG.outageOnly; await MIG_VIEW.boardings($('#mig-body')); };
window.migBoardSearch = function () { MIG.bq = $('#mig-q').value.trim(); migRenderSub(); };

window.migBoarding = async function (code) {
  try {
    const d = await api('/migration/boardings/' + encodeURIComponent(code));
    const b = d.boarding, j = d.journey;
    const opts = (j && asObj(j.prob_option_map)) || [];
    migModal(`
      <h2>上车账 ${b.boarding_code}</h2>
      <p class="muted small">卡 ${b.card_code} · ${migHM(b.board_minute)} · ${b.board_station} · ${b.route_code}/${b.vehicle_code} · 对齐 ${b.matched_trip || '—'}（${b.align_status}，偏差 ${b.time_offset_sec}s）</p>
      <div class="grid cards-2">
        <div><h3>候选下车站集合（保留多候选）</h3>
          ${(d.candidates || []).map(c => `
            <div style="margin:8px 0">
              <div class="small"><b>${c.station_code}</b> ${c.det_selected ? badge('确定性选定', 'gray') : ''} <span class="muted">${c.retain_reason || ''}</span> <span class="mono" style="float:right">${fmt(c.posterior_prob, 3)}</span></div>
              ${migBar(c.posterior_prob, 1, 'purple')}
            </div>`).join('')}
        </div>
        <div><h3>双引擎结论</h3>
          <dl class="kv">
            <dt>确定性</dt><dd>${j ? j.det_dest + ' · ' + j.det_path + ' · 换乘 ' + j.det_transfers + ' · 损失 ' + j.det_loss_sec + 's' : '—'} ${j && j.det_mismatch_risk ? badge('可能错配', 'amber') : ''}</dd>
            <dt>概率迁移概率</dt><dd class="mono">${j ? fmt(j.mig_prob, 3) : '—'}</dd>
            <dt>期望损失/方差</dt><dd class="mono">${j ? fmt(j.prob_loss_mean, 0) + 's / ' + fmt(j.prob_loss_var, 0) : '—'}</dd>
            <dt>多候选/中断</dt><dd>${j && j.multi_candidate ? badge('多候选', 'purple') : badge('单一', 'gray')} ${j && j.location_outage ? badge('定位中断', 'purple') : ''}</dd>
          </dl>
        </div>
      </div>
      <h3>候选完整路径后验</h3>
      <div class="table-wrap"><table class="data"><thead><tr><th>终点</th><th>方案</th><th>路径</th><th class="num">换乘</th><th class="num">等待(s)</th><th class="num">后验</th><th>额外折返换乘</th></tr></thead>
        <tbody>${opts.map(o => `<tr>
          <td><b>${o.dest}</b></td><td class="small mono">${o.plan}</td><td class="small">${(o.routes || []).join(' → ')}</td>
          <td class="num">${o.transfers}</td><td class="num">${o.waitSec ?? o.wait_sec ?? ''}</td>
          <td class="num mono">${fmt(o.probability, 3)}</td>
          <td>${o.extraTransfer ? badge('是', 'red') : '—'}</td></tr>`).join('')}</tbody></table></div>`);
  } catch (e) { toast('加载详情失败：' + e.message, true); }
};

/* ---------- ② 轨迹对齐 ---------- */
MIG_VIEW.trajectories = async function (el) {
  const rows = await api('/migration/trajectories');
  el.innerHTML = `
    <div class="card">
      <h3 style="margin-top:0">车辆轨迹对齐（AVL/GPS 班次轨迹）</h3>
      <p class="section-desc">上车刷卡按 线路+车辆+站点时刻 对齐到具体班次；定位中断区间缺测站不强行吸附，改为插值模糊对齐并在下车推断中保留多候选。点击行查看逐站轨迹点。</p>
      <div class="table-wrap"><table class="data">
        <thead><tr><th>班次</th><th>线路</th><th>车</th><th>形态</th><th>折返站</th><th>定位中断</th><th class="num">拥堵延误(s)</th><th class="num">缺测 fix 点</th><th class="num">对齐上客</th></tr></thead>
        <tbody>${rows.map(t => `<tr class="clickable" onclick="migTrip('${t.trip_code}')">
          <td class="mono"><b>${t.trip_code}</b></td><td>${t.route_code}</td><td class="small">${t.vehicle_code}</td>
          <td>${t.short_turn ? badge('短线折返', 'amber') : badge('全程', 'green')}</td>
          <td>${t.turn_stop || '—'}</td>
          <td>${t.outage ? badge(`${t.outage_from_stop}→${t.outage_to_stop}`, 'purple') : '—'}</td>
          <td class="num">${t.congestion_sec || 0}</td>
          <td class="num">${t.missing_fix_points}</td>
          <td class="num">${t.aligned_boardings}</td>
        </tr>`).join('')}</tbody></table></div>
    </div>`;
};
window.migTrip = async function (code) {
  try {
    const d = await api('/migration/trajectories/' + encodeURIComponent(code));
    const t = d.trace;
    migModal(`<h2>轨迹 ${t.trip_code}</h2>
      <p class="muted small">${t.route_code} / ${t.vehicle_code} · ${t.short_turn ? '短线折返于 ' + t.turn_stop : '全程至 ' + t.last_actual_stop}${t.outage ? ' · 定位中断 ' + t.outage_from_stop + '→' + t.outage_to_stop : ''}</p>
      <div class="table-wrap"><table class="data"><thead><tr><th>seq</th><th>站</th><th>时刻</th><th class="num">km</th><th>fix</th></tr></thead>
        <tbody>${(d.points || []).map(p => `<tr style="${p.fix_ok ? '' : 'background:#f3efff'}">
          <td class="num">${p.seq_index}</td><td>${p.station_code || '（区间点）'}</td><td class="mono">${migHM(p.minute_of_day)}</td>
          <td class="num">${fmt(p.progress_km, 1)}</td><td>${p.fix_ok ? badge('OK', 'green') : badge('中断/缺测', 'purple')}</td></tr>`).join('')}</tbody></table></div>
      <h3>对齐到该班次的上车账</h3>
      <div class="small">${(d.boardings || []).map(b => `<span class="badge gray" style="margin:3px;cursor:pointer" onclick="closeModal();migBoarding('${b.boarding_code}')">${b.card_code} · ${b.board_station} · ${b.align_status}</span>`).join('') || '<span class="muted">无</span>'}</div>`);
  } catch (e) { toast('加载轨迹失败：' + e.message, true); }
};

/* ---------- ③ 重建旅程列表 ---------- */
MIG_VIEW.journeys = async function (el) {
  const qs = MIG.jMulti ? '&multiCandidate=true' : '';
  const d = await api(`/migration/journeys?limit=400${qs}`);
  const rows = d.rows || [];
  el.innerHTML = `
    <div class="card">
      <div class="toolbar" style="margin-top:0">
        <h3 style="margin:0">重建旅程与候选下车站</h3><span style="flex:1"></span>
        <button class="filter-chip ${MIG.jMulti ? 'active' : ''}" onclick="migToggleJMulti()">仅看多候选/中断</button>
      </div>
      <div class="table-wrap" style="max-height:62vh"><table class="data">
        <thead><tr><th>旅程/卡</th><th>起点</th><th>确定终点</th><th class="num">换乘</th><th>错配风险</th><th class="num">概率迁移</th><th class="num">期望损失(s)</th><th>标志</th></tr></thead>
        <tbody>${rows.map(j => `<tr class="clickable" onclick="migJourney('${j.card_code}')">
          <td class="mono small">${j.card_code}</td><td>${j.origin_stop}</td><td><b>${j.det_dest || '—'}</b></td>
          <td class="num">${j.det_transfers}</td>
          <td>${j.det_mismatch_risk ? badge('风险', 'amber') : badge('低', 'green')}</td>
          <td class="num mono">${fmt(j.mig_prob, 2)}</td>
          <td class="num">${fmt(j.prob_loss_mean, 0)}</td>
          <td class="small">${j.multi_candidate ? badge('多候选', 'purple') : ''} ${j.location_outage ? badge('中断', 'purple') : ''} ${j.short_turn ? badge('折返', 'amber') : ''}</td>
        </tr>`).join('')}</tbody></table></div>
      <div class="small muted" style="margin-top:6px">${rows.length} 条。点击查看候选集合与路径后验。</div>
    </div>`;
};
window.migToggleJMulti = async function () { MIG.jMulti = !MIG.jMulti; await MIG_VIEW.journeys($('#mig-body')); };
window.migJourney = async function (key) { return window.migBoarding(key); };

/* ---------- ⑤ 抽样核验 ---------- */
MIG_VIEW.verification = async function (el) {
  const d = await api('/migration/surveys');
  const vrows = d.verification || [];
  el.innerHTML = `
    <div class="card">
      <h3 style="margin-top:0">抽样调查核验：调查区间 vs 推断区间</h3>
      <p class="section-desc">抽样额外换乘比例用 Wilson 95% 区间并按 OD 代表总体换算为迁移量区间，再与概率推断区间比较。<b>区间不重叠即判 CONFLICT，该 OD 禁止发布单点迁移量。</b></p>
      <div class="table-wrap"><table class="data">
        <thead><tr><th>OD</th><th class="num">样本 n</th><th class="num">报告额外换乘</th><th>抽样比例(95% Wilson)</th><th>抽样迁移量区间</th><th>推断迁移量区间</th><th>结论</th><th>说明</th></tr></thead>
        <tbody>${vrows.map(v => {
          const m = MIG_VERIFY_META[v.status] || [v.status, 'gray'];
          return `<tr style="${v.status === 'CONFLICT' ? 'background:#fdeeec' : ''}">
            <td><b>${v.origin_stop}</b>→<b>${v.dest_stop}</b></td>
            <td class="num">${v.sample_n}</td><td class="num">${v.extra_yes}</td>
            <td>${fmt(v.survey_share, 3)} <span class="muted small">[${fmt(v.survey_low, 3)},${fmt(v.survey_high, 3)}]</span></td>
            <td class="mono small">[${fmt(v.survey_vol_low)}, ${fmt(v.survey_vol_high)}]</td>
            <td class="mono small">[${fmt(v.infer_low)}, ${fmt(v.infer_high)}]</td>
            <td>${badge(m[0], m[1])}</td>
            <td class="small muted">${v.note || ''}</td>
          </tr>`;
        }).join('')}</tbody></table></div>
    </div>
    <div class="card" style="margin-top:14px">
      <h3>原始抽样记录</h3>
      <div class="table-wrap"><table class="data">
        <thead><tr><th>样本号</th><th>OD</th><th class="num">n</th><th class="num">yes</th><th class="num">代表总体</th><th>来源</th><th>备注</th></tr></thead>
        <tbody>${(d.samples || []).map(s => `<tr>
          <td class="mono small">${s.sample_code}</td><td>${s.origin_stop}→${s.dest_stop}</td>
          <td class="num">${s.sample_n}</td><td class="num">${s.extra_yes}</td><td class="num">${s.od_population}</td>
          <td>${s.source}</td><td class="small muted">${s.note || ''}</td></tr>`).join('')}</tbody></table></div>
    </div>`;
};

/* ---------- 📣 发布门控 ---------- */
MIG_VIEW.publication = async function (el) {
  const [p, sv] = await Promise.all([api('/migration/publication'), api('/migration/verification')]);
  const g = p.gate || {}, hist = p.history || [], vrows = sv.rows || [];
  const conflicts = g.conflictOds || [];
  el.innerHTML = `
    <div class="${conflicts.length ? 'banner red' : 'banner green'}">
      <span class="big">${conflicts.length ? '⛔' : '✅'}</span>
      <div><b>门控：${g.status}</b><div>${g.reason || ''}</div></div>
    </div>
    <div class="grid cards-2" style="margin-top:14px">
      <div class="card">
        <h3>报告级发布</h3>
        <p class="section-desc">冲突存在时，“发布单点总量”会被服务端 409 拒绝；“发布包络区间”始终允许，状态为 INTERVAL_ONLY。</p>
        <div class="pill-row">
          <button class="btn primary" onclick="migPublish({scope:'REPORT',mode:'INTERVAL'})">发布报告级包络区间</button>
          <button class="btn danger" onclick="migPublish({scope:'REPORT',mode:'POINT'})">尝试发布报告级单点（应 409）</button>
        </div>
        <div id="pub-msg" class="small" style="margin-top:10px"></div>
      </div>
      <div class="card">
        <h3>分 OD 发布</h3>
        <div class="table-wrap" style="max-height:240px"><table class="data">
          <thead><tr><th>OD</th><th>状态</th><th>操作</th></tr></thead>
          <tbody>${vrows.map(v => `<tr>
            <td class="small"><b>${v.origin_stop}</b>→<b>${v.dest_stop}</b></td>
            <td>${badge((MIG_VERIFY_META[v.status] || [v.status])[0], (MIG_VERIFY_META[v.status] || [,'gray'])[1])}</td>
            <td class="pill-row">
              <button class="btn sm" onclick="migPublishOd('${v.origin_stop}','${v.dest_stop}','INTERVAL')">区间</button>
              <button class="btn sm ${v.status === 'CONFLICT' ? 'danger' : 'primary'}" onclick="migPublishOd('${v.origin_stop}','${v.dest_stop}','POINT')">单点</button>
            </td></tr>`).join('')}</tbody></table></div>
      </div>
    </div>
    <div class="card" style="margin-top:14px">
      <h3>发布历史（留痕）</h3>
      <div class="table-wrap" style="max-height:280px"><table class="data">
        <thead><tr><th>时间</th><th>范围</th><th>模式</th><th>状态</th><th class="num">单点</th><th class="num">区间</th><th>理由</th><th>发布人</th></tr></thead>
        <tbody>${hist.map(h => `<tr>
          <td class="small">${dt(h.created_at)}</td><td class="mono small">${h.scope}</td><td>${h.publish_mode}</td>
          <td>${badge(h.status, h.status === 'PUBLISHED' ? 'green' : 'amber')}</td>
          <td class="num">${h.point_value != null ? fmt(h.point_value) : '—'}</td>
          <td class="num mono small">${h.interval_low != null ? '[' + fmt(h.interval_low) + ',' + fmt(h.interval_high) + ']' : '—'}</td>
          <td class="small muted">${h.reason || ''}</td><td class="small">${h.published_by || ''}</td>
        </tr>`).join('')}</tbody></table></div>
    </div>`;
};
window.migPublishOd = function (o, d, mode) {
  return migPublish({ scope: 'OD', origin: o, dest: d, mode });
};
window.migPublish = async function (payload) {
  const msg = $('#pub-msg');
  try {
    const r = await api('/migration/publish', { method: 'POST', body: JSON.stringify(payload) });
    if (msg) msg.innerHTML = `<span class="expected-ok">已${r.status === 'PUBLISHED' ? '发布' : '仅发布区间'}：${r.scope} ${r.mode} ${r.intervalLow != null ? '[' + r.intervalLow + ',' + r.intervalHigh + ']' : '点=' + r.pointValue}</span>`;
    toast('发布成功：' + r.status);
    if (MIG.sub === 'publication') { const el = $('#mig-body'); await MIG_VIEW.publication(el); }
  } catch (e) {
    if (msg) msg.innerHTML = `<span class="expected-bad">服务端拒绝（${e.status}）：${e.message}</span>`;
    if (e.status === 403) toast('403：当前账号无权发布', true);
    else toast('已被门控拒绝：' + e.message, true);
  }
};

init().catch(e => {
  console.error(e);
  document.body.innerHTML = `<div style="padding:40px;font-family:sans-serif">初始化失败：${e.message}。<a href="/login.html">前往登录</a></div>`;
});
