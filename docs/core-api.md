# SnowFold 共享核心接口契约

本契约为四端冻结版。iPhone / Mac 的 SwiftUI、Android 的 Compose 与 Windows 的 GPUI 共用 `snowfold-core`。接口唯一来源为 `core/src/ffi.rs`，UniFFI 与生成器精确锁定 0.32.2，宿主从当前核心库生成类型，不手写副本。

## 约定

- 公开持久身份统一使用 `RecordId = String`：小写、标准连字符、非 nil UUID。新资产、快照、修正、订阅、revision、ledger 与 replica 均为随机 UUIDv4，不含排序语义。SQLite 整数行号只存在于核心内部。非法 ID 返回 Validation，合法但不存在的返回 NotFound。
- 时刻由核心写入，统一 UTC 固定九位小数 RFC3339（`2026-11-14T05:00:00.123456789Z`），备份只接受该格式；时刻不用于判定因果、当前版本或可撤销性。
- 自然日严格 `YYYY-MM-DD`，年份 0001–9999，宿主传当前本地日给 `on`；月末续费按现有日历算法裁切（2024-01-31 月订阅于 2024-03-01 续费得 2024-03-29、2 期）。
- 金额为 i64 整数分；资产与快照固定 CNY，订阅限 CNY/USD；持久金额非负，净资产与 `format_money` 可为负。输入最多两位小数；年化按周 52 / 月 12 / 季 4 / 年 1，月均为年化除 12 后四舍五入到分；币种分别统计，不换汇。
- 枚举：分类 cash/fixed/investment/receivable/debt；周期 weekly/monthly/quarterly/yearly；状态 active/paused。中文文案以冻结的 `docs/copy.md` 为准。
- 一个绝对数据目录由一个 `SnowFoldCore` 共享，内部 Mutex 串行；宿主在工作队列同步调用、成功后 UI 线程刷新，调用期间禁用重复提交。没有自动重放、expected_revision、同步队列或云同步。
- `open` 创建目录与 `snowfold.sqlite3`，不做启动备份、不生成密钥。数据库版本只认 `user_version`，当前基线为 2。旧结构库、非空的 version=0 库、高版本库返回 IncompatibleDatabase 且保留原文件。迁移按版本顺序执行，每步独立事务；失败步骤回滚，已提交步骤保留。
- SQLite 无原生 UUID / UTC 类型，核心在入口一次校验，表上只有形状 CHECK；核心能整库替换，直接 SQL 也可能绕过应用事务，以核心为唯一写入口。
- 备份格式 `format_version=1`，与 UI 读模型分开；未知版本返回 IncompatibleBackup，当前数据不变。三个版本层次各自独立：数据库基线 `user_version=2`（见 `open` 约定）、备份载荷 `format_version=1`、密封包信封内 `version=2`。密封包使用 `.sfseal` 扩展名，载荷格式标识为 `snowfold.sealed-snapshot`；信封字节布局、AAD 与密钥文件规格见 [SECURITY.md](../SECURITY.md)。旧 `schema_version` JSON、裸 Backup 或 version=1 密封载荷返回 IncompatibleBackup。
- `app_meta` 中 `ledger_id` 随备份与恢复走，`replica_id`、`device_id`、`theme` 为本机身份，恢复不覆盖；`clear_data` 不改 ledger_id。
- 可选值用 UniFFI optional，空集合返回空集合。`data` 排序：资产按创建行号、历史按行号、订阅按 next_charge_on、uid。错误为稳定枚举，描述不用于分支。

## 导出类型

| 类型 | 用途与字段 |
| --- | --- |
| `RecordId`（Rust 别名） | 全部持久身份的集中定义；当前为小写 UUID 字符串 |
| `SnowFoldCore`（Object） | 持有串行 SQLite 连接、数据目录与一条待撤销操作 |
| `CoreError`（Error） | 稳定错误枚举，含 IncompatibleDatabase，见错误表 |
| `AssetRecord` | id、revision_uid、current_history_id、category_code、name、amount_cents、created_at、updated_at |
| `HistoryRecord` | id、asset_id、previous_id（可空，首条为 nil）、original_amount_cents、amount_cents、recorded_at；只含有效快照，修正不新增条目 |
| `SubscriptionRecord` | id、revision_uid、name、amount_cents、currency、cycle、next_charge_on、status、created_at、updated_at |
| `SubscriptionInput` | 订阅创建与更新的宿主输入：name、amount_cents、currency、cycle、next_charge_on、status |
| `HistoryCorrectionRecord` | id、history_id、previous_amount_cents、amount_cents、recorded_at；按时间追加，解释原值到现值 |
| `LedgerData` | 当前资产、有效快照、全部订阅；撤销后的快照不出现在 history |
| `CategoryTotal` / `CurrencyTotal` / `Overview` | `CategoryTotal`：code、name、is_liability、amount_cents、count。`CurrencyTotal`：currency、due_this_month_cents、monthly_cents、annual_cents。`Overview`：total_assets_cents、total_debt_cents、net_assets_cents、categories、subscription_totals、active/paused/overdue_subscriptions、next_charge_on（可空）、upcoming；金额与统计语义见「约定」的金额条 |
| `ThemePreference` / `SubscriptionGroup` | System/Light/Dark 与 Overdue/ThisMonth/NextMonth/Later/Paused |
| `UndoTicket` | 不透明 token 与 valid_for_ms=6000；对象同一时刻只有一条，令牌内容无语义 |
| `BackupInfo` / `SealedBackupInfo` | 数量确认；密封包附 exported_at_display 与 device_id |
| `RecoveryCodeInfo` / `KeyImportResult` / `RenewalResult` | `RecoveryCodeInfo`：current_fingerprint（可空，本机尚无密钥时）、new_fingerprint。`KeyImportResult`：fingerprint、previous_key_backed_up（旧密钥是否已备份为 `.bak`）。`RenewalResult`：next_charge_on、periods（本次顺延的期数） |

## 导出函数

| 函数 | 用途与成功结果 |
| --- | --- |
| `open(data_dir) -> SnowFoldCore` | 打开或按基线 2 初始化绝对目录；相对路径 Validation，不兼容库 IncompatibleDatabase |
| `data() -> LedgerData` | 一致读事务内读取当前读模型 |
| `overview(on) -> Overview` | 按自然日汇总；溢出 Overflow，非法日期 Validation |
| `create_asset(category_code, asset_name, amount_cents) -> RecordId` | 创建资产与首条有效快照，返回新 UUID；0 分允许 |
| `rename_asset(id, asset_name) -> ()` | 重命名并生成新 revision_uid、updated_at |
| `change_balance(id, amount_cents) -> UndoTicket?` | 金额变化时追加快照、前驱指向原当前，并原子推进指针；相同金额返回 nil 且不影响已有票据 |
| `correct_history(id, amount_cents) -> ()` | 追加修正记录并更新快投影；修正当前快照同步余额与 revision，修正旧快照不动当前余额 |
| `history_corrections(id) -> Vec<HistoryCorrectionRecord>` | 返回一条快照的全部修正，回答改前改后 |
| `delete_asset(id) -> UndoTicket` | 事务内删除资产与全部快照、修正，写 asset 墓碑，返回 6 秒撤销票据 |
| `undo(token) -> ()` | 余额撤销把当前快照标 voided 并回指前驱；删除撤销原样恢复 UUID 与金额、清墓碑、给新 revision；过期、令牌不符、对象已变化返回 Conflict，失败不改变数据 |
| `create_subscription(subscription) -> RecordId` | 显式创建，返回新 UUID；不再用 id=0 |
| `update_subscription(id, subscription) -> ()` | 完整更新并生成新 revision_uid、updated_at |
| `set_subscription_paused(id, is_paused) -> ()` | 暂停或恢复，保留下次扣费日 |
| `renew_subscription(id, on) -> RenewalResult` | 到期 active 订阅按日历顺延；暂停或未到期 Conflict |
| `subscription_group(id, on) -> SubscriptionGroup` / `subscription_annual(id) -> i64` | 分组与单条年化 |
| `delete_subscription(id) -> UndoTicket` | 删除订阅并写 subscription 墓碑，6 秒内可撤销 |
| `theme()` / `save_theme(theme)` | 本机主题 |
| `export_json() -> String` | 一致读事务导出 format_version=1 备份，序列化前 validate |
| `inspect_json(json) -> BackupInfo` | 按版本解码并校验，不改数据 |
| `import_json(json) -> ()` | 校验后单事务整库替换，ledger_id 随备份更新，失败不变 |
| `export_sealed() -> Vec<u8>` | 一致读事务内构造信封后加密，密钥缺失自动生成 |
| `inspect_sealed(bytes) -> SealedBackupInfo` | 认证解密、版本与全量校验后返回确认信息，不改数据 |
| `import_sealed(bytes) -> SealedBackupInfo` | 再次校验后整库替换；失败保持数据 |
| `key_fingerprint()` | 返回本机密钥指纹；尚无密钥时为空，不生成密钥 |
| `export_recovery_code()` | 按需加载或创建密钥，返回带注释头与指纹的恢复码文本，文件格式见 `docs/copy.md` |
| `inspect_recovery_code(contents)` | 解析恢复码文本并返回当前指纹与新指纹对照；解析失败 InvalidRecoveryCode，数据不变 |
| `import_recovery_code(contents, expected_current_fingerprint)` | 当前指纹与传入值不符返回 Conflict；一致时写入新密钥，旧密钥备份为 `.bak`，返回新指纹与是否发生备份 |
| `clear_data() -> ()` | 删除全部资产与订阅，逐个写墓碑；保留主题与三个身份键 |
| `startup_backup() -> ()` | 保存明文 JSON 启动备份，保留 10 份 |
| `parse_amount` / `format_amount` / `format_money` | 金额文本与显示，规则不变 |

## 错误与失败状态

| 变体 | 何时出现 | 界面处理 |
| --- | --- | --- |
| `Validation` | 空白名称、负金额、未知分类/币种/周期/状态、非法 UUID、非法日期或时刻文本、相对目录 | 保留输入，提示后重试 |
| `NotFound` | ID 合法但对象不存在 | 刷新 data，关闭已失效的编辑对象 |
| `Conflict` | 撤销过期/令牌不符/对象已变化、续费暂停或未到期、事务中途失败、SQLite 忙或约束冲突、恢复码确认过时 | 刷新状态重新确认；事务回滚，无部分写入 |
| `IncompatibleDatabase` | 打开旧结构、非空 version=0 或高版本库 | 提示数据库版本不兼容；保留原文件，不迁移不清空 |
| `IncompatibleBackup` | 未知 format_version 或密封 version、旧 schema_version JSON、裸 Backup 密封载荷 | 保留当前数据，不做降级导入 |
| `InvalidBackup` | 非法字段、重复 UUID、断链、金额不一致、坏 magic、截断、非法密封元数据 | 显示备份无效；数据不变 |
| `Authentication` | AES-GCM 认证失败 | 提示错钥或损坏，不区分两者 |
| `InvalidRecoveryCode` / `KeyUnavailable` / `Io` / `Database` / `Overflow` / `Internal` | 恢复码或密钥文件内容无法解析；密钥文件不可读、创建或替换；数据目录或备份文件读写失败；数据库读写失败；合计金额溢出；核心内部状态异常 | 均不改动业务数据，按 `docs/copy.md` 对应错误键提示 |

## 写操作后的刷新

| 成功写操作 | 必须刷新 |
| --- | --- |
| `create_asset` / `rename_asset` / 有变动的 `change_balance` / `correct_history` / `delete_asset` | data、overview；删除时关闭对应历史面板，可调 `history_corrections(id)` 展示改前改后；新票据替换旧提示 |
| `undo` | data、overview、分组与历史面板；清空已消费票据 |
| `create_subscription` / `update_subscription` / `set_subscription_paused` / `renew_subscription` / `delete_subscription` | data、overview、依赖 on 的分组与下一扣费 |
| `import_json` / `import_sealed` / `clear_data` | data、overview、全部分组；清空撤销、旧 ID 选择与输入；主题和密钥独立 |
| `save_theme` | theme 与宿主外观 |
| `export_sealed` / `export_recovery_code` / `inspect_sealed` / `import_recovery_code` | key_fingerprint 可能首次生成；业务列表无变化 |
| `startup_backup` | 无业务刷新；失败提示本机备份未保存 |

## 生成与接入

各端在构建时用内核自带生成器 `core/bindgen`（UniFFI 0.32.2）从刚编译的内核生成调用代码，不入库，核心是唯一来源：

- iPhone / Mac：构建入口 `apps/apple/scripts/build-core.sh`，输出到 `apps/apple/Generated/`。
- 安卓：`./gradlew assembleDebug` 自动执行，输出到 `app/build/generated/uniffi/kotlin/`。

完整命令、工作目录与手动调试方法见 [development.md](development.md#绑定生成与手动调试)。生成命名固定：Swift module `SnowFoldCore`，FFI module `SnowFoldCoreFFI`；Kotlin package `app.snowfold.core`，库名 `snowfold_core`。
