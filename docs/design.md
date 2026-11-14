# SnowFold 设计规范（三端共用，以 GPUI 桌面端为基准）

状态：**冻结**（2026-11-14 修订版）。设计基准＝现有 GPUI 桌面端（apps/desktop/src/app/，已定版本）。领导口径：桌面端的样式、图标、卡片布局早已定下，iPhone/Mac（SwiftUI）与安卓（Compose）全部照它对齐；本文所有数值逐条取自桌面端源码并标注 `文件:行号`，手机端照抄即可。与旧版（以 SwiftUI 为基准）冲突处一律以本文为准。**移动端布局见 mobile-layout.md**（多列改单列与各页区块在 375pt/360dp 的排布）；本文只写视觉语言与桌面端实测数值。基准截图目录 `.fullstack/layout/shots/desktop-baseline/`——本次**未完成（宿主无屏幕录制权限）**，请以文中 `文件:行号` 为准。

总原则：安静的中性底色，语义色只用于金额、分类与警示；层次靠留白、分组与一层卡片底建立，卡片有 1px 细线描边、无阴影（仅浮层用阴影）；中文为主语言；金额一律经核心 `format_money`，各端不自算。文案以 docs/copy.md 与事实板「文案修正」为准。桌面端窗口默认 1440×900、最小 960×720（main.rs:15-19），内容列最大宽 1040、水平内边距 44、上 40 下 100（render.rs:75-83）。

## 1. 颜色

全部出自 apps/desktop/src/app/components.rs `Palette::new`（:93-137），两套均为单独调的实值。`@n%` = 该色不透明度（hex alpha 换算：0x0e≈6%、0x14≈8%、0x15≈8%、0x17≈9%、0x1a≈10%、0x24≈14%、0x26≈15%、0x29≈16%、0x47≈28%、0x52≈32%、0x8c≈55%、0x99≈60%、0x80≈50%）。

### 1.1 层级与语义色

| 令牌 | 浅色 | 深色 | 用途（桌面端实际） |
| --- | --- | --- | --- |
| canvas | #F1F1EE | #0B0B0A | 页面底（components.rs:93） |
| card | #FFFFFF | #171716 | 卡片、弹层底（:94） |
| subtle | #F7F7F4 | #1E1E1C | 次级底：悬浮面板、行内触发器、弹层底栏、历史展开区（:95） |
| hover | #FBFBF9 | #1C1C1A | 列表行悬浮（:96） |
| text（ink） | #191917 | #F3F3EE | 主文字、主按钮底（:97） |
| muted（ink-2） | #6F6F68 | #9B9B93 | 次要文字、图标默认色（:98） |
| ink3 | #9D9D95 | #6C6C65 | 辅助/标签文字（:99） |
| accent | #2B59E0 | #8098FF | 主操作文字、选中描边、≤7 天徽标（:100） |
| positive | #1C7A50 | #58C795 | 预留（桌面未使用，保留令牌）（:101） |
| negative | #AE4A3C | #E58A7A | 负债金额、逾期徽标、危险按钮、错误文本（:102） |
| line（hairline） | #191917@9% | #FFFFFF@8% | 行分隔线、卡片描边（:103-107） |
| hairline_strong | #191917@16% | #FFFFFF@16% | 卡片悬浮描边、输入框描边（:108-112） |
| accent_soft | #2B59E0@8% | #8098FF@15% | ≤7 天徽标底（:113-117） |
| pos_soft | #1C7A50@8% | #58C795@15% | fixed 分类 chip 底（:118-120） |
| neg_soft | #AE4A3C@8% | #E58A7A@14% | 逾期/今天徽标底、危险按钮底、待确认头像底（:121-123） |
| mark_b | #4A4A3F | #C9C9BD | 顶栏品牌图标（:128） |
| tabs_bg | #191917@6% | #FFFFFF@6% | 分段导航容器底（:129-133） |
| active_tab | #FFFFFF | #2B2B28 | 选中导航段底（:134） |
| 遮罩（弹层） | #1C1C19@28% | #000000@50% | 设置/新建订阅弹层（render.rs:253-257、467-471） |
| 遮罩（确认） | #1C1C19@32% | #000000@60% | 确认弹层（render.rs:690-694） |

注意：**桌面端没有 USD 专用标识色**。USD 金额以 text 色与 CNY 并排文本展示（`¥xx / $xx`，subscriptions.rs:83-88）；旧规范的 usd 令牌（#2A7F8F）作废，两端不要新增换色。

### 1.2 分类色（五个固定分类，顺序 cash→debt）

`category_tint`（components.rs:141-153）：chip 底 = glyph 同色低透明度，glyph = 前景。

| 分类 | chip 底 浅 | chip 底 深 | glyph 浅 | glyph 深 |
| --- | --- | --- | --- | --- |
| cash 流动资金 | #8C6D3F@10% | #8C6D3F@10% | #8C6D3F | #8C6D3F |
| fixed 固定资产 | pos_soft（#1C7A50@8% / #58C795@15%） | 同左 | #1C7A50 | #58C795 |
| investment 投资理财 | #6D6D68@10% | #6D6D68@10% | muted | muted |
| receivable 应收款 | #AE4A3C@9% | #E58A7A@14% | #AE4A3C | #E58A7A |
| debt 负债 | #2B59E0@9% | #8098FF@14% | accent | accent |

规则：负债金额一律 negative（overview.rs:450、asset_page.rs:279）；构成卡占比条填充：负债 #E58A7A@55%、其余 #F3F3EE@32%（overview.rs:474-478）；分类图表用 glyph 色不换色。

## 2. 字体

GPUI 全局基准 14px（render.rs:101），未设置任何字体族（无 `font_family` 调用）——**金额数字不强制等宽**，移动端按平台默认文字渲染即可。字重只用到 Regular / Medium / SemiBold。全部字号为 px。

| 级别 | 字号 | 字重 | 颜色 | 出处与用途 |
| --- | --- | --- | --- | --- |
| hero 整数 | 56 | SemiBold | text / negative(负数) | 净资产整数段（components.rs:55-57） |
| hero 币种符 | 26 | Medium | muted | ¥/$ 符号（:59-64） |
| hero 小数 | 29 | Medium | ink3 | 小数段（:67-72） |
| hero 标签 | 12 | Medium | ink3 | 「净资产」标签（overview.rs:103-107） |
| 页头标题 | 25 | SemiBold | text | 每页页头（components.rs:170-172） |
| 页头副标题 | 12.5 | Regular | ink3 | 页头下副标题（:175-178） |
| 分类卡金额 | 25 | SemiBold | text / negative | 构成卡主值（overview.rs:448-450） |
| 汇总值 | 22 | SemiBold | text/negative | 资产页总资产/总负债/净资产（components.rs:199-204） |
| 统计值 | 19 | SemiBold | text | 订阅统计列主值（components.rs:232-236） |
| 统计次值 | 14.8 | Medium | ink3 | USD 次值、下一扣费服务名（:238-243） |
| 行标题 | 13.5 | Medium/Regular | text | 资产/订阅/即将扣费名称（asset_page.rs:58、overview.rs:274） |
| 行数值 | 13.5 | Medium/Regular | text | 行内金额（asset_page.rs:105；overview.rs:296） |
| 正文/按钮 | 13–14 | Medium | text/muted | 默认文本 14（render.rs:101）、行内按钮 13（components.rs:308） |
| 弹层标题 | 15 | SemiBold | text | sheet/确认弹层标题（render.rs:291、723-724） |
| 弹层正文 | 12.5 | Regular | muted，行高 19 | 确认弹层描述（render.rs:727-731） |
| 弹层行标题 | 13 | Regular | text | 设置行标签（components.rs:448） |
| 弹层行说明 | 11.5 | Regular | ink3，最大宽 260 | 设置行副文（:450-454） |
| 分区头 | 12 | SemiBold | ink3 | 「资产构成」「即将扣费」（overview.rs:329-333） |
| 分组头 | 11.5 | SemiBold | ink3 | 「待确认」等分组标题（components.rs:256-261） |
| 分区头计数 | 11 | Regular | ink3 | 分组头右侧数量（:263-267） |
| 标签/徽标 | 11–11.5 | Medium/SemiBold | ink3 | 统计标签 11.5（:198、224）、徽标 11（overview.rs:285） |
| 链接 | 12.5 | Regular | muted（悬浮 text） | 「全部明细」等（overview.rs:344） |
| 元信息 | 12.5 | Regular | muted | hero 下总资产/负债率行（overview.rs:116） |
| 分区合计 | 15 | SemiBold | text/negative | 资产分区头右侧（asset_page.rs:277-279） |
| 分区名 | 14 | SemiBold | text | 资产分区头分类名（:264-266） |
| 导航段 | 13 | Medium | text/muted | 顶部 tab（render.rs:43-45） |
| 品牌 | 13 | SemiBold | text | 顶栏「SnowFold」（render.rs:147-149） |
| 表单输入 | 13.5 | Regular | text | 输入框/下拉（render.rs:553；components.rs:520） |
| 表单标签 | 11.5 | Regular | ink3 | 字段标签（components.rs:494） |
| 脚注 | 11.5 | Regular | ink3，行高 18 | 弹层内说明（render.rs:614-618） |
| 分段控件 | 12 | Regular/Medium | text/muted | 主题分段（components.rs:400-406） |
| 历史时间 | 12 | Regular | muted | 变动记录时间列（asset_page.rs:179） |
| 历史增量 | 12 | Regular | ink3 | 变动记录增量列（:184） |
| 历史金额 | 13 | Regular | text | 变动记录金额（:194） |

金额格式 `¥1,234.56`、`$1,234.56`（千分位逗号、两位小数、负号 `-` 前缀）由核心 `format_money` 输出。日期格式：页头副标题 `2026年11月14日 星期六`（components.rs:30-34、overview.rs:28-32）、行内短日期 `11月17日`（跨年 `2027年3月1日`，components.rs:36-43）、订阅行日期原样 ISO `2026-11-20`（subscriptions.rs:372）。

## 3. 间距、圆角、边框、阴影

- 卡片：圆角 18，bg card，1px line 描边（list_card，components.rs:271-280；panel 无描边，:154-162）。卡片悬浮描边换 hairline_strong（overview.rs:388）。
- panel（通知/浮层基座）：圆角 18、内边距 20（`p_5`）、纵向 gap 12（`gap_3`）、bg card（components.rs:154-162）。
- 构成卡：min 高 124，水平内边距 18、上 17 下 16（overview.rs:378-381）。
- 即将扣费/订阅行卡：圆角 18、1px line 描边、行间 1px line 分隔（overview.rs:214-219、subscriptions.rs:173）。
- 顶部导航：高 56；分段容器 p=3、gap=2、圆角 10、bg tabs_bg；段高 29、最小宽 64、水平内边距 15、圆角 7（render.rs:21-27、39-46）。品牌图标 22px + 字 13px 在左侧 88/17（:130-149）；设置按钮 30×30、圆角 8、图标 16px（:52-74）。
- 阴影：只有浮层用（撤销横幅、通知条、弹层）`shadow_lg`（GPUI 预设大阴影）；卡片一律无阴影。
- 页面分区：构成卡栅格 gap 14（overview.rs:185-196）；「即将扣费」区上边距 46（:306）；订阅分组间 30（subscriptions.rs:170）；资产分区间 34（asset_page.rs:387）；汇总条上分隔 34+34（asset_page.rs:398-400）。
- 行：即将扣费行高 52、水平 18、gap 14（overview.rs:251-252）；资产行高 50（asset_page.rs:28）；订阅行 min 高 64、水平 16（subscriptions.rs:180-181）；历史行高 38（asset_page.rs:175）。
- chip/头像：分类 chip 27×27 圆角 8，图标 15（overview.rs:405-413；asset_page.rs:249-259）；订阅头像 34×34 圆角 10，1px line 描边（subscriptions.rs:213-227）。
- 徽标：高 21、水平 8、圆角 99（胶囊）（overview.rs:281-289）。
- 待确认行首红条：宽 2.5、高 40、圆角 2、negative（subscriptions.rs:192-199）。
- 占比条：高 3、圆角 99、底 line（overview.rs:464-479）。
- 按钮：统一高 32、水平内边距 13、圆角 9、字 13 Medium（components.rs:300-313）；图标小钮 24×24 圆角 7（overview.rs:429-433）；关闭钮 26×26 圆角 7（render.rs:299-310）；分段段高 26、水平 12、圆角 7（components.rs:394-399）；输入框/下拉高 36、圆角 9、水平 10（:509-519；render.rs:549-553）。
- 弹层：设置与新建订阅宽 472、圆角 22、1px line 描边、shadow_lg（render.rs:266-276、480-490）；确认弹层宽 400、圆角 18（:699-708）。弹层头部：水平 20、上 18 下 14（:282-287）；底栏：1px line 上分隔、bg subtle、水平 20、上 14 下 18、按钮 gap 8（components.rs:533-547）。弹层分组：上下 14、组间 1px line 分隔、组标题 11.5 下距 10（:412-428）。
- 触控：桌面端无最小触控约束（部分命中区仅 24px）；移动端按平台惯例保证 ≥44pt/dp 最小命中区，视觉尺寸保持本规范数值。

## 4. 图标

来源与规则：桌面端图标＝`shared/icons/ui/` 的 Lucide SVG（24 viewBox、线宽 2、currentColor，apps/desktop/src/assets.rs:9-46 嵌入清单），另有少量来自 gpui-component 自带图标集（同为 Lucide 取材）。安卓直接使用 shared/icons/ui/ 下同名 SVG；**缺 `chevron-down.svg`，本次已从 Lucide 原样补入**。SF Symbols 仅苹果端使用，按寓意最近映射。桌面端**无图标容器的着色规则之外的特殊处理**：一律单色（currentColor），色随文字色。

### 4.1 映射表（用途 → Lucide（shared/icons/ui/）→ SF Symbol）

| 用途 | Lucide | SF Symbol | 出处 |
| --- | --- | --- | --- |
| cash 流动资金 | wallet | wallet.pass.fill | components.rs:636 |
| fixed 固定资产 | house | house.fill | :638 |
| investment 投资理财 | trending-up | chart.line.uptrend.xyaxis | :640 |
| receivable 应收款 | banknote | banknote.fill | :642 |
| debt 负债 | credit-card | creditcard.fill | :644 |
| 顶栏设置按钮 | sliders | slider.horizontal.3 | render.rs:70 |
| 品牌标记 | mark（非 Lucide，四端共用图形） | —（用 mark 图形） | render.rs:141 |
| 分区右链接箭头 | arrow-right | arrow.right | overview.rs:353 |
| 变动记录 chip | clock | clock（或 clock.arrow.circlepath） | asset_page.rs:78 |
| 添加（构成卡小圆钮/分区「添加」） | plus | plus | overview.rs:442；asset_page.rs:297 |
| 行删除/弹层关闭 | x | xmark | asset_page.rs:123；render.rs:312 |
| 已续费 | check | checkmark | components.rs:573（subscriptions.rs:428 使用） |
| 下拉展开 | chevron-down（本次补入） | chevron.down | components.rs:527 |
| 历史回退（按钮前缀「◷」→ Undo2，当前未挂接页面） | undo-2 | arrow.uturn.backward | components.rs:571 |
| 设置（gpui 组件图标，按钮映射备用） | settings | gearshape | components.rs:575 |

未在桌面端出现、shared/icons/ui 已入库但两端暂不需要照搬的：chart-pie、calendar、calendar-clock、chevron-left、circle-check、circle-help、circle-pause、circle-play、hard-drive、info、pause、pencil、play、receipt、refresh-cw、repeat、trash、triangle-alert。两端如需（如暂停/删除动作用的图标），以 shared/icons/ui/ 现有文件为准，不新增语义。

### 4.2 尺寸与容器

| 场景 | glyph | 容器 | 出处 |
| --- | --- | --- | --- |
| 分类 chip（构成卡/分区头） | 15 | 27×27，圆角 8，底=分类 chip 底 | overview.rs:405-413 |
| 历史 chip 内时钟 | 12 | 行内 chip：高 22、水平 7，无底（展开时 subtle） | asset_page.rs:67-91 |
| 顶栏设置 | 16 | 30×30，圆角 8 | render.rs:52-74 |
| 品牌 | 22 | 无容器，色 mark_b | render.rs:140-143 |
| 链接箭头 | 12 | 无容器，随链接色 | overview.rs:353 |
| 添加（构成卡） | 14 | 24×24，圆角 7，ink3，悬浮 subtle+text | overview.rs:429-434 |
| 添加（分区头） | 12（size_3） | 行内，随「添加」文字钮 | asset_page.rs:297 |
| 行删除/关闭 | 14（size_3p5） | 24×24（行）/26×26 圆角 7（弹层），ink3，悬浮 neg | asset_page.rs:113-127；render.rs:299-315 |

## 5. 控件

### 5.1 顶部导航（桌面端形态；移动端见 §8）
品牌（mark 22 + 「SnowFold」13 SemiBold）靠左 88px；居中分段导航「总览/资产/订阅」：容器 p3/gap2/圆角 10/bg tabs_bg，段高 29、最小宽 64、px15、圆角 7、字 13 Medium，选中＝bg active_tab + text 色，未选 muted，悬浮 text（render.rs:21-51）；右侧设置按钮 30×30 圆角 8、sliders 16px muted、悬浮 subtle（:52-74）。快捷键：Cmd/Ctrl+1/2/3 切页、Cmd+, 设置、Cmd+N 新建、Esc 关闭（actions.rs:7-20）。

### 5.2 按钮（components.rs:284-379、560-607）
统一基座：高 32、水平内边距 13、圆角 9、字 13 Medium、1px 描边、键盘聚焦描边变 accent、悬浮换底色。

| 类型 | 底 | 字色 | 描边 | 悬浮 | 用途 |
| --- | --- | --- | --- | --- | --- |
| 主按钮 action_primary | text（墨色实底） | canvas | 无 | 底变 accent、字 canvas | 「保存」「完成」 |
| 次级 action_soft | subtle | text | line | hover | 「导出」「导入」「导出密文」等 |
| 危险 action_danger | neg_soft | neg | 无 | 字变 neg | 「清空」「确认覆盖」「确认替换」 |
| 幽灵 action | 透明 | muted | 无 | 底 subtle、字 text | 「取消」「撤销 · 6秒内」「暂停」「×」 |
| 强调文字 button | 透明/指定 card | accent | 无（聚焦描边 accent） | 底 canvas | 「＋ 新建订阅」（bg=card，subscriptions.rs:55-56）、「撤销 · 6秒内」（render.rs:200） |
| 文字钮 text_control | 无底无边框 | 随内容 | 仅聚焦描边 accent | — | 行内名称/金额/历史 chip（components.rs:549-559） |

带图标的按钮：前缀符号自动配 Lucide 图标——「＋」→plus、「×」→x、「✓」→check、「◷」→undo-2、「设置」→settings（components.rs:564-578）。

### 5.3 分段控件
- 顶部页面导航：见 §5.1。
- 主题分段（自动/浅色/深色）：容器 gap2、p3、圆角 10、1px line 描边、bg subtle（render.rs:229-236）；段：高 26、水平 12、圆角 7、字 12，选中＝bg card + text Medium，未选 muted，悬浮 text（components.rs:381-410）。

### 5.4 输入与下拉
- 文本输入（gpui Input）：高 36、圆角 9、字 13.5（render.rs:548-553）；行内编辑输入宽 180（订阅名）/130（订阅金额）/240（资产名）/160（金额）（editing.rs:159-172）。
- 下拉触发器 field_select：高 36、全宽、水平 10、圆角 9、1px hairline_strong 描边、bg card、字 13.5 + 右侧 chevron-down 12 ink3；悬浮 bg subtle、聚焦描边 accent（components.rs:497-531）。桌面端币种/周期用点击循环切换或弹层选项（subscriptions.rs:269-363），移动端可用平台选择器。
- 日期：gpui DatePicker，高 36、圆角 9（render.rs:606-612）；行内日期弹层：panel 宽 280 + 「应用日期」按钮（subscriptions.rs:381-413）。

### 5.5 行
- 资产行（asset_page.rs:24-129）：高 50、px16、gap12；名称 13.5（flex-1，截断）→ 历史 chip（高 22、px7、11px ink3 + clock 12 + 条数，展开时 bg subtle）→ 弹性空隙 → 金额 13.5 → 删除 24×24（x 14 ink3，悬浮 neg）。展开历史面板：bg subtle、px16、pt12 pb16、上 1px line；标题「变动记录 · 修正不会新增快照」11.5 ink3；每行高 38：时间+「当前」宽 220 字 12 muted、增量 12 ink3（「+¥xx」/「初始」）、金额 13 右对齐、「修正」钮 12。
- 订阅行（subscriptions.rs:174-491）：min 高 64、px16、gap8；待确认组行首红条 2.5×40；头像 34×34 圆角 10（待确认：bg neg_soft、字 neg；其余：bg subtle、字 muted、1px line 描边；首字 13 SemiBold）→ 名称钮（宽 180，14px）→ 金额钮（宽 130，右对齐）→ 币种钮 → 周期钮（muted）→ 日期钮（muted，ISO）→ 弹性空隙 → 「逾期N天」11 neg（仅待确认）→「✓ 已续费」→「暂停/恢复」→「×」。已暂停组整行透明度 62%（:190）。
- 即将扣费行（overview.rs:242-299）：高 52、px18、gap14；日期宽 74 字 12.5 muted → 名称 13.5 Medium flex-1 截断 → 徽标胶囊（高 21、px8、11 Medium：逾期/今天=neg_soft+neg；≤7 天=accent_soft+accent；>7 天=subtle+ink3）→ 金额宽 82 右对齐 13.5 Medium。
- 设置行 sheet_row（components.rs:430-458）：左标签 13 + 说明 11.5 ink3（最大宽 260）垂直 gap2，右侧控件，行 py7、左右 gap16。

### 5.6 弹层
- 遮罩：设置/新建订阅 #1C1C19@28%（深 #000@50%），确认弹层 #1C1C19@32%（深 #000@60%），点击遮罩关闭（render.rs:253-257、467-471、690-694）。
- 设置弹层（render.rs:249-455）：宽 472、圆角 22、bg card、1px line 描边、shadow_lg；头部「设置」15 SemiBold + 关闭钮 26×26 圆角 7（x 14 ink3、悬浮 subtle）；内容区水平 20、最大高＝视口 62%（clamp 360–780）可滚动（:14-16、328-338）；分组结构见 §6.5；底部动作条「完成」＝主按钮。
- 新建订阅弹层（render.rs:458-637）：同宽圆角；表单纵向 gap14、px20：名称 → 金额+币种（双列 gap12）→ 周期+下次扣费日（双列 gap12）→ 脚注 11.5/行高 18「名称与金额必填。保存后按周期计算下次扣费；到期时进入「待确认」，点一下即可顺延。」；底部「取消」（幽灵）+「保存」（主按钮）。
- 确认弹层（render.rs:639-759）：宽 400、圆角 18；标题 15 SemiBold、正文 12.5 muted 行高 19；底部「取消」（幽灵）+「确认覆盖/确认替换」（危险）。四种确认：清空全部数据、导入备份、导入加密备份（含导出时间/设备/数量）、替换同步密钥（新旧指纹）。
- 行内弹层 popover（订阅行币种/周期/日期）：panel（圆角 18 bg card）+ shadow_lg，币种宽 130、周期宽 140、日期宽 280（subscriptions.rs:277-414）。
- 校验错误：弹层标题下方一行 12px negative 文本（render.rs:529-538、734-737），不清空已输入内容（editing.rs:155「未保存：{错误}」）。

### 5.7 撤销横幅与通知条（render.rs:184-221）
- 撤销横幅：panel（圆角 18、内边距 20、bg card、shadow_lg）横向排：消息 14px（「已删除资产」/「已删除订阅」/「已记录本次变动」，state.rs:331、350；editing.rs:132）+「撤销 · 6秒内」强调文字钮（accent）；位于页面底部 24、左右 40，水平居中；6 秒自动消失（render.rs:185；state.rs:296-299），新票据替换旧票据。桌面端**无进度条**。
- 通知条：panel 横向排位于右下 24/40：消息 14px（如「已顺延 N 期」，subscriptions.rs:447）+「×」钮（accent，点击关闭）；不自动消失。

## 6. 页面结构（区块顺序）

顶栏（56px：品牌/分段导航/设置）之下，内容列最大宽 1040、居中、px44、上 40、下 100（render.rs:75-83、121-169）。

### 6.1 总览（overview.rs）
1. 页头：「总览」25 + 副标题 `{2026年11月14日} 星期{六} · 数据已保存在本机`。
2. hero 行（mb44，两端底对齐、gap36）：左＝「净资产」标签 12 ink3（下距 10）→ hero 金额 56/26/29 → 元信息行（mt16、gap22，元素间 1×11 竖线）：`• 总资产 ¥xx`（点标 4px 圆 ink3，值 Medium text）、`• 总负债 ¥xx`（值 neg）、`• 负债率 xx.x%`；右＝「本月待扣」块（左 1px line 竖线、pl34）：标签 11.5 ink3 → `¥xx / $xx` 20 SemiBold → 旁注 11.5 muted（「N 个订阅 · 下一笔 11月20日」/「N 个订阅 · M 笔待确认」/「暂无扣费」）。
3. 「资产构成」分区（分区头 12 SemiBold ink3 + 右「全部明细 →」链接 12.5 muted + arrow-right 12，mb14）：五张构成卡两行排布（第一行 cash+fixed 两张，第二行 investment+receivable+debt 三张，gap14）：卡＝chip 27×27 圆角 8 + 分类名 13 Medium muted + 右上「＋」小圆钮 24×24；金额 25 SemiBold（mt18，负债 neg）；底行（mt14、gap10）「N 项」11.5 ink3 + 占比条（高 3，负债 #E58A7A@55%/其余 #F3F3EE@32%）+ 百分比 11.5（宽 34 右对齐）。点卡跳资产页并滚动定位到该分类（overview.rs:390-393）。
4. 「即将扣费」分区（mt46，分区头 + 右「全部订阅 →」）：卡内前 5 条按日期升序的行（见 §5.5），行点击跳订阅页；空态＝「未来 30 天没有待扣费用」12.5 ink3（px18 py22）。

### 6.2 资产与负债（asset_page.rs）
1. 页头：「资产与负债」+ 副标题「N 项记录 · 5 个固定分类」；下空 34。
2. 汇总条（pb30、1px line 下分隔）：三列 sum_item（label 11.5 ink3 + 值 22 SemiBold）：总资产 text / 总负债 neg / 净资产 text；非末列右侧 1px line 竖分隔（mr34 pr34）；溢出时行尾红字「金额合计超出整数范围，请检查资产数据」12 neg。
3. 下空 34；五个分区（间距 34），每分区＝分区头（chip 27×27 + 分类名 14 SemiBold + 「N 项」11.5 ink3 + 弹性 + 分区合计 15 SemiBold（负债 neg）+「＋ 添加」文字钮（plus 12 + 文字 12.5 ink3，高 26））＋ 卡（圆角 18、1px line、mt11）：行列表（§5.5 资产行；点历史 chip 展开变动记录面板）。
4. 新建资产：不弹窗，分区卡顶部插入行内表单条（bg subtle、px16、py10、1px line 下分隔）：名称输入（宽 240）+ 金额输入（宽 160）+「完成」「取消」（editing.rs:174-191；asset_page.rs:312-327）。
5. 分区空态：居中「还没有记录，点击右侧「添加」记下第一笔」12.5 ink3（py20 px16）。

### 6.3 订阅（subscriptions.rs）
1. 页头行（mb34、底对齐）：「订阅」+ 副标题「N 个在订 · M 个已暂停」；右侧「＋ 新建订阅」强调文字钮（bg card）。
2. 统计卡（list_card 横排、py16 px4、mb34）：四列 stat_col（水平 18）：本月待扣 / 月均支出 / 年化支出（值＝`¥xx / $xx` 19 SemiBold；口径提示为悬浮 Hint 卡：px10 py6 圆角 8 bg card 1px line 11.5px，subscriptions.rs:72-101）+ 下一个扣费（日期 19 SemiBold + 服务名 14.8 Medium ink3）。
3. 分组列表：待确认 / 本月 / 下月 / 之后 / 已暂停（组间 mt30，组头 group_head 11.5 SemiBold + 数量 11）：每卡 list_card，行为 §5.5 订阅行；行内按钮触发 popover 或动作。
4. 整页空态：panel（py16 居中）「还没有订阅」+「记下常用服务，随时知道接下来要扣多少钱」+「＋ 新建订阅」钮（subscriptions.rs:134-150）。
5. 新建/编辑弹层：见 §5.6 新建订阅弹层（编辑沿用行内编辑，不弹窗）。

### 6.4 编辑弹层
桌面端编辑全部为**行内编辑**：点行内名称/金额/日期即原地变输入框（editing.rs:15-74），新建资产在分区卡顶部行内表单条，仅新建订阅用居中弹层（§5.6）。移动端按平台惯例：iOS 用 sheet、安卓用 bottom sheet/dialog，字段与脚注照 §5.6，保存/取消按钮照 §5.2。

### 6.5 设置与备份（render.rs:328-453）
分组（组间 1px line，组内行 py7）：
1. 外观：主题行（分段控件，见 §5.3）＋说明「跟随系统会自动匹配电脑的深浅色。」
2. 数据：导出备份（说明「保存为 JSON 文件，可随时导入恢复。」＋次级钮「导出」）；导入备份（「会覆盖当前所有记录，请先导出备份。」＋「导入」）；清空全部数据（「删除本机上的全部资产与订阅记录。」＋危险钮「清空」→确认弹层）。
3. 加密备份：加密备份（「导出 .sfseal 密文，可安全上传网盘或发给家人。」＋「导出密文」）；恢复加密备份（「导入密文并覆盖当前数据；需要本机同步密钥。」＋「导入密文」）；同步密钥指纹（只读行，右侧等宽值或「尚未生成」）；同步密钥恢复码（「换机或重装后恢复加密备份；请离线保存，不要放云盘同步目录。」＋「导出恢复码」「导入恢复码」）。
4. 关于：行「SnowFold · MVP 0.1」—「离线运行 · 无网络请求」。

### 6.6 错误与启动失败
- 存储不可用（启动失败/库损坏）：页面区只显示红字 panel（圆角 18 bg card，文本 negative，render.rs:84-86），如「启动备份失败：{错误}。原数据未改变，请检查数据目录权限。」（state.rs:162）。无图标、无业务内容。移动端：整页错误态，文案照抄 docs/copy.md，保持本规范颜色。
- 运行中错误：内容区顶部红字 panel（neg 色 14px）。

## 7. 图表

**桌面端无任何图表**（无环形图、条形图、时间线；唯一的图形元素是构成卡占比条 3px，overview.rs:464-479）。旧版 design.md 的三张图表（§7）作废。移动端：桌面端无对应，移动端按平台惯例实现，保持本规范的颜色与卡片样式；若实现分类构成图，用 §1.2 分类 glyph 色。

## 8. 移动端适配规则（视觉语言；布局见 docs/mobile-layout.md）

> 本节为移动端适配规则。桌面端没有手机宽度的现成实现；375pt/360dp 下的单列排布、导航容器与各页区块布局由 docs/mobile-layout.md 定义，本节只定视觉语言如何延续。

- 视觉语言原样延续：§1 颜色（含深浅两套）、§2 字阶字重、§3 卡片样式（圆角 18、1px line 描边、卡片无阴影）、§4 图标与容器、§6 区块顺序与层级不变；改动只有两处——多列改单列、桌面端顶栏换成各平台导航容器（导航容器底色 card + hairline_strong 上分隔，选中项 accent）。
- 内容列改全宽，左右边距 16（桌面 44 是窗口边距，不适用于手机）；底部预留导航容器高度 + 24。
- 弹层：新建订阅与设置改为全宽底部 sheet（顶部两角圆角 22、bg card、1px line、遮罩同 §5.6），底栏按钮吸底；确认弹层宽 min(400, 屏宽−32) 居中。
- 撤销横幅/通知条：左右边距改 16、底部 24＋安全区，样式照 §5.7。
- 最小命中区 ≥44pt/dp（平台要求）；图标视觉尺寸照 §4.2，不动。
- 触感、返回手势、edge-to-edge、系统文件选择、读屏：桌面端无对应，移动端按平台惯例实现，保持本规范的颜色与卡片样式。

## 9. 动效与反馈

桌面端实际只有三项：
| 场景 | 规格 | 出处 |
| --- | --- | --- |
| 新建行高亮 | 行底 hover 色，3 秒后淡出，并滚动定位到该行 | editing.rs:139-152 |
| 撤销窗口 | 6 秒后自动撤下，新票据替换旧票据 | render.rs:185；state.rs:296-299 |
| 悬浮 | 列表行/按钮底色过渡（GPUI 默认） | 各行 .hover |

其余（横幅出入场、数字过渡、分段滑动高亮、sheet 开合、列表增删动画）桌面端无对应，移动端按平台惯例实现，保持本规范的颜色与卡片样式。触感反馈桌面端无对应，移动端按平台惯例（保存成功/续费顺延＝success，删除/清空确认＝warning，暂停/恢复＝light）。

## 10. 未定与沿用

- 主题跟随：桌面端存 app_meta `theme`（system/light/dark），跟随系统由 GPUI 外观订阅实现（state.rs `_appearance`）；移动端用各自系统 Appearance。
- 快捷键仅桌面端（§5.1），移动端不适用。
- 金额、年化、币种并列格式只调核心 `format_money` / `subscription_annual`，文案冻结清单见 docs/copy.md（191 键）。
