// ---------- 设置 ----------
const syncConfigured = Q.get('syncconf') !== 'no';
const setRow = (title, help, ctrl, opts = {}) => `<div class="list-row" style="min-height:${help ? 'var(--row2)' : 'var(--row)'};gap:16px">
  <div class="grow col" style="gap:2px"><span class="t-body ${opts.neg ? 'cneg' : ''}">${title}</span>${help ? `<span class="t-caption c2" style="max-width:${form === 'desktop' ? 360 : 300}px">${help}</span>` : ''}</div>${ctrl || ''}</div>`;
const group = (title, rows, foot) => `<div><div class="sec-head"><span class="t-caption-m c2">${title}</span></div><div class="card">${rows.join('')}</div>${foot ? `<div class="t-caption c2" style="padding:8px 16px 0">${foot}</div>` : ''}</div>`;
const mob = form === 'mobile';
const actBtn = (label, kind = 'secondary') => mob ? (kind === 'danger' ? '' : icon('chevron-right', 16, false, 'c2')) : `<button class="btn btn-${kind}" style="height:28px">${label}</button>`;
const themeSeg = `<div class="seg" style="${mob ? 'width:100%' : 'width:240px'}"><div class="on">自动</div><div>浅色</div><div>深色</div></div>`;
function settingsGeneral() {
  return [group('外观', [mob ? `<div class="col" style="padding:12px 16px;gap:8px"><span class="t-body">主题</span>${themeSeg}</div>` : setRow('主题', '跟随系统会自动匹配电脑的深浅色。', themeSeg)])];
}
function settingsData() {
  return [group('数据', [setRow('导出备份', '保存为 JSON 文件，可随时导入恢复。', actBtn('导出')), setRow('导入备份', '会覆盖当前所有记录，请先导出备份。', actBtn('导入')),
    setRow('清空全部数据', '删除本机上的全部资产与订阅记录。', actBtn('清空', 'danger'), { neg: mob })])];
}
function settingsSeal() {
  return [group('加密备份', [setRow('加密备份', '导出 .sfseal 密文，可安全上传网盘或发给家人。', actBtn('导出密文')), setRow('恢复加密备份', '导入密文并覆盖当前数据；需要本机同步密钥。', actBtn('导入密文')),
    setRow('同步密钥指纹', '', `<span class="t-body c2" style="font-family:ui-monospace,'JetBrains Mono',monospace">7F3A·91C2·E04B·5D18</span>`),
    setRow('同步密钥恢复码', '换机或重装后恢复加密备份；请离线保存，不要放云盘同步目录。', mob ? '' : `<div class="row" style="gap:8px">${actBtn('导出恢复码')}${actBtn('导入恢复码')}</div>`),
    ...(mob ? [setRow('导出恢复码', '', actBtn()), setRow('导入恢复码', '', actBtn())] : [])])];
}
function settingsSync() {
  const f = (label, value, ph) => mob ? `<div class="list-row" style="gap:16px"><span class="t-body" style="width:112px;flex:none">${label}</span><span class="grow t-body ${value ? '' : 'c2'} wrap">${value || ph || ''}</span></div>`
    : `<span class="lbl" style="text-align:right">${label}</span><div class="field">${value ? `<span class="wrap">${value}</span>` : `<span class="ph">${ph || ''}</span>`}</div>`;
  if (!syncConfigured) {
    const fields = [f('存储类型', 'S3 兼容存储'), f('服务地址', '', 'https://'), f('存储桶', ''), f('区域', '', 'auto'), f('路径前缀（可选）', ''), f('Access Key ID', ''), f('Secret Access Key', '')];
    return [group('同步', mob ? [...fields, `<div class="list-row"><span class="grow t-body">使用路径风格地址</span><div class="switch"></div></div>`]
      : [`<div style="padding:16px"><div class="form-grid" style="grid-template-columns:136px 1fr;row-gap:10px">${fields.join('')}<span></span><div class="row" style="gap:8px"><div class="switch"></div><span class="t-body">使用路径风格地址</span></div></div>
         <div class="row" style="justify-content:flex-end;margin-top:16px"><button class="btn btn-primary" style="height:28px">保存配置</button></div></div>`],
      '数据先用本机同步密钥加密再上传，服务器只能看到密文。只在你打开应用或点「立即同步」时联网。')];
  }
  return [group('同步', [
    setRow('开启同步', '上次同步 3 分钟前', `<div class="switch on"></div>`),
    setRow('存储', 'S3 兼容存储 · https://s3.example.com/snowfold-family-ledger-backups', mob ? actBtn() : actBtn('修改')),
    `<div class="list-row" style="justify-content:${mob ? 'center' : 'flex-end'};gap:8px">${mob ? `<button class="btn btn-text">${icon('refresh', 16)}立即同步</button>` : `<button class="btn btn-secondary" style="height:28px">${icon('refresh', 16)}立即同步</button>`}</div>`],
    '数据先用本机同步密钥加密再上传，服务器只能看到密文。只在你打开应用或点「立即同步」时联网。'),
  group('同步策略', [setRow('打开应用时同步', '', `<div class="switch on"></div>`), setRow('自动同步', '', mob ? `<span class="row c2" style="gap:4px">每 30 分钟${icon('chevron-right', 16)}</span>` : `<div class="field" style="height:28px;width:140px;justify-content:space-between">每 30 分钟${icon('chevron-right', 16, false, 'c2')}</div>`), setRow('修改后自动同步', '', `<div class="switch on"></div>`)],
    '自动同步只在应用打开时进行，关闭应用后不会在后台联网。'),
  `<div class="row" style="justify-content:${mob ? 'center' : 'flex-start'}"><button class="btn btn-text-neg">清除配置</button></div>`];
}
function settingsAbout() {
  return [group('关于', [setRow('SnowFold · MVP 0.1', '默认离线运行 · 仅在开启同步后联网', '')])];
}
const SET_TABS = [['general', '通用', 'settings'], ['data', '数据', 'database'], ['seal', '加密与同步', 'shield-lock'], ['about', '关于', 'info']];
function settingsSections(tab) {
  if (mob || device === 'ipad') return [...settingsGeneral(), ...settingsData(), ...settingsSeal(), ...settingsSync(), ...settingsAbout()];
  return { general: settingsGeneral(), data: settingsData(), seal: [...settingsSeal(), ...settingsSync()], about: settingsAbout() }[tab];
}

// ---------- 弹层与表单 ----------
const err = state.endsWith('error');
function formFields(kind) {
  const F = (label, inner, opts = {}) => ({ label, inner, ...opts });
  if (kind === 'asset') return [F('分类', `<div class="field" style="justify-content:space-between">${`<span class="row" style="gap:8px">${chip('cash', 20, 16)}流动资金</span>`}${icon('chevron-right', 16, false, 'c2')}</div>`, { raw: true }),
    F('名称', err ? '' : '招商银行 储蓄卡', { ph: '例如：招商银行 储蓄卡', error: err }), F('金额', err ? 'abc' : '86,450.32', { prefix: '¥', ph: '0.00', error: err })];
  if (kind === 'adjust') return [F('资产', '招商银行 储蓄卡', { ro: true }), F('当前余额', money(8645032), { ro: true, num: true }), F('新余额', err ? '' : '90,000.00', { prefix: '¥', ph: '0.00', error: err, focused: !err })];
  if (kind === 'correct') return [F('记录时间', '2026年11月12日 20:15', { ro: true, num: true }), F('原金额', money(8645032), { ro: true, num: true }), F('修正为', err ? '-' : '86,540.32', { prefix: '¥', ph: '0.00', error: err, focused: !err })];
  if (kind === 'sub') return [F('名称', err ? 'Netflix' : 'Netflix', { ph: '例如：Netflix' }), F('金额', '15.49', { prefix: '$', ph: '0.00' }),
    F('币种', `<div class="seg" style="width:100%"><div>¥ 人民币</div><div class="on">$ 美元</div></div>`, { raw: true }),
    F('扣费周期', `<div class="seg" style="width:100%"><div>每周</div><div class="on">每月</div><div>每季</div><div>每年</div></div>`, { raw: true }),
    F('下次扣费日', `<div class="field ${err ? 'error' : ''}" style="justify-content:space-between">${err ? '<span class="ph">选择日期</span>' : '<span class="num">2026年11月20日</span>'}${icon('calendar', 16, false, 'c2')}</div>`, { raw: true, errMsg: err ? '请选择下次扣费日' : '' })];
}
const DLG = { asset: ['添加资产', 'asset'], adjust: ['调整余额', 'adjust'], correct: ['修正历史', 'correct'], 'sub-new': ['新建订阅', 'sub'], 'sub-edit': ['编辑订阅', 'sub'] };
function fieldHtml(f) {
  if (f.raw) return f.inner;
  if (f.ro) return `<div class="field readonly"><span class="${f.num ? 'num' : ''} wrap">${f.inner}</span></div>`;
  return `<div class="field ${f.error ? 'error' : ''} ${f.focused ? 'focused' : ''}">${f.prefix ? `<span class="c2">${f.prefix}</span>` : ''}${f.inner ? `<span class="${f.prefix ? 'num' : ''} grow">${f.inner}</span>` : `<span class="ph grow">${f.ph || ''}</span>`}</div>`;
}
function desktopDialog(key) {
  const [title, kind] = DLG[key]; const fields = formFields(kind);
  const help = kind === 'sub' ? `<span></span><span class="t-caption c2">填写名称、金额并选择下次扣费日。到期确认续费后按周期顺延。</span>` : kind === 'correct' ? `<span></span><span class="t-caption c2">修正不会新增快照</span>` : '';
  const grid = fields.map(f => `<span class="lbl">${f.label}</span>${fieldHtml(f)}${f.errMsg ? `<span class="err">${f.errMsg}</span>` : ''}`).join('');
  const errLine = err && kind !== 'sub' ? `<div class="row cneg t-caption" style="gap:6px;padding:0 24px 0 128px;margin-top:-4px">${icon('alert', 16)}请检查名称、金额、日期与选项</div>` : '';
  const left = key === 'sub-edit' ? `<button class="btn btn-text-neg" style="padding:0 4px">${icon('trash', 16)}删除</button><button class="btn btn-text" style="padding:0 4px">${icon('pause', 16)}暂停</button>` : '';
  const w = 480;
  return `<div class="scrim"></div><div class="dialog" style="width:${w}px;left:${(W - w) / 2}px;top:${Math.max(48, (H - (kind === 'sub' ? 420 : 300)) / 2 - 24)}px">
    <div class="t-headline" style="padding:20px 24px 4px">${title}</div>
    <div style="padding:16px 24px 20px"><div class="form-grid">${grid}${help}</div></div>${errLine}
    <div class="row" style="gap:8px;padding:${errLine ? 16 : 4}px 24px 20px">${left}<span class="grow"></span><button class="btn btn-secondary">取消</button><button class="btn btn-primary ${err ? '' : ''}">保存</button></div></div>`;
}
const CONFIRM = {
  clear: ['清空全部数据？', '将删除全部资产、历史与订阅，不能撤销。请先导出备份。', '清空'],
  import: ['覆盖本机数据？', '将导入 60 项资产、412 条历史、30 项订阅，并覆盖现有数据。此操作不能撤销。', '确认覆盖'],
  sealed: ['覆盖本机数据？', '加密备份导出于 2026年11月12日 21:03（设备 MacBook-Pro-7F3A），校验通过：60 项资产、412 条历史、30 项订阅。<br>确认后会覆盖现有数据，此操作不能撤销；如果导出时间比当前数据旧，请先取消并核对。', '确认覆盖'],
  replacekey: ['替换本机同步密钥？', '当前指纹 7F3A·91C2·E04B·5D18，新指纹 2B9E·04AF·77C1·A6D3。<br>替换后旧密钥不会保留。请先导出当前恢复码，否则旧密钥导出的加密备份将无法在本机打开。只有确认这个恢复码来自你自己的设备时才继续。', '确认替换'],
  syncclear: ['清除同步配置？', '将删除本机保存的存储地址与凭证，并停止同步。远端数据、本机数据和同步密钥都不受影响。', '清除配置'],
};
function confirmDialog(k) {
  const [t, b, ok] = CONFIRM[k];
  if (form === 'desktop') { const w = 420; return `<div class="scrim"></div><div class="dialog" style="width:${w}px;left:${(W - w) / 2}px;top:${H / 2 - 150}px;padding:24px">
    <div class="row" style="gap:12px;align-items:flex-start"><div class="chip" style="width:40px;height:40px;border-radius:50%;background:var(--neg-soft);color:var(--neg)">${icon(k === 'replacekey' ? 'key' : 'alert', 20)}</div>
    <div class="col grow" style="gap:8px"><span class="t-headline">${t}</span><span class="t-body c2">${b}</span></div></div>
    <div class="row" style="gap:8px;justify-content:flex-end;margin-top:24px"><button class="btn btn-secondary focus-ring">取消</button><button class="btn btn-danger">${ok}</button></div></div>`; }
  const w = Math.min(320, W - 48);
  return `<div class="scrim"></div><div class="dialog glass" style="width:${w}px;left:${(W - w) / 2}px;top:${H / 2 - 180}px;padding:24px 20px 16px;border-radius:28px;background:var(--card)">
    <div class="col" style="gap:8px;text-align:center;align-items:center"><span class="t-headline">${t}</span><span class="t-caption c2" style="text-align:center">${b}</span></div>
    <div class="col" style="gap:8px;margin-top:20px"><button class="btn btn-danger btn-full">${ok}</button><button class="btn btn-secondary btn-full">取消</button></div></div>`;
}
function mobileSheet(key) {
  const [title, kind] = DLG[key]; const fields = formFields(kind);
  const rows = fields.map(f => f.raw && f.inner.includes('seg') ? `<div class="col" style="padding:12px 16px;gap:8px"><span class="t-body">${f.label}</span>${f.inner}</div>`
    : `<div class="list-row" style="gap:16px"><span class="t-body" style="width:${device === 'ipad' ? 120 : 96}px;flex:none">${f.label}</span>
       <div class="grow">${f.raw ? f.inner.replace('class="field', 'class="field readonly') : f.ro ? `<span class="t-body c2 num wrap">${f.inner}</span>` : `<span class="t-body ${f.inner ? 'num' : 'c2'} ${f.error ? 'cneg' : ''}">${f.prefix && f.inner ? f.prefix + ' ' : ''}${f.inner || f.ph}</span>`}</div></div>`).join('');
  const errMsg = err ? (kind === 'sub' ? '请选择下次扣费日' : '请检查名称、金额、日期与选项') : '';
  const help = kind === 'sub' ? '填写名称、金额并选择下次扣费日。到期确认续费后按周期顺延。' : kind === 'correct' ? '修正不会新增快照' : '';
  const top = device === 'ipad' ? (H - 640) / 2 : 62; const sw = device === 'ipad' ? 540 : W; const left = device === 'ipad' ? (W - 540) / 2 : 0;
  const del = key === 'sub-edit' ? `<div class="card"><div class="list-row"><span class="t-body cbrand">暂停</span></div><div class="list-row"><span class="t-body cneg">删除订阅</span></div></div>` : '';
  return `<div class="scrim"></div><div class="dialog" style="left:${left}px;${device === 'ipad' ? 'top:46%;transform:translateY(-50%);height:auto;padding-bottom:16px' : `top:${top}px;height:${H - top}px`};width:${sw}px;background:var(--bg);border-radius:${device === 'ipad' ? 28 : '28px 28px 0 0'}">
    ${device === 'iphone' ? `<div style="width:36px;height:5px;border-radius:3px;background:var(--sep);margin:6px auto 0"></div>` : ''}
    <div class="row" style="padding:${device === 'iphone' ? 8 : 16}px 16px 8px;gap:12px"><div class="icon-btn glass" style="width:44px;height:44px" aria-label="取消">${icon('x', 20)}</div>
      <span class="grow t-headline" style="text-align:center">${title}</span><div class="icon-btn" style="width:44px;height:44px;background:${err ? 'var(--sep)' : 'var(--brand)'};color:${err ? 'var(--text2)' : 'var(--on-brand)'}" aria-label="保存">${icon('check', 20)}</div></div>
    <div class="col" style="gap:8px;padding:16px">${kind === 'asset' ? '' : ''}<div class="card">${rows}</div>
      ${errMsg ? `<div class="row cneg t-caption" style="gap:6px;padding:0 16px">${icon('alert', 16)}${errMsg}</div>` : ''}${help ? `<div class="t-caption c2" style="padding:0 16px">${help}</div>` : ''}<div style="height:16px"></div>${del}</div></div>`;
}

// ---------- 横幅 ----------
function toast(kind) {
  const bottom = form === 'desktop' ? 24 : device === 'ipad' ? 32 : 112;
  const cx = form === 'desktop' ? 236 + (W - 236) / 2 : device === 'ipad' ? (W + 280) / 2 : W / 2;
  const inner = kind === 'undo' ? `${icon('trash', 16, false, 'c2')}<span class="t-body">已删除资产</span><button class="btn btn-text" style="height:32px">${icon('undo', 16)}撤销 · 6秒内</button>`
    : kind === 'error' ? `${icon('alert', 16, false, 'cneg')}<span class="t-body">无法连接同步服务，请检查网络与服务地址后重试</span><div class="icon-btn" style="width:32px;height:32px;color:var(--text2)">${icon('x', 16)}</div>`
    : `${icon('check', 16, false, 'cpos')}<span class="t-body">已顺延 1 期</span><div class="icon-btn" style="width:32px;height:32px;color:var(--text2)">${icon('x', 16)}</div>`;
  if (device === 'iphone') return `<div class="toast glass" style="bottom:${bottom}px;left:16px;right:16px;height:auto;min-height:52px;padding:8px 8px 8px 16px;border-radius:26px;white-space:normal">${inner.replace('<span class="t-body">', '<span class="t-body grow">')}</div>`;
  return `<div class="toast glass" style="bottom:${bottom}px;left:${cx}px;transform:translateX(-50%)">${inner}</div>`;
}

// ---------- 错误页 ----------
function errorPage(kind) {
  if (kind === 'startup') return `<div class="col" style="height:100%;justify-content:center;padding:0 32px 8%">${emptyState('alert', '无法打开数据', '无法读取本机数据：无法读写本机数据库。请检查数据目录权限后重新打开。', '', true)}</div>`;
  return `<div style="padding:8px ${form === 'desktop' ? 32 : 16}px 0"><div class="card row" style="gap:12px;padding:16px;align-items:flex-start;background:var(--warn-soft)">${icon('key', 20, false, 'cwarn')}
    <div class="col grow" style="gap:4px"><span class="t-body-m">同步密钥不可用</span><span class="t-caption c2">请检查密钥文件或导入恢复码。加密备份暂时无法导出或导入；可以在设置里导入恢复码，或处理密钥文件后重启。</span></div>
    <button class="btn btn-secondary" style="height:${form === 'desktop' ? 28 : 36}px">导入恢复码</button></div></div>`;
}
