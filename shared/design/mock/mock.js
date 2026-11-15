// SnowFold 设计稿渲染器：mock.html?device=mac|iphone|ipad&w=&h=&theme=light|dark&screen=&state=&data=normal|long|empty
const Q = new URLSearchParams(location.search);
const device = Q.get('device') || 'mac';
const form = device === 'mac' ? 'desktop' : 'mobile';
const W = +Q.get('w') || (device === 'mac' ? 1080 : device === 'ipad' ? 1180 : 393);
const H = +Q.get('h') || (device === 'mac' ? 720 : device === 'ipad' ? 820 : 852);
const theme = Q.get('theme') || 'light';
const screen = Q.get('screen') || 'overview';
const state = Q.get('state') || '';
const dataKey = Q.get('data') || 'normal';
const D = DATASETS[dataKey];
const S = summarize(D);
const wide = device !== 'iphone';
document.documentElement.dataset.theme = theme;
document.body.dataset.form = form;
document.body.dataset.device = device;

// ---------- 基础 ----------
const h = (strings, ...vals) => strings.reduce((a, s, i) => a + s + (i < vals.length ? (Array.isArray(vals[i]) ? vals[i].join('') : (vals[i] ?? '')) : ''), '');
const icon = (name, size = 20, filled = false, cls = '') => {
  const set = filled ? (window.ICONS_SOLID || {}) : (window.ICONS || {});
  const svg = set[name] || (window.ICONS || {})[name] || `<svg viewBox="0 0 24 24"><rect x="4" y="4" width="16" height="16" rx="3" fill="none" stroke="currentColor" stroke-width="1.5"/><text x="12" y="15" font-size="7" text-anchor="middle" fill="currentColor">${name.slice(0, 3)}</text></svg>`;
  return `<span class="ic ${cls}" style="width:${size}px;height:${size}px">${svg}</span>`;
};
const cat = (code) => CATS.find(c => c.code === code);
const chip = (code, box, glyph) => `<div class="chip" style="width:${box}px;height:${box}px;background:var(--cat-${code}-soft);color:var(--cat-${code})">${icon(cat(code).icon, glyph)}</div>`;
const amt = (cents, cur = 'CNY', cls = 't-body-m') => `<span class="num ${cls} ${cents < 0 ? 'cneg' : ''}">${money(cents, cur)}</span>`;
const dual = (o, cls = 't-body-m') => o.USD ? `${amt(o.CNY, 'CNY', cls)}<span class="c2 ${cls}"> / </span>${amt(o.USD, 'USD', cls)}` : amt(o.CNY, 'CNY', cls);
const shortDate = (iso) => { const [, m, d] = iso.split('-'); return `${+m}月${+d}日`; };
// 金额大字：整数段 display，币种符与小数段降一级；放不下时整体缩小（最小 60%），绝不省略
const hero = (cents, level = 'display') => {
  const s = money(cents); const neg = s.startsWith('−');
  const body = s.replace('−', ''); const sym = body[0]; const [int, dec] = body.slice(1).split('.');
  const big = level === 'display' ? 't-display' : 't-title';
  const small = level === 'display' ? 't-title' : 't-headline';
  return `<div class="fit num" data-fit style="white-space:nowrap;${neg ? 'color:var(--neg)' : ''}">${neg ? `<span class="${big}">−</span>` : ''}<span class="${small}" style="font-weight:500;margin-right:2px">${sym}</span><span class="${big}">${int}</span><span class="${small}" style="font-weight:500;${neg ? '' : 'color:var(--text2)'}">.${dec}</span></div>`;
};
const badgeFor = (s) => s.group === 'overdue' ? `<span class="badge b-neg">逾期 ${s.overdue} 天</span>`
  : s.group === 'paused' ? '' : s.days === 0 ? `<span class="badge b-neg">今天</span>`
  : s.days <= 7 ? `<span class="badge b-warn">${s.days} 天后</span>` : `<span class="badge b-plain">${s.days} 天后</span>`;
const avatar = (s, size) => `<div class="chip" style="width:${size}px;height:${size}px;border-radius:50%;background:${s.group === 'overdue' ? 'var(--neg-soft)' : s.group === 'paused' ? 'var(--bg)' : 'var(--brand-soft)'};color:${s.group === 'overdue' ? 'var(--neg)' : s.group === 'paused' ? 'var(--text2)' : 'var(--brand)'};font:var(--t-headline)">${[...s.name][0]}</div>`;
const emptyState = (ic, title, desc, btn, neg = false) => `<div class="empty">
  <div class="disc ${neg ? 'neg' : ''}">${icon(ic, 48)}</div>
  <div class="ttl">${title}</div>${desc ? `<div class="desc">${desc}</div>` : ''}
  ${btn ? `<button class="btn btn-primary">${btn}</button>` : ''}</div>`;
const GROUPS = [['overdue', '待确认'], ['this_month', '本月'], ['next_month', '下月'], ['later', '之后'], ['paused', '已暂停']];
const syncState = Q.get('sync') || (D.sync ? 'idle' : 'off');
const syncBtn = (size) => syncState === 'off' ? '' : `<div class="icon-btn glass" style="width:${size}px;height:${size}px;position:relative" title="立即同步 · 上次同步 3 分钟前">
  ${icon('refresh', 20, false, syncState === 'syncing' ? 'spin' : '')}${syncState === 'failed' ? `<span class="dot-neg" style="top:${size * 0.18}px;right:${size * 0.18}px"></span>` : ''}</div>`;

// ---------- 页面内容（宽屏：mac / ipad 共用结构） ----------
const pct = r => r > 0 && r < 0.0005 ? '&lt;0.1%' : (r * 100).toFixed(1) + '%';
function overviewBody() {
  if (!D.assets.length) return `<div class="col" style="height:100%;justify-content:center;padding-bottom:8%">${emptyState('pie-chart', '还没有记录', '记下第一笔资产，净资产会显示在这里', '添加资产')}</div>`;
  const total = S.assets || 1;
  const bar = `<div class="bar">${S.byCat.filter(c => c.code !== 'debt' && c.total > 0).map(c => `<div style="flex:${c.total / total};background:var(--cat-${c.code})"></div>`).join('')}</div>`;
  const comp = S.byCat.map(c => `<div class="list-row" style="--inset:${wide ? 56 : 60}px">${chip(c.code, form === 'desktop' ? 28 : 32, form === 'desktop' ? 16 : 20)}
      <div class="grow col"><span class="t-body-m">${c.name}</span>${form === 'mobile' ? `<span class="t-caption c2">${c.items.length} 项</span>` : ''}</div>
      ${form === 'desktop' ? `<span class="t-caption c2" style="width:40px">${c.items.length} 项</span>` : ''}
      <div class="col" style="align-items:flex-end">${amt(c.total)}${c.code !== 'debt' && form === 'mobile' ? `<span class="t-caption c2 num">${pct(c.total / total)}</span>` : ''}</div>
      ${form === 'desktop' ? `<span class="t-caption c2 num" style="width:44px;text-align:right">${c.code === 'debt' ? '' : (c.total / total * 100).toFixed(1) + '%'}</span>` : ''}</div>`).join('');
  const up = S.upcoming.length ? S.upcoming.map(s => `<div class="list-row">
      ${wide ? `<span class="t-body c2 num" style="width:${form === 'desktop' ? 56 : 72}px">${shortDate(s.next)}</span>` : ''}
      <div class="grow col"><span class="t-body wrap">${s.name}</span>${!wide ? `<span class="t-caption c2">${shortDate(s.next)}</span>` : ''}</div>
      ${badgeFor(s)}${amt(s.cents, s.cur)}</div>`).join('')
    : `<div style="padding:32px 16px">${emptyState('calendar-repeat', '未来 30 天没有扣费')}</div>`;
  const summaryLine = S.overdue ? `<span class="cneg">${S.active} 个订阅 · ${S.overdue} 笔待确认</span>` : `${S.active} 个订阅 · 下一笔 11月20日`;
  const secHead = (t, link) => `<div class="sec-head"><span class="t-headline grow">${t}</span><span class="${form === 'desktop' ? 't-body' : 't-caption-m'} cbrand row" style="gap:2px">${link}${icon('chevron-right', 16)}</span></div>`;
  const metric = (label, v, neg) => `<div class="col" style="gap:2px"><span class="t-caption c2">${label}</span><span class="t-body-m num ${neg ? 'cneg' : ''}">${v}</span></div>`;
  const heroBlock = `<div class="col" style="gap:4px"><span class="t-body-m c2">净资产</span>${hero(S.net)}</div>`;
  const metrics = `<div class="row" style="gap:${wide ? 32 : 20}px;flex-wrap:wrap;row-gap:12px">${metric('总资产', money(S.assets))}${metric('总负债', money(S.debt), true)}${metric('负债率', pct(S.ratio / 100))}</div>`;
  // 高窗口（≥800）桌面与 iPad：构成与扣费改为双行宽松行，填满纵向空间
  const roomy = wide;
  const compRoomy = S.byCat.map(c => `<div class="list-row" style="--inset:56px;min-height:64px">${chip(c.code, 28, 16)}<div class="grow col" style="gap:6px">
      <div class="row" style="gap:8px"><span class="t-body-m">${c.name}</span><span class="t-caption c2">${c.items.length} 项</span><span class="grow"></span>${amt(c.total)}</div>
      ${c.code === 'debt' ? `<span class="t-caption c2">不计入资产构成</span>` : `<div class="row" style="gap:8px"><div class="grow" style="height:4px;border-radius:2px;background:var(--bg)"><div style="width:${Math.max(0.5, c.total / total * 100)}%;height:100%;border-radius:2px;background:var(--cat-${c.code})"></div></div><span class="t-caption c2 num" style="width:44px;text-align:right">${pct(c.total / total)}</span></div>`}</div></div>`).join('');
  const upRoomy = S.upcoming.length ? S.upcomingAll.slice(0, device === "mac" && H >= 880 ? 8 : 5).map(s => `<div class="list-row" style="min-height:64px">${avatar(s, 32)}
      <div class="grow col"><span class="t-body-m wrap">${s.name}</span><span class="t-caption c2">${s.cycle} · ${shortDate(s.next)}</span></div>${badgeFor(s)}${amt(s.cents, s.cur)}</div>`).join('') : null;
  const compCard = `<div>${secHead('资产构成', '全部明细')}<div class="card">${roomy ? compRoomy : `<div style="padding:16px 16px 4px">${bar}</div>${comp}`}</div></div>`;
  const upCard = `<div>${secHead('即将扣费', '全部订阅')}<div class="card">${roomy && upRoomy ? upRoomy : up}${S.upcoming.length ? `<div class="list-row t-caption c2" style="min-height:40px">${summaryLine}</div>` : ''}</div></div>`;
  if (wide) return `<div class="col" style="gap:32px;padding:${form === 'desktop' ? '8px 32px 32px' : '8px 24px 32px'}">
     <div class="col" style="gap:16px">${heroBlock}${metrics}</div>
     <div style="display:grid;grid-template-columns:1fr 1fr;gap:24px;align-items:start">${compCard}${upCard}</div></div>`;
  return `<div class="col" style="gap:28px;padding:8px 16px 120px"><div class="col" style="gap:16px;padding:0 4px">${heroBlock}${metrics}</div>${compCard}${upCard}</div>`;
}

function assetSections(selId) {
  return S.byCat.map(c => {
    const head = `<div class="sec-head" style="gap:10px">${chip(c.code, 28, 16)}<span class="t-headline">${c.name}</span><span class="t-caption c2">${c.items.length} 项</span><span class="grow"></span>${amt(c.total)}</div>`;
    const rows = c.items.length ? c.items.map(a => `<div class="list-row ${a.id === selId ? 'sel' : ''}">
        <span class="grow t-body wrap" style="${a.id === selId ? 'color:var(--brand);font-weight:500' : ''}">${a.name}</span>${amt(a.cents)}${form === 'mobile' ? icon('chevron-right', 16, false, 'c2') : ''}</div>`).join('')
      : `<div class="list-row"><span class="grow t-body c2">暂无${c.name}</span><button class="btn btn-tint" style="height:${form === 'desktop' ? 28 : 32}px">${icon('plus', 16)}添加</button></div>`;
    return `<div>${head}<div class="card">${rows}</div></div>`;
  }).join('');
}
function historyRows(a, n) {
  let bal = a.cents; const rows = [];
  for (let i = 0; i < n; i++) {
    const delta = i === n - 1 ? null : Math.round(a.cents * 0.03 * (i % 2 ? -1 : 1)) || 1200;
    rows.push(`<div class="list-row">
      <div class="grow col" style="min-width:0;gap:2px"><div class="row" style="gap:8px"><span class="t-body num wrap">${['2026年11月14日 09:41','2026年11月2日 20:15','2026年10月21日 08:30','2026年10月3日 19:02','2026年9月18日 12:47','2026年8月30日 21:10','2026年8月1日 09:05','2026年7月12日 18:20'][i] || '2026年6月1日 10:00'}</span></div>
      <div class="row" style="gap:8px">${i === 0 ? '<span class="badge b-brand">当前</span>' : ''}<span class="t-caption c2 num">${delta === null ? '初始' : (delta > 0 ? '+' : '') + money(delta)}</span></div></div>
      ${amt(bal)}<button class="btn btn-text" style="height:${form === 'desktop' ? 28 : 44}px">修正</button></div>`);
    if (delta !== null) bal -= delta;
  }
  return rows.join('');
}
function assetDetail(a) {
  return `<div class="col" style="gap:24px">
    <div class="col" style="gap:8px"><div class="row" style="gap:8px">${chip(a.cat, 24, 16)}<span class="t-caption-m c2">${cat(a.cat).name}</span></div>
      <span class="t-headline wrap">${a.name}</span>${hero(a.cents, 'title')}
      <span class="t-caption c2">更新于 ${a.updated} · 历史 ${a.hist} 条</span></div>
    <div class="row" style="gap:8px"><button class="btn btn-primary">调整余额</button><button class="btn btn-secondary">重命名</button><span class="grow"></span><button class="btn btn-text-neg">${icon('trash', 16)}删除</button></div>
    <div><div class="sec-head"><span class="t-headline">变动记录</span><span class="t-caption c2">修正不会新增快照</span></div><div class="card">${historyRows(a, state === 'history' ? 8 : 4)}</div></div></div>`;
}
function assetOverviewPane() {
  const total = S.assets || 1;
  return `<div class="col" style="gap:24px"><div class="col" style="gap:4px"><span class="t-body-m c2">总资产</span>${hero(S.assets, 'title')}</div>
    <div class="row" style="gap:32px"><div class="col"><span class="t-caption c2">总负债</span>${amt(S.debt)}</div><div class="col"><span class="t-caption c2">净资产</span>${amt(S.net)}</div><div class="col"><span class="t-caption c2">记录</span><span class="t-body-m num">${D.assets.length} 项</span></div></div>
    <div class="card">${S.byCat.filter(c => c.code !== 'debt').map(c => `<div class="list-row" style="--inset:56px">${chip(c.code, 28, 16)}<div class="grow col" style="gap:6px"><div class="row"><span class="t-body-m grow">${c.name}</span>${amt(c.total)}</div>
      <div class="row" style="gap:8px"><div class="grow" style="height:4px;border-radius:2px;background:var(--bg)"><div style="width:${c.total / total * 100}%;height:100%;border-radius:2px;background:var(--cat-${c.code})"></div></div><span class="t-caption c2 num" style="width:44px;text-align:right">${pct(c.total / total)}</span></div></div></div>`).join('')}</div></div>`;
}
function assetsBody() {
  if (!D.assets.length) return `<div class="col" style="height:100%;justify-content:center;padding-bottom:8%">${emptyState('wallet', '还没有资产', '现金、房产、投资、借出和欠款都可以记', '添加资产')}</div>`;
  const sel = state === 'selected' || state === 'history' ? D.assets[0] : null;
  if (wide) {
    const listW = W >= 1400 ? 440 : 380;
    return `<div class="row" style="align-items:stretch;height:100%">
      <div class="col" style="width:${listW}px;gap:24px;padding:8px 16px 32px ${form === 'desktop' ? 32 : 24}px;overflow:hidden">${assetSections(sel && sel.id)}</div>
      <div class="grow" style="padding:8px ${form === 'desktop' ? 32 : 24}px 32px 16px;overflow:hidden">${sel ? assetDetail(sel) : assetOverviewPane()}</div></div>`;
  }
  return `<div class="col" style="gap:24px;padding:8px 16px 120px">
    <div class="col" style="gap:4px;padding:0 4px"><span class="t-body-m c2">总资产</span>${hero(S.assets, 'title')}
      <div class="row" style="gap:20px;margin-top:8px;flex-wrap:wrap"><div class="col"><span class="t-caption c2">总负债</span>${amt(S.debt)}</div><div class="col"><span class="t-caption c2">净资产</span>${amt(S.net)}</div></div></div>
    ${assetSections()}</div>`;
}
function subRow(s) {
  const paused = s.group === 'paused';
  const seg = (t) => `<span style="white-space:nowrap">${t}</span>`;
  const sub = `${seg(s.cycle)} · ${seg(shortDate(s.next))}`;
  if (form === 'desktop' || device === 'ipad') return `<div class="list-row" style="--inset:${device === 'ipad' ? 68 : 60}px;min-height:var(--row2)">${avatar(s, device === 'ipad' ? 36 : 32)}
     <div class="grow col"><span class="t-body-m wrap ${paused ? 'c2' : ''}">${s.name}</span><span class="t-caption c2">${sub}</span></div>
     ${badgeFor(s)}<span style="min-width:${device === 'ipad' ? 140 : 120}px;text-align:right">${amt(s.cents, s.cur, 't-body-m' + (paused ? ' c2' : ''))}</span>
     <span class="row" style="width:${device === 'ipad' ? 132 : 112}px;justify-content:flex-end">${s.group === 'overdue' ? `<button class="btn btn-tint" style="height:${device === 'ipad' ? 36 : 28}px">${icon('check', 16)}已续费</button>` : ''}</span></div>`;
  return `<div class="list-row" style="--inset:64px;min-height:var(--row2)">${avatar(s, 36)}
    <div class="grow col"><span class="t-body wrap ${paused ? 'c2' : ''}">${s.name}</span><span class="t-caption ${s.group === 'overdue' ? 'cneg' : 'c2'}">${s.group === 'overdue' ? seg(`逾期 ${s.overdue} 天`) + ' · ' : ''}${sub}</span></div>
    <div class="col" style="align-items:flex-end;gap:4px">${amt(s.cents, s.cur, 't-body-m' + (paused ? ' c2' : ''))}${s.group === 'overdue' ? `<button class="btn btn-tint" style="height:32px;padding:0 10px;font:var(--t-caption-m)">${icon('check', 16)}已续费</button>` : ''}</div></div>`;
}
function subsBody() {
  if (!D.subs.length) return `<div class="col" style="height:100%;justify-content:center;padding-bottom:8%">${emptyState('calendar-repeat', '还没有订阅', '记下常用服务，随时知道接下来要扣多少钱', `${icon('plus', 16)}新建订阅`)}</div>`;
  const usd = (o, cls) => o.USD ? `<span class="${cls} c2 num">+ ${money(o.USD, 'USD')}</span>` : '';
  const stat = (label, o) => `<div class="col" style="gap:2px;flex:1;min-width:0"><span class="t-caption c2 row" style="gap:4px">${label}${icon('info', 16)}</span>${amt(o.CNY, 'CNY', 't-headline')}${usd(o, 't-caption')}</div>`;
  const groups = GROUPS.map(([g, label]) => { const items = D.subs.filter(s => s.group === g).sort((a, b) => a.next.localeCompare(b.next)); if (!items.length) return '';
    return `<div><div class="sec-head"><span class="t-caption-m ${g === 'overdue' ? 'cneg' : 'c2'}">${label}</span><span class="t-caption c2">${items.length}</span></div><div class="card">${items.map(subRow).join('')}</div></div>`; }).join('');
  const head = `<div class="row" style="gap:${wide ? 40 : 16}px;align-items:flex-end;flex-wrap:wrap;row-gap:16px">
      <div class="col" style="gap:4px;${wide ? '' : 'width:100%'}"><span class="t-body-m c2">本月待扣</span>${hero(S.due.CNY, 'title')}${usd(S.due, 't-body')}</div>
      <div class="row" style="gap:${wide ? 40 : 16}px;${wide ? '' : 'width:100%'}">${stat('月均支出', S.monthly)}${stat('年化支出', S.annual)}</div></div>`;
  return `<div class="col" style="gap:24px;padding:8px ${form === 'desktop' ? 32 : wide ? 24 : 16}px ${wide ? 32 : 120}px;${device === 'ipad' ? '' : ''}">
    <div style="padding:0 4px">${head}</div><span class="t-caption c2" style="padding:0 4px;margin-top:-8px">${S.active} 个在订${S.paused ? ` · ${S.paused} 个已暂停` : ''}</span>${groups}</div>`;
}
