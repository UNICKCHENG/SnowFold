# SnowFold 共享核心接口契约

本契约为四端冻结版。iPhone / Mac 的 SwiftUI、Android 的 Compose 与 Windows 的 GPUI 共用 `snowfold-core`。接口唯一来源为 `core/src/ffi.rs`，UniFFI 与生成器精确锁定 0.32.2，宿主从当前核心库生成类型，不手写副本。

## 约定

- 公开持久身份统一使用 `RecordId = String`：小写、标准连字符、非 nil UUID。新资产、快照、修正、订阅、revision、ledger 与 replica 均为随机 UUIDv4，不含排序语义。SQLite 整数行号只存在于核心内部。非法 ID 返回 Validation，合法但不存在的返回 NotFound。
- 时刻由核心写入，统一 UTC 固定九位小数 RFC3339（`2026-11-14T05:00:00.123456789Z`），备份只接受该格式；时刻不用于判定因果或可撤销性；开启同步时，资产与订阅按 `(updated_at, revision_uid)` 后写者胜。核心生成的业务时间大于数据库已观察到的最大业务时间，以抵御本机墙钟回拨。
- 自然日严格 `YYYY-MM-DD`，年份 0001–9999，宿主传当前本地日给 `on`；月末续费按现有日历算法裁切（2024-01-31 月订阅于 2024-03-01 续费得 2024-03-29、2 期）。
- 金额为 i64 整数分；资产与快照固定 CNY，订阅限 CNY/USD；持久金额非负，净资产与 `format_money` 可为负。输入最多两位小数；年化按周 52 / 月 12 / 季 4 / 年 1，月均为年化除 12 后四舍五入到分；币种分别统计，不换汇。
- 枚举：分类 cash/fixed/investment/receivable/debt；周期 weekly/monthly/quarterly/yearly；状态 active/paused。中文文案以冻结的 `docs/copy.md` 为准。
- 一个绝对数据目录由一个 `SnowFoldCore` 共享，内部 Mutex 串行；宿主在工作队列同步调用、成功后 UI 线程刷新，调用期间禁用重复提交。没有自动重放、expected_revision 或后台同步队列。前台同步只由宿主显式调用，HTTP 期间释放业务锁。
- `open` 创建目录与 `snowfold.sqlite3`，不做启动备份、不生成密钥。数据库版本只认 `user_version`，当前基线为 3，基线 2 通过单事务新增 `sync_state` 表升级。旧结构库、非空的 version=0 库、高版本库返回 IncompatibleDatabase 且保留原文件。迁移按版本顺序执行，每步独立事务；失败步骤回滚，已提交步骤保留。
- SQLite 无原生 UUID / UTC 类型，核心在入口一次校验，表上只有形状 CHECK；核心能整库替换，直接 SQL 也可能绕过应用事务，以核心为唯一写入口。
- 备份格式 `format_version=1`，与 UI 读模型分开；未知版本返回 IncompatibleBackup，当前数据不变。三个版本层次各自独立：数据库基线 `user_version=3`（见 `open` 约定）、备份载荷 `format_version=1`、密封包信封内 `version=2`。密封包使用 `.sfseal` 扩展名，载荷格式标识为 `snowfold.sealed-snapshot`；信封字节布局、AAD 与密钥文件规格见 [SECURITY.md](../SECURITY.md)。旧 `schema_version` JSON、裸 Backup 或 version=1 密封载荷返回 IncompatibleBackup。
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
| `open(data_dir) -> SnowFoldCore` | 打开或按基线 3 初始化或从基线 2 升级绝对目录；相对路径 Validation，不兼容库 IncompatibleDatabase |
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
| `InvalidRecoveryCode` / `KeyUnavailable` / `Io` / `Database` / `Overflow` / `Internal` | 恢复码或密钥内容无法解析；系统安全存储读写失败或密钥已失效（不会静默生成新密钥）；数据目录或备份文件读写失败；数据库读写失败；合计金额溢出；核心内部状态异常 | 均不改动业务数据，按 `docs/copy.md` 对应错误键提示 |

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


## 前台端到端加密同步

同步业务接口与 HTTP 类型只在 `core/src/ffi.rs` 定义。Swift/Kotlin 用同一内核生成，不手写。核心负责合并、加密、对象路径、认证、AWS SigV4 与条件写，宿主只传 HTTP；未调用 `sync_now` 不联网。移动端默认构建不包含 Rust HTTP/TLS，URLSession/OkHttp 实现 `SyncTransport`；Rust 桌面调用方显式开启 `sync-http` feature 后使用 `sync::RustSyncTransport::default()`。

### 类型与接口

| 类型 | 字段 |
| --- | --- |
| `SyncBackend` | `S3`、`WebDav` |
| `SyncConfig` | backend、endpoint、bucket、region、prefix、username、path_style；username 为 S3 access key id 或 WebDAV 用户名，读取配置绝不返回 secret |
| `SyncStatus` | config（可空）、enabled、recovery_code_exported、last_synced_at（可空，UTC 纳秒 RFC3339）、policy |
| `SyncPolicy` | on_launch（默认true）、interval_minutes（只允许0/15/30/60，默认30）、after_edit（默认true） |
| `SyncReport` | pulled_records、pushed_records、merged_records、last_synced_at、retries、pending_local_changes |
| `SyncHeader` | name、value |
| `SyncRequest` | method、url、headers、body |
| `SyncResponse` | status（u16）、headers、body |
| `SyncTransport`（foreign trait） | `send(request: SyncRequest) -> Result<SyncResponse, CoreError>`；Send + Sync，同步回调，由宿主工作队列调用 |

| 方法 | 成功行为与失败条件 |
| --- | --- |
| `sync_status() -> SyncStatus` | 读本机状态，不联网、不生成密钥 |
| `set_sync_policy(policy) -> ()` | 持久化前台策略；非法间隔返回Validation；宿主负责前台定时器及编辑后30秒去抖 |
| `configure_sync(config, secret) -> ()` | 校验后把 secret 用同步密钥加密入库，保存公开配置；enabled=false、last_synced_at=nil；变更配置需重新传完整 secret；不重置policy |
| `set_sync_enabled(enabled) -> ()` | 开启要求已配置、密钥可用、该密钥已导出恢复码；关闭保留配置 |
| `clear_sync_config() -> ()` | 删除配置、凭证密文与最后同步时间，恢复默认policy，保留密钥、恢复码导出标记与远端对象 |
| `sync_now(transport) -> SyncReport` | 前台同步，关闭/未配置时不调用 transport；同一对象不能重入 |

`pulled_records` 是最终成功轮读取远端的全部逻辑行数，`pushed_records` 是 PUT 成功的全部逻辑行数，均含历史、修正和墓碑；`merged_records` 是相对调用开始快照新增、移除或改变的逻辑行数。`retries` 只计 412 后的重新 GET/合并/PUT，首次加最多三次重试。弱 ETag 重读和认证失败后的只读重试不计入该字段。

`export_recovery_code` 成功返回前在 `sync_state` 保存当前密钥指纹；核心可证明已生成并交还恢复码，无法观测系统保存面板取消，宿主仍负责保存返回文本。导入另一把恢复码先清除同步配置再更换系统密钥，避免旧凭证/旧配置参与新钥同步；系统密钥存储失败时配置可能已清除，业务数据不变。导入相同密钥保留配置。新密钥需重新导出恢复码才能开启。

### 后端与传输责任

- S3 支持自定义 endpoint/region、path-style 和虚拟主机寻址，核心用 service=s3 的 AWS SigV4 签名，并把条件头纳入签名。endpoint 可带固定路径。bucket/region 由核心校验。
- WebDAV 的 bucket、region 必须为空，path_style=false；endpoint 是目标根目录，prefix 是相对目录；不使用 PROPFIND。GET 404 时按目录层级 MKCOL 一次，再条件 PUT。已有目录返回 405 可继续。
- prefix 可为空，禁止绝对路径、`..`、查询、片段、百分号编码与反斜线。对象名由密钥单向派生，不含账本/设备身份。
- 远端要求 HTTPS；仅 localhost/127.0.0.1/[::1] 接受 HTTP，用于本机服务。URL 禁止 userinfo、query、fragment。WebDAV 使用 Basic，S3 使用签名，不通过 URL 传凭证。
- 宿主必须校验证书、禁止重定向、设置超时、限制响应为 32 MiB，HTTP 状态原样返回；不得自行重试 PUT，不记录 Authorization、secret 或请求体。传输失败映射 SyncNetwork。Rust 实现连接超时 10 秒，读写超时各 30 秒，拒绝重定向。

### 合并、并发与失败

资产和订阅取 `(updated_at, revision_uid)` 较大版本；删除时间不小于修改时间时删除胜出。历史按 UUID 并集，允许并发余额分支，资产 current_history_id 决定当前余额。修正事实按 UUID 并集，快照有效金额取 `(recorded_at, id)` 最大修正；同 UUID 不可变载荷不同拒绝远端。空设备的 ledger_id 与另一设备合并后取字典序最小者，replica/device/theme 保持本机值。

GET 200 必须有唯一的强 ETag，写入用 If-Match；GET 404 的首建用 If-None-Match: *。412 重新读取最新远端后合并，最多三次重试。没有无条件覆盖路径。Apache 新文件可能先返回弱 ETag，核心等待 1.1 秒后只重读一次，仍弱或缺失 ETag 则拒绝。认证解密失败先等待 200 ms 只读重试；新读仍失败且 ETag 相同则返回 SyncRemoteKeyMismatch，ETag 变动且仍认证失败返回 SyncNetwork，全程不 PUT。

推送前不写本机业务数据。PUT 成功后，核心锁住本机，把网络期间本机最新写入再合并，在同一事务内替换业务数据、保存最后同步时间，成功提交后清空撤销票据；没有半条记录。该阶段新的本机变更若尚未包含在远端，pending_local_changes=true，下一次前台调用继续提交。配置、整库导入、导入恢复码与同步运行互斥，冲突返回 Conflict；普通记账可在网络期间继续。

**已知边界**：WebDAV 并发写的不丢更新取决于服务端是否原子执行条件写。Apache 验收服务确实可能让两个并发 PUT 都通过同一个强 If-Match，内核不能替代服务器事务锁。没有删除/复活交错的存活记录，其历史在两机及三机任意同步顺序下并集收敛。若第三台离线设备在删除前新增历史，另一台先同步胜出的删除、再由较晚修改复活资产，新增历史可能因同步先后丢失；当前载荷未保留删除前的合并事实。

### 稳定错误与宿主刷新

新增 CoreError：RecoveryCodeRequired、SyncNotConfigured、SyncDisabled、SyncNetwork、SyncAuthentication（HTTP 401/403）、SyncConflict、SyncRemoteKeyMismatch、SyncInvalidRemote、SyncIncompatible。配置输入错误复用 Validation，运行互斥复用 Conflict。新增错误 Display 为技术代码，界面映射用户文案，不能直接展示 Display。

| 操作 | 必须刷新 |
| --- | --- |
| configure_sync / set_sync_enabled / set_sync_policy / clear_sync_config | sync_status |
| export_recovery_code / import_recovery_code | key_fingerprint、sync_status；业务列表不变 |
| sync_now 成功 | data、overview、订阅分组、当前历史/修正面板、sync_status；清空撤销提示与失效 ID 选择 |
| sync_now 失败 | sync_status；没有额外远端数据落库，保留调用期间本机写入结果 |
| 普通资产/订阅写 | 沿用原刷新表，不触发网络 |

同步配置表是本机状态，不进入 JSON/.sfseal 或远端同步载荷。数据库 2→3 只加表，失败事务回滚；备份 format_version=1、密封载荷 version=2 和原 AAD 保持兼容。

策略即使未配置也可读写；configure_sync保留，clear_sync_config恢复默认，关闭同步保留策略。on_launch表示打开/回到前台同步，interval_minutes=0表示关闭前台定时同步，after_edit由宿主在修改后30秒去抖触发；内核不创建定时器，不在后台联网。策略与其他配置一样只存本机sync_state，不进入任何备份。
