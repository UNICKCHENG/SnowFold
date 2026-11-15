// ---------- 框架：Mac 窗口 / iPhone / iPad ----------
const NAV = [['overview', '总览', 'pie-chart'], ['assets', '资产', 'wallet'], ['subscriptions', '订阅', 'calendar-repeat']];
const pageOf = { swipe: 'subscriptions', 'asset-detail': 'assets', overview: 'overview', assets: 'assets', subscriptions: 'subscriptions', settings: 'settings', dialog: Q.get('page') || 'assets', confirm: 'settings', toast: Q.get('page') || 'assets', error: 'overview' };
const TITLE = { overview: '总览', assets: '资产与负债', subscriptions: '订阅', settings: '设置' };
function bodyFor(page) {
  if (screen === 'error' && state === 'startup') return errorPage('startup');
  const keyBanner = screen === 'error' && state === 'key' ? errorPage('key') : '';
  if (page === 'overview') return keyBanner + overviewBody();
  if (page === 'assets') return assetsBody();
  if (page === 'subscriptions') return subsBody();
  if (page === 'settings') return `<div class="col" style="gap:24px;padding:8px ${mob ? 16 : 24}px 120px;${device === 'ipad' ? 'max-width:720px;margin:0 auto' : ''}">${settingsSections().join('')}</div>`;
}
function overlayFor() {
  if (screen === 'dialog') return form === 'desktop' ? desktopDialog(state.replace('-error', '')) : mobileSheet(state.replace('-error', ''));
  if (screen === 'confirm') return confirmDialog(state);
  if (screen === 'toast') return toast(state);
  if (screen === 'swipe') return '';
  return '';
}
const page = pageOf[screen] || screen;
const dark = theme === 'dark';
// 窗口背后的「桌面」：只在浮层毛玻璃下透出一点颜色
const WALL = dark ? 'radial-gradient(120% 90% at 10% 0%, #1E2B33 0%, #121417 60%)' : 'radial-gradient(120% 90% at 10% 0%, #DCE7EC 0%, #EEF0F3 60%)';

function macWindow() {
  if (screen === 'settings-window') return macSettingsWindow();
  const sidebarW = 220, inset = 8, contentX = sidebarW + inset * 2;
  const navItem = (key, label, ic, on) => `<div class="row" style="gap:10px;height:32px;padding:0 10px;border-radius:var(--r-control);${on ? 'background:var(--brand-soft);color:var(--brand)' : 'color:var(--text)'}">
     ${icon(ic, 20, on)}<span class="${on ? 't-body-m' : 't-body'}">${label}</span></div>`;
  const startup = screen === 'error' && state === 'startup';
  const sidebar = `<div class="glass" style="position:absolute;left:${inset}px;top:${inset}px;bottom:${inset}px;width:${sidebarW}px;border-radius:18px;padding:52px 10px 10px;display:flex;flex-direction:column;gap:2px;${startup ? 'opacity:.5' : ''}">
     <div class="row" style="position:absolute;left:18px;top:18px;gap:8px">${['#FF5F57', '#FEBC2E', '#28C840'].map(c => `<span style="width:12px;height:12px;border-radius:50%;background:${c}"></span>`).join('')}</div>
     ${NAV.map(([k, l, i]) => navItem(k, l, i, !startup && page === k)).join('')}<span class="grow"></span>${navItem('settings', '设置', 'settings', false)}</div>`;
  const tb = `<div class="row" style="position:absolute;left:${contentX}px;right:0;top:0;height:52px;padding:0 16px 0 24px;gap:8px">
     <span class="t-headline grow">${startup ? 'SnowFold' : TITLE[page]}</span>${startup ? '' : `${syncBtn(32)}<div class="icon-btn glass" style="width:32px;height:32px" title="${page === 'subscriptions' ? '新建订阅' : '添加资产'}">${icon('plus', 20)}</div>`}</div>`;
  const content = `<div style="position:absolute;left:${contentX}px;right:0;top:52px;bottom:0;overflow:hidden">${bodyFor(page)}</div>`;
  return `<div style="position:absolute;inset:0;background:var(--bg)">${content}${tb}${sidebar}${overlayFor()}</div>`;
}
function macSettingsWindow() {
  const tab = state || 'general';
  const tabs = SET_TABS.map(([k, l, i]) => `<div class="col" style="align-items:center;gap:2px;width:84px;padding:6px 0;border-radius:var(--r-control);${k === tab ? 'background:var(--brand-soft);color:var(--brand)' : 'color:var(--text2)'}">${icon(i, 24, k === tab)}<span class="t-caption${k === tab ? '-m' : ''}">${l}</span></div>`).join('');
  return `<div style="position:absolute;inset:0;background:var(--bg)">
    <div class="glass" style="position:absolute;left:0;right:0;top:0;height:88px;border-radius:0;box-shadow:inset 0 -1px 0 var(--sep)">
      <div class="row" style="position:absolute;left:18px;top:18px;gap:8px">${['#FF5F57', '#FEBC2E', '#28C840'].map(c => `<span style="width:12px;height:12px;border-radius:50%;background:${c}"></span>`).join('')}</div>
      <div class="t-headline" style="position:absolute;left:0;right:0;top:14px;text-align:center">${SET_TABS.find(t => t[0] === tab)[1]}</div>
      <div class="row" style="position:absolute;left:0;right:0;top:38px;justify-content:center;gap:8px">${tabs}</div></div>
    <div class="col" style="position:absolute;left:0;right:0;top:88px;bottom:0;padding:24px 32px;gap:24px;overflow:hidden">${settingsSections(tab).join('')}</div>${screen === 'settings-window' && Q.get('confirm') ? confirmDialog(Q.get('confirm')) : ''}</div>`;
}
function statusBar() {
  return `<div class="row" style="position:absolute;left:0;right:0;top:0;height:${device === 'ipad' ? 24 : 54}px;padding:0 ${device === 'ipad' ? 20 : 36}px;font:600 ${device === 'ipad' ? 13 : 17}px var(--font);z-index:5">
    <span class="grow num">9:41</span><span class="row" style="gap:6px">${device === 'ipad' ? '<span class="num">11月14日 周六</span>' : ''}<svg width="18" height="12" viewBox="0 0 18 12"><rect x="0" y="8" width="3" height="4" rx="1" fill="currentColor"/><rect x="5" y="5" width="3" height="7" rx="1" fill="currentColor"/><rect x="10" y="2" width="3" height="10" rx="1" fill="currentColor"/><rect x="15" y="0" width="3" height="12" rx="1" fill="currentColor"/></svg>
    <svg width="27" height="13" viewBox="0 0 27 13"><rect x=".5" y=".5" width="23" height="12" rx="3.5" fill="none" stroke="currentColor" opacity=".4"/><rect x="2" y="2" width="20" height="9" rx="2" fill="currentColor"/><rect x="25" y="4" width="1.5" height="5" rx=".75" fill="currentColor" opacity=".4"/></svg></span></div>`;
}
function iphone() {
  const TABS = [...NAV, ['settings', '设置', 'settings']];
  const detail = screen === 'asset-detail';
  const navTitle = detail ? '' : TITLE[page];
  const right = page === 'overview' ? syncBtn(44) : page === 'settings' ? '' : `<div class="icon-btn glass" style="width:44px;height:44px">${icon('plus', 20)}</div>`;
  const nav = `<div class="row" style="position:absolute;left:0;right:0;top:54px;height:52px;padding:0 16px;gap:8px;z-index:4">
     ${detail ? `<div class="icon-btn glass" style="width:44px;height:44px">${icon('chevron-left', 20)}</div>` : '<span style="width:44px"></span>'}
     <span class="grow t-headline" style="text-align:center">${navTitle}</span>${detail ? '<span style="width:44px"></span>' : right || '<span style="width:44px"></span>'}</div>`;
  const tabW = W - 2 * 24;
  const tabbar = `<div class="glass row" style="position:absolute;left:24px;bottom:${H > 700 ? 28 : 12}px;width:${tabW}px;height:64px;border-radius:32px;padding:4px;z-index:6">
     ${TABS.map(([k, l, i]) => { const on = (detail ? 'assets' : page) === k; return `<div class="col" style="flex:1;align-items:center;justify-content:center;gap:2px;height:56px;border-radius:28px;${on ? 'background:var(--brand-soft);color:var(--brand)' : 'color:var(--text)'}">${icon(i, 24, on)}<span class="t-caption${on ? '-m' : ''}">${l}</span></div>`; }).join('')}</div>`;
  let body = detail ? `<div style="padding:8px 16px 120px">${assetDetail(D.assets[0])}</div>` : bodyFor(page);
  if (screen === 'swipe') body = swipeDemo();
  const home = `<div style="position:absolute;left:50%;bottom:8px;width:134px;height:5px;border-radius:3px;background:var(--text);transform:translateX(-50%);z-index:7"></div>`;
  const sheetOpen = screen === 'dialog';
  return `<div style="position:absolute;inset:0;background:var(--bg)">${statusBar()}${nav}<div style="position:absolute;left:0;right:0;top:106px;bottom:0;overflow:hidden">${body}</div>
    ${sheetOpen || (screen === 'error' && state === 'startup') ? '' : tabbar}<div style="position:absolute;inset:0;z-index:10;pointer-events:none">${overlayFor()}</div>${home}</div>`;
}
function ipad() {
  const sidebarW = 260;
  const navItem = (k, l, i, on) => `<div class="row" style="gap:12px;height:44px;padding:0 12px;border-radius:var(--r-control);${on ? 'background:var(--brand-soft);color:var(--brand)' : 'color:var(--text)'}">${icon(i, 24, on)}<span class="${on ? 't-body-m' : 't-body'}">${l}</span></div>`;
  const sidebar = `<div class="glass" style="position:absolute;left:8px;top:8px;bottom:8px;width:${sidebarW}px;border-radius:24px;padding:56px 12px 12px;display:flex;flex-direction:column;gap:4px;z-index:3">
     <div class="t-title" style="position:absolute;left:24px;top:16px;font:var(--t-headline)">SnowFold</div>
     ${NAV.map(([k, l, i]) => navItem(k, l, i, page === k)).join('')}<span class="grow"></span>${navItem('settings', '设置', 'settings', page === 'settings')}</div>`;
  const cx = sidebarW + 16;
  const tb = `<div class="row" style="position:absolute;left:${cx}px;right:0;top:24px;height:56px;padding:0 20px 0 24px;gap:12px;z-index:4">
     <span class="t-headline grow">${TITLE[page]}</span>${page === 'settings' ? '' : `${syncBtn(44)}<div class="icon-btn glass" style="width:44px;height:44px">${icon('plus', 20)}</div>`}</div>`;
  return `<div style="position:absolute;inset:0;background:var(--bg)">${statusBar()}<div style="position:absolute;left:${cx}px;right:0;top:80px;bottom:0;overflow:hidden">${bodyFor(page)}</div>${tb}${sidebar}<div style="position:absolute;inset:0;z-index:10">${overlayFor()}</div>
    <div style="position:absolute;left:50%;bottom:8px;width:200px;height:5px;border-radius:3px;background:var(--text);transform:translateX(-50%);z-index:7"></div></div>`;
}
function swipeDemo() {
  // 订阅行左滑：露出「已续费」「删除」
  const s = D.subs[0];
  return `<div class="col" style="gap:24px;padding:8px 16px 120px"><div><div class="sec-head"><span class="t-caption-m cneg">待确认</span><span class="t-caption c2">2</span></div>
    <div class="card"><div style="position:relative;overflow:hidden"><div class="row" style="position:absolute;right:0;top:0;bottom:0;width:176px">
      <div class="col" style="flex:1;height:100%;align-items:center;justify-content:center;gap:4px;background:var(--brand);color:var(--on-brand)">${icon('check', 20)}<span class="t-caption-m">已续费</span></div>
      <div class="col" style="flex:1;height:100%;align-items:center;justify-content:center;gap:4px;background:var(--neg);color:${dark ? '#0E0E10' : '#fff'}">${icon('trash', 20)}<span class="t-caption-m">删除</span></div></div>
      <div style="transform:translateX(-176px);background:var(--card);position:relative">${subRow(s)}</div></div>${subRow(D.subs[1])}</div></div>
    <div><div class="sec-head"><span class="t-caption-m c2">本月</span><span class="t-caption c2">3</span></div><div class="card">${D.subs.filter(x => x.group === 'this_month').map(subRow).join('')}</div></div></div>`;
}

// ---------- 渲染 ----------
const root = document.getElementById('root');
root.style.width = W + 'px'; root.style.height = H + 'px';
root.style.background = WALL;
root.innerHTML = device === 'mac' ? macWindow() : device === 'ipad' ? ipad() : iphone();
// 金额自适应：放不下按比例缩小，最小 60%
document.fonts.ready.then(() => {
  root.querySelectorAll('[data-fit]').forEach(el => {
    const avail = el.parentElement.clientWidth; const need = el.scrollWidth;
    if (need > avail) { const k = Math.max(0.6, avail / need); el.style.transform = `scale(${k})`; el.style.transformOrigin = 'left center'; el.style.height = (el.offsetHeight * k) + 'px'; }
  });
  document.body.dataset.ready = '1';
});
