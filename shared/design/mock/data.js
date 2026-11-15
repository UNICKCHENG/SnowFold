// 设计稿样例数据：normal（日常）、long（第 6 节长数据）、empty（空库）
// 金额一律以「分」存，显示走 money()，与核心 format_money 同格式（负号 U+2212、千分位、两位小数）
window.money = (cents, cur = 'CNY') => {
  const a = Math.abs(cents), whole = Math.floor(a / 100).toString();
  const g = whole.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  return (cents < 0 ? '−' : '') + (cur === 'USD' ? '$' : '¥') + g + '.' + String(a % 100).padStart(2, '0');
};
window.CATS = [
  { code: 'cash', name: '流动资金', icon: 'banknote' },
  { code: 'fixed', name: '固定资产', icon: 'home' },
  { code: 'investment', name: '投资理财', icon: 'trending-up' },
  { code: 'receivable', name: '应收款', icon: 'hand-coins' },
  { code: 'debt', name: '负债', icon: 'credit-card' },
];
const TODAY = '2026-11-14';
const normal = {
  assets: [
    { id: 'a1', cat: 'cash', name: '招商银行 储蓄卡', cents: 8645032, hist: 6, updated: '11月12日' },
    { id: 'a2', cat: 'cash', name: '微信零钱', cents: 132050, hist: 3, updated: '11月10日' },
    { id: 'a3', cat: 'cash', name: '支付宝余额宝', cents: 2380000, hist: 9, updated: '11月01日' },
    { id: 'a4', cat: 'fixed', name: '自住房 · 浦东', cents: 420000000, hist: 2, updated: '6月30日' },
    { id: 'a5', cat: 'fixed', name: '家用车', cents: 13500000, hist: 2, updated: '3月02日' },
    { id: 'a6', cat: 'investment', name: '沪深 300 指数基金', cents: 15628044, hist: 14, updated: '11月13日' },
    { id: 'a7', cat: 'investment', name: '美股账户', cents: 9823015, hist: 8, updated: '11月13日' },
    { id: 'a8', cat: 'receivable', name: '借给小王', cents: 2000000, hist: 1, updated: '9月18日' },
    { id: 'a9', cat: 'debt', name: '房贷 · 工商银行', cents: -215000000, hist: 12, updated: '11月05日' },
    { id: 'a10', cat: 'debt', name: '信用卡 · 招行', cents: -862390, hist: 4, updated: '11月08日' },
  ],
  subs: [
    { id: 's1', name: 'iCloud+ 200GB', cents: 2100, cur: 'CNY', cycle: '每月', next: '2026-11-11', group: 'overdue', overdue: 3 },
    { id: 's2', name: '健身房年卡', cents: 288000, cur: 'CNY', cycle: '每年', next: '2026-11-09', group: 'overdue', overdue: 5 },
    { id: 's3', name: 'Netflix', cents: 1549, cur: 'USD', cycle: '每月', next: '2026-11-20', group: 'this_month', days: 6 },
    { id: 's4', name: '网易云音乐', cents: 1500, cur: 'CNY', cycle: '每月', next: '2026-11-14', group: 'this_month', days: 0 },
    { id: 's5', name: '宽带 · 电信', cents: 12900, cur: 'CNY', cycle: '每月', next: '2026-11-28', group: 'this_month', days: 14 },
    { id: 's6', name: 'ChatGPT Plus', cents: 2000, cur: 'USD', cycle: '每月', next: '2026-12-03', group: 'next_month', days: 19 },
    { id: 's7', name: '车险 · 平安', cents: 468000, cur: 'CNY', cycle: '每年', next: '2027-03-01', group: 'later', days: 107 },
    { id: 's8', name: '腾讯视频', cents: 3000, cur: 'CNY', cycle: '每月', next: '2026-12-08', group: 'paused' },
  ],
  sync: true,
};
// 长数据：资产名 24 个汉字；含 ¥9,999,999,999.99 与 −¥1,234,567.89；人民币与美元混合；每分类 12 条，订阅 30 条
const LONG_NAME = '招商银行朝朝宝活期理财家庭应急备用金账户专用户头'; // 24 字
const longAssets = [];
CATS.forEach((c, ci) => {
  for (let i = 0; i < 12; i++) {
    let cents;
    if (c.code === 'debt') cents = i === 0 ? -123456789 : -(1000000 + i * 734521);
    else if (c.code === 'cash' && i === 0) cents = 999999999999;
    else cents = 1234567 * (i + 1) + ci * 98765;
    longAssets.push({ id: `${c.code}${i}`, cat: c.code, name: i === 0 ? LONG_NAME : `${c.name}${String(i + 1).padStart(2, '0')} ${['工资卡', '定期存单三年期', '账户', '专户（家庭共用）'][i % 4]}`, cents, hist: 3 + i, updated: '11月14日' });
  }
});
const SUB_NAMES = ['iCloud+ 2TB 家庭共享方案（含 Apple TV+ 与音乐）', 'Adobe Creative Cloud 全家桶', 'Netflix 高级版', 'Spotify 家庭', '网易云音乐黑胶 SVIP', 'ChatGPT Plus', 'Notion 团队版', 'GitHub Copilot', '1Password 家庭', '腾讯视频 VIP'];
const groups = ['overdue', 'overdue', 'this_month', 'this_month', 'this_month', 'this_month', 'this_month', 'next_month', 'next_month', 'next_month', 'next_month', 'later', 'later', 'later', 'later', 'later', 'later', 'later', 'later', 'later', 'later', 'later', 'later', 'paused', 'paused', 'paused', 'paused', 'next_month', 'this_month', 'later'];
const longSubs = groups.map((g, i) => ({
  id: 'L' + i, name: SUB_NAMES[i % 10] + (i >= 10 ? ` ${Math.floor(i / 10) + 1}` : ''),
  cents: i % 3 === 1 ? 9999 + i * 100 : (i === 0 ? 99999999 : 2500 + i * 731), cur: i % 3 === 1 ? 'USD' : 'CNY',
  cycle: ['每月', '每年', '每季', '每周'][i % 4], group: g, overdue: g === 'overdue' ? 3 + i : undefined, days: g === 'overdue' || g === 'paused' ? undefined : (i % 9) + (g === 'later' ? 60 : g === 'next_month' ? 20 : 0),
})).map(s => ({ ...s, next: shiftDay(s.group === 'overdue' ? -s.overdue : s.group === 'paused' ? 5 : s.days) }));
// 下次扣费日由分组倒推，保证与核心按日期分组的结果一致
function shiftDay(n) { const d = new Date(TODAY + 'T00:00:00Z'); d.setUTCDate(d.getUTCDate() + n); return d.toISOString().slice(0, 10); }
window.DATASETS = {
  normal,
  long: { assets: longAssets, subs: longSubs, sync: true },
  // 负净资产样例（需求第 6 节：−¥1,234,567.89）：日常数据再加一笔经营贷
  neg: { assets: normal.assets.concat([{ id: 'a11', cat: 'debt', name: '经营贷 · 建设银行', cents: -379702540, hist: 3, updated: '10月20日' }]), subs: normal.subs, sync: true },
  empty: { assets: [], subs: [], sync: false },
};
window.TODAY = TODAY;
// 汇总：只在界面层把核心返回值摆出来；设计稿里用同口径自算
window.summarize = (d) => {
  const byCat = CATS.map(c => {
    const items = d.assets.filter(a => a.cat === c.code);
    return { ...c, items, total: items.reduce((s, a) => s + a.cents, 0) };
  });
  const assets = byCat.filter(c => c.code !== 'debt').reduce((s, c) => s + c.total, 0);
  const debt = byCat.find(c => c.code === 'debt').total;
  const active = d.subs.filter(s => s.group !== 'paused');
  const due = (cur) => active.filter(s => s.cur === cur && (s.group === 'overdue' || s.group === 'this_month')).reduce((t, s) => t + s.cents, 0);
  const perYear = { '每周': 52, '每月': 12, '每季': 4, '每年': 1 };
  const annual = (cur) => active.filter(s => s.cur === cur).reduce((t, s) => t + s.cents * perYear[s.cycle], 0);
  return {
    byCat, assets, debt, net: assets + debt, ratio: assets ? (Math.abs(debt) / assets * 100) : 0,
    due: { CNY: due('CNY'), USD: due('USD') }, annual: { CNY: annual('CNY'), USD: annual('USD') },
    monthly: { CNY: Math.round(annual('CNY') / 12), USD: Math.round(annual('USD') / 12) },
    active: active.length, paused: d.subs.length - active.length, overdue: d.subs.filter(s => s.group === 'overdue').length,
    upcoming: d.subs.filter(s => s.group === 'overdue' || (s.days !== undefined && s.days <= 30)).sort((a, b) => (a.group === 'overdue' ? -100 + (a.overdue || 0) * -1 : a.days) - (b.group === 'overdue' ? -100 + (b.overdue || 0) * -1 : b.days)).slice(0, 5),
    upcomingAll: d.subs.filter(s => s.group === 'overdue' || (s.days !== undefined && s.days <= 30)).sort((a, b) => (a.group === 'overdue' ? -100 + (a.overdue || 0) * -1 : a.days) - (b.group === 'overdue' ? -100 + (b.overdue || 0) * -1 : b.days)),
  };
};
