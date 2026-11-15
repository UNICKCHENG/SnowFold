// 组件规范页：comps.html?device=mac|iphone&theme=&w=&h=
const dark = theme === "dark";
const root = document.getElementById('root');
root.style.width = W + 'px'; root.style.height = H + 'px';
const STATES = ['默认', '悬停', '按下', '禁用', '聚焦', '出错'];
const cell = (label, inner) => `<div class="col" style="gap:8px;align-items:flex-start"><span class="t-caption c2">${label}</span>${inner}</div>`;
const btnRow = (kind, label) => STATES.map((st, i) => {
  if (i === 5) return cell(st, kind === 'primary' ? `<span class="t-caption c2">按钮无出错态</span>` : '<span></span>');
  const cls = ['', 'hover', 'pressed', 'disabled', 'focus-ring'][i];
  const style = i === 1 && kind !== 'primary' ? `filter:brightness(${dark ? 1.25 : 0.96})` : '';
  return cell(st, `<button class="btn btn-${kind} ${cls}" style="${style}">${label}</button>`);
}).join('');
const section = (title, body, note) => `<div class="col" style="gap:12px"><div class="row" style="gap:12px;align-items:baseline"><span class="t-headline">${title}</span>${note ? `<span class="t-caption c2">${note}</span>` : ''}</div>${body}</div>`;
const grid6 = (inner) => `<div style="display:grid;grid-template-columns:repeat(6, 1fr);gap:16px;align-items:start">${inner}</div>`;
const fieldStates = STATES.map((st, i) => cell(st, `<div class="field ${i === 4 ? 'focused' : ''} ${i === 5 ? 'error' : ''}" style="width:100%;${i === 1 ? 'box-shadow:inset 0 0 0 1px var(--text2)' : ''}${i === 3 ? ';background:var(--bg);color:var(--text2)' : ''}">${i === 0 ? '<span class="ph">例如：微信零钱</span>' : '招商银行'}</div>${i === 5 ? '<span class="t-caption cneg">请检查名称、金额、日期与选项</span>' : ''}`)).join('');
const rowStates = ['默认', '悬停', '按下', '选中'].map((st, i) => cell(st, `<div class="card" style="width:100%"><div class="list-row ${i === 3 ? 'sel' : ''}" style="${i === 1 ? 'background:color-mix(in srgb, var(--text) 4%, var(--card))' : i === 2 ? 'background:color-mix(in srgb, var(--text) 8%, var(--card))' : ''}">${chip('cash', form === 'desktop' ? 28 : 32, form === 'desktop' ? 16 : 20)}<span class="grow t-body ${i === 3 ? 'cbrand' : ''}">微信零钱</span>${amt(8645032)}</div></div>`)).join('');
const syncStates = [['空闲', 'idle'], ['同步中', 'syncing'], ['失败', 'failed']].map(([l, st]) => cell(l, `<div class="icon-btn glass" style="width:${mob ? 44 : 32}px;height:${mob ? 44 : 32}px;position:relative">${icon('refresh', 20, false, st === 'syncing' ? 'spin' : '')}${st === 'failed' ? `<span class="dot-neg" style="top:${mob ? 8 : 6}px;right:${mob ? 8 : 6}px"></span>` : ''}</div><span class="t-caption c2" style="max-width:180px">${st === 'idle' ? '提示：立即同步 · 上次同步 3 分钟前' : st === 'syncing' ? '图标匀速旋转；读屏：正在同步…' : '右上红点；读屏：上次同步失败，点击重试'}</span>`)).join('');
const amounts = `<div class="row" style="gap:48px;align-items:flex-end;flex-wrap:wrap">
  ${cell('大标题 · 净资产（全应用唯一）', `<div style="width:380px">${hero(1000208126241)}</div>`)}${cell('页标题 · 页面焦点', hero(472108141, 'title'))}
  ${cell('列表金额 · 正文中等', `<div class="col" style="gap:4px">${amt(999999999999)}${amt(-123456789)}${amt(1549, 'USD')}</div>`)}</div>`;
const typeScale = [['大标题', 't-display', '40/48 半粗'], ['页标题', 't-title', '28/34 半粗'], ['分区标题', 't-headline', form === 'desktop' ? '15/20 半粗' : '17/22 半粗'], ['正文', 't-body', form === 'desktop' ? '13/18 常规·中等' : '17/22 常规·中等'], ['辅助', 't-caption', form === 'desktop' ? '11/14 常规·中等' : '13/18 常规·中等']]
  .map(([n, c, v]) => `<div class="row" style="gap:24px;align-items:baseline"><span class="t-caption c2" style="width:120px">${n} · ${v}</span><span class="${c}">资产与负债 ¥1,234.56</span></div>`).join('');
const sw = (on) => `<div class="switch ${on ? 'on' : ''}"></div>`;
const controls = `<div class="row" style="gap:40px;flex-wrap:wrap;align-items:flex-start">
  ${cell('分段控件', `<div class="seg" style="width:280px"><div class="on">自动</div><div>浅色</div><div>深色</div></div><div class="seg" style="width:280px;opacity:1"><div style="color:var(--text2)">每周</div><div class="on" style="background:var(--sep);color:var(--text2)">每月</div><div style="color:var(--text2)">每季</div></div><span class="t-caption c2">第二行：禁用</span>`)}
  ${cell('开关', `<div class="row" style="gap:12px">${sw(false)}${sw(true)}<div class="switch on" style="opacity:.4"></div><div class="switch" style="box-shadow:0 0 0 3px color-mix(in srgb, var(--brand) 40%, transparent)"></div></div><span class="t-caption c2">关 · 开 · 禁用 · 聚焦</span>`)}
  ${cell('选择器', `<div class="field" style="width:220px;justify-content:space-between"><span class="row" style="gap:8px">${chip('cash', 20, 16)}流动资金</span>${icon('chevron-right', 16, false, 'c2')}</div>`)}
  ${cell('日期选择', `<div class="field" style="width:220px;justify-content:space-between"><span class="num">2026年11月20日</span>${icon('calendar', 16, false, 'c2')}</div>`)}
  ${cell('徽标', `<div class="row" style="gap:8px"><span class="badge b-neg">逾期 3 天</span><span class="badge b-neg">今天</span><span class="badge b-warn">6 天后</span><span class="badge b-plain">14 天后</span><span class="badge b-brand">当前</span></div>`)}</div>`;
const icons = `<div class="row" style="gap:20px;flex-wrap:wrap">${['pie-chart', 'wallet', 'calendar-repeat', 'settings', 'banknote', 'home', 'trending-up', 'hand-coins', 'credit-card', 'close', 'plus', 'refresh', 'check', 'pause', 'play', 'trash', 'clock', 'undo', 'shield-lock', 'key', 'chevron-right', 'chevron-left', 'info', 'warning-triangle', 'database', 'calendar']
  .map(n => `<div class="col" style="align-items:center;gap:6px;width:76px"><div class="row" style="gap:6px">${icon(n, 20)}${['pie-chart', 'wallet', 'calendar-repeat', 'settings'].includes(n) ? icon(n, 20, true, 'cbrand') : ''}</div><span class="t-caption c2" style="font-size:10px">${n}</span></div>`).join('')}</div>`;
const sizes = `<div class="row" style="gap:24px;align-items:flex-end">${[16, 20, 24, 48].map(s => `<div class="col" style="align-items:center;gap:6px">${icon('wallet', s)}<span class="t-caption c2">${s}</span></div>`).join('')}</div>`;
const swatch = (name, v) => `<div class="col" style="gap:6px;width:112px"><div style="height:44px;border-radius:var(--r-control);background:var(${v});box-shadow:inset 0 0 0 1px var(--sep)"></div><span class="t-caption-m">${name}</span><span class="t-caption c2" style="font-family:monospace">${getComputedStyle(document.documentElement).getPropertyValue(v).trim()}</span></div>`;
const colors = `<div class="row" style="gap:12px;flex-wrap:wrap">${[['品牌', '--brand'], ['品牌淡色', '--brand-soft'], ['背景', '--bg'], ['卡片', '--card'], ['分隔线', '--sep'], ['次要文字', '--text2'], ['主文字', '--text'], ['负债·逾期', '--neg'], ['提醒', '--warn'], ['成功', '--pos'], ['流动资金', '--cat-cash'], ['固定资产', '--cat-fixed'], ['投资理财', '--cat-investment'], ['应收款', '--cat-receivable'], ['负债', '--cat-debt']].map(([n, v]) => swatch(n, v)).join('')}</div>`;
const empties = `<div class="row" style="gap:32px;align-items:flex-start;flex-wrap:wrap">
  <div class="card" style="width:360px;padding:32px 16px">${emptyState('pie-chart', '还没有记录', '记下第一笔资产，净资产会显示在这里', '添加资产')}</div>
  <div class="card" style="width:360px;padding:32px 16px">${emptyState('calendar-repeat', '未来 30 天没有扣费')}</div>
  <div class="col" style="gap:8px;width:360px"><span class="t-caption c2">分类空状态（紧凑行，见规范 §空状态 说明）</span><div class="card"><div class="list-row">${chip('cash', 28, 16)}<span class="grow t-body c2">暂无流动资金</span><button class="btn btn-tint" style="height:28px">${icon('plus', 16)}添加</button></div></div></div></div>`;
const toasts = `<div class="col" style="gap:12px;align-items:flex-start"><div class="toast glass" style="position:relative">${icon('trash', 16, false, 'c2')}<span class="t-body">已删除资产</span><button class="btn btn-text" style="height:32px">${icon('undo', 16)}撤销 · 6秒内</button></div>
  <div class="toast glass" style="position:relative">${icon('check', 16, false, 'cpos')}<span class="t-body">同步完成 · 拉取 3 条 · 上传 1 条</span><div class="icon-btn" style="width:32px;height:32px;color:var(--text2)">${icon('close', 16)}</div></div>
  <div class="toast glass" style="position:relative">${icon('alert', 16, false, 'cneg')}<span class="t-body">同步服务拒绝访问，请检查账号、密钥与权限</span><div class="icon-btn" style="width:32px;height:32px;color:var(--text2)">${icon('close', 16)}</div></div></div>`;
root.innerHTML = `<div class="col" style="gap:40px;padding:40px;background:var(--bg);min-height:100%">
  <div class="col" style="gap:4px"><span class="t-title">SnowFold 组件 · ${form === 'desktop' ? '桌面（Mac 定稿，Windows 照做）' : '移动（iOS 定稿，安卓照做）'} · ${dark ? '深色' : '浅色'}</span><span class="t-body c2">数值见设计规范；此页只示意形态与状态</span></div>
  ${section('颜色', colors)}${section('字号层级', `<div class="col" style="gap:12px">${typeScale}</div>`, '最多 5 级；字重只用常规、中等、半粗；金额一律等宽数字')}
  ${section('金额', amounts, '币种符与小数降一级；放不下整体缩小，最小 60%，绝不省略')}
  ${section('按钮 · 主要', grid6(btnRow('primary', '保存')))}${section('按钮 · 次要', grid6(btnRow('secondary', '导出')))}
  ${section('按钮 · 危险', grid6(btnRow('danger', '确认覆盖')))}${section('按钮 · 文字', grid6(btnRow('text', '撤销 · 6秒内')))}
  ${section('输入行', grid6(fieldStates))}${section('列表行与卡片', `<div style="display:grid;grid-template-columns:repeat(4,1fr);gap:16px">${rowStates}</div>`, '卡片圆角 16、无阴影、无描边；行分隔线从文字起始处开始')}
  ${section('控件', controls)}${section('图标', `<div class="col" style="gap:16px">${icons}${sizes}</div>`, 'IconMCP 线条风格，描边 1.25；导航选中用实心版；尺寸 16 / 20 / 24，空状态 48')}
  ${section('同步按钮', `<div class="row" style="gap:48px">${syncStates}</div>`)}${section('空状态', empties)}${section('撤销横幅与通知', toasts, '只用于浮层：毛玻璃 + 浮层阴影')}</div>`;
document.fonts.ready.then(() => { document.body.dataset.ready = '1'; });
