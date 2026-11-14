# SnowFold 三端共用中文文案

状态：**冻结，之后修改只经用户**。适用于 SwiftUI（iPhone/Mac）、Compose（Android）、GPUI（Windows）三种宿主。

英文键名统一 snake_case。`{name}` 为命名占位符，替代原 Rust 的位置占位符或格式标记；`\n` 表示显示换行。相同中文保留一个公共键，带图标符号的按钮保留原展示。日期、金额展示由宿主调用核心或本地排版；占位符中的金额是已格式化文本，绝不作为浮点传回核心。宿主按稳定错误枚举映射本文「FFI 错误描述」的文案作为 `{detail}`，不直接使用 Rust 的 Display，两者不保证逐字相同；不拼接 SQL、路径、密钥或堆栈。

## 页面、分区与统计

| key | 中文文案 | 用途 |
| --- | --- | --- |
| app_name | SnowFold | 应用名 |
| overview_title | 总览 | 页面及导航 |
| assets_tab | 资产 | 导航 |
| assets_title | 资产与负债 | 资产页面 |
| subscriptions_title | 订阅 | 页面及导航 |
| settings_title | 设置 | 设置面板 |
| new_subscription_title | 新建订阅 | 新建弹层 |
| overview_subtitle | {date} 星期{weekday} · 数据已保存在本机 | 总览副标题 |
| assets_subtitle | {count} 项记录 · 5 个固定分类 | 资产副标题 |
| subscriptions_subtitle | {active} 个在订 | 没有暂停订阅时 |
| subscriptions_subtitle_with_paused | {active} 个在订 · {paused} 个已暂停 | 有暂停订阅时 |
| net_assets | 净资产 | 总览和资产合计 |
| total_assets | 总资产 | 合计 |
| total_debt | 总负债 | 合计 |
| debt_ratio | 负债率 | 总览 |
| asset_composition | 资产构成 | 总览分区 |
| all_details | 全部明细 | 总览跳转资产 |
| upcoming_charges | 即将扣费 | 总览分区 |
| all_subscriptions | 全部订阅 | 总览跳转订阅 |
| due_this_month | 本月待扣 | 订阅统计 |
| monthly_spending | 月均支出 | 订阅统计 |
| annual_spending | 年化支出 | 订阅统计 |
| due_this_month_help | 在用订阅中，下次扣费日落在当前自然月的金额合计（含本月已过期、待确认的） | 原统计提示 |
| monthly_spending_help | 月均为年化合计 ÷ 12 后舍入 | 订阅统计提示 |
| annual_spending_help | 按扣费周期折算为全年支出 | 订阅统计提示 |
| next_charge | 下一个扣费 | 订阅摘要 |
| subscription_summary_overdue | {active} 个订阅 · {overdue} 笔待确认 | 总览订阅摘要 |
| subscription_summary_next | {active} 个订阅 · 下一笔 {date} | 总览订阅摘要 |
| subscription_summary | {active} 个订阅 | 总览订阅摘要 |
| item_count | {count} 项 | 分类记录数 |
| appearance_section | 外观 | 设置分区 |
| data_section | 数据 | 设置分区 |
| encrypted_backup_section | 加密备份 | 设置分区及行标题 |
| about_section | 关于 | 设置分区 |
| app_version | SnowFold · MVP 0.1 | 关于行 |
| offline_description | 离线运行 · 无网络请求 | 关于说明 |
| category_cash | 流动资金 | 固定分类 cash |
| category_fixed | 固定资产 | 固定分类 fixed |
| category_investment | 投资理财 | 固定分类 investment |
| category_receivable | 应收款 | 固定分类 receivable |
| category_debt | 负债 | 固定分类 debt |

## 字段、选项与日期

| key | 中文文案 | 用途 |
| --- | --- | --- |
| name_label | 名称 | 字段与输入占位 |
| amount_label | 金额 | 字段 |
| currency_label | 币种 | 字段 |
| billing_cycle_label | 扣费周期 | 字段 |
| next_charge_date_label | 下次扣费日 | 日期字段 |
| currency_cny | ¥ 人民币 | 币种选项 |
| currency_usd | $ 美元 | 币种选项 |
| cycle_weekly | 每周 | weekly |
| cycle_monthly | 每月 | monthly |
| cycle_quarterly | 每季 | quarterly |
| cycle_yearly | 每年 | yearly |
| cycle_unknown | 未知周期 | 旧界面非法周期回退 |
| group_overdue | 待确认 | 逾期 active 订阅 |
| group_this_month | 本月 | 当前自然月 |
| group_next_month | 下月 | 下一个自然月 |
| group_later | 之后 | 更远日期 |
| group_paused | 已暂停 | paused 订阅 |
| theme_label | 主题 | 设置字段 |
| theme_system | 自动 | 跟随系统 |
| theme_light | 浅色 | 浅色偏好 |
| theme_dark | 深色 | 深色偏好 |
| date_long | {year}年{month}月{day}日 | 长日期或跨年短日期 |
| date_short | {month}月{day}日 | 本年短日期 |
| weekday_sunday | 日 | 星期 |
| weekday_monday | 一 | 星期 |
| weekday_tuesday | 二 | 星期 |
| weekday_wednesday | 三 | 星期 |
| weekday_thursday | 四 | 星期 |
| weekday_friday | 五 | 星期 |
| weekday_saturday | 六 | 星期 |
| today | 今天 | 扣费日提示 |
| days_until_charge | {days} 天后 | 总览扣费日提示 |
| overdue_days | 逾期 {days} 天 | 总览扣费日提示 |
| overdue_days_compact | 逾期{days}天 | 订阅行提示 |
| history_title | 变动记录 · 修正不会新增快照 | 历史面板 |
| history_button | 历史 {date} | 历史触发器 |
| history_current | 当前 | 当前余额快照 |
| history_initial | 初始 | 首条快照 |
| new_subscription_help | 填写名称、金额并选择下次扣费日。到期确认续费后按周期顺延。 | 新建订阅说明 |

## 按钮

| key | 中文文案 | 用途 |
| --- | --- | --- |
| done | 完成 | 完成编辑或关闭设置 |
| cancel | 取消 | 取消编辑或确认 |
| save | 保存 | 新建订阅提交 |
| close | 关闭 | 关闭图标可访问名称 |
| dismiss | × | 关闭通知 |
| add | 添加 | 分类添加资产 |
| add_with_icon | ＋ 添加 | 分类添加触发器名称 |
| new_subscription | ＋ 新建订阅 | 页头与空状态按钮 |
| correct_history | 修正 | 历史金额修正 |
| apply_date | 应用日期 | 日期浮层确认 |
| mark_renewed | ✓ 已续费 | 订阅到期确认 |
| pause | 暂停 | active 订阅操作 |
| resume | 恢复 | paused 订阅操作 |
| undo | 撤销 · 6秒内 | 删除/余额变动撤销 |
| export | 导出 | JSON 导出 |
| import | 导入 | JSON 导入 |
| clear | 清空 | 发起清空确认 |
| export_sealed | 导出密文 | 加密备份导出 |
| import_sealed | 导入密文 | 发起加密备份导入 |
| export_recovery_code | 导出恢复码 | 恢复码导出 |
| import_recovery_code | 导入恢复码 | 发起恢复码导入 |
| confirm_overwrite | 确认覆盖 | JSON/密封包导入及现有清空弹层 |
| confirm_replace_key | 确认替换 | 恢复码确认 |

清空弹层的提交按钮沿用「确认覆盖」。

## 空状态与设置说明

| key | 中文文案 | 用途 |
| --- | --- | --- |
| no_asset_records | 还没有记录，点击右侧「添加」记下第一笔 | 分类空状态 |
| composition_empty | 暂无资产 | 总览资产构成图空状态（移动端） |
| no_subscriptions | 还没有订阅 | 订阅空标题 |
| no_subscriptions_help | 记下常用服务，随时知道接下来要扣多少钱 | 订阅空说明 |
| no_charges | 暂无扣费 | 无 active 订阅或下一个扣费 |
| no_upcoming_charges | 未来 30 天没有待扣费用 | 总览空状态 |
| theme_help | 跟随系统会自动匹配电脑的深浅色。 | 原设置说明 |
| export_backup_title | 导出备份 | JSON 设置行 |
| export_backup_help | 保存为 JSON 文件，可随时导入恢复。 | JSON 设置说明 |
| import_backup_title | 导入备份 | JSON 设置行 |
| import_backup_help | 会覆盖当前所有记录，请先导出备份。 | JSON 设置说明 |
| clear_all_title | 清空全部数据 | 清空设置行 |
| clear_all_help | 删除本机上的全部资产与订阅记录。 | 清空设置说明 |
| encrypted_backup_help | 导出 .sfseal 密文，可安全上传网盘或发给家人。 | 加密设置说明 |
| restore_encrypted_backup_title | 恢复加密备份 | 加密恢复设置行 |
| restore_encrypted_backup_help | 导入密文并覆盖当前数据；需要本机同步密钥。 | 加密恢复设置说明 |
| key_fingerprint_label | 同步密钥指纹 | 设置及确认页 |
| key_not_generated | 尚未生成 | 无密钥指纹 |
| recovery_code_title | 同步密钥恢复码 | 恢复码设置行 |
| recovery_code_help | 换机或重装后恢复加密备份；请离线保存，不要放云盘同步目录。 | 恢复码说明 |

“右侧”“电脑”沿用桌面来源文案。手机的控件位置与系统用语若需调整，须经用户修改本冻结表，不在实现中自行改字。

## 确认弹层与系统文件选择

| key | 中文文案 | 用途 |
| --- | --- | --- |
| clear_confirmation_title | 清空全部数据？ | 清空确认标题 |
| clear_confirmation_body | 将删除全部资产、历史与订阅，不能撤销。请先导出备份。 | 清空确认正文 |
| import_confirmation_title | 覆盖本机数据？ | JSON/密封包导入标题 |
| import_confirmation_body | 将导入 {assets} 项资产、{history} 条历史、{subscriptions} 项订阅，并覆盖现有数据。此操作不能撤销。 | JSON 导入正文 |
| sealed_import_confirmation_body | 加密备份导出于 {exported_at}（设备 {device_id}），校验通过：{assets} 项资产、{history} 条历史、{subscriptions} 项订阅。\n确认后会覆盖现有数据，此操作不能撤销；如果导出时间比当前数据旧，请先取消并核对。 | 密封包导入正文 |
| replace_key_confirmation_title | 替换本机同步密钥？ | 恢复码替换标题 |
| replace_key_confirmation_body | 当前指纹 {current_fingerprint}，新指纹 {new_fingerprint}。\n替换后，旧密钥导出的加密备份将无法在本机打开；旧密钥会备份为 snowfold-sync.key.bak。只有确认这个恢复码来自你自己的设备时才继续。 | 恢复码替换正文 |
| choose_json_backup | 选择 SnowFold JSON 备份 | 文件选择标题 |
| choose_sealed_backup | 选择 SnowFold 加密备份 | 文件选择标题 |
| choose_recovery_code | 选择 SnowFold 恢复码文件 | 文件选择标题 |

## 操作反馈与桌面错误模板

| key | 中文文案 | 用途 |
| --- | --- | --- |
| balance_change_recorded | 已记录本次变动 | 可撤销通知 |
| asset_deleted | 已删除资产 | 可撤销通知 |
| subscription_deleted | 已删除订阅 | 可撤销通知 |
| data_updated | 数据已更新 | 导入/清空成功 |
| backup_exported | 备份已导出 | JSON 成功 |
| sealed_backup_exported | 加密备份已导出 · 密钥指纹 {fingerprint} | 密封包成功 |
| recovery_code_exported | 恢复码已导出 · 密钥指纹 {fingerprint} | 恢复码导出成功 |
| key_imported | 同步密钥已导入 · 指纹 {fingerprint} | 首次导入密钥 |
| key_replaced | 同步密钥已替换 · 指纹 {fingerprint}（旧密钥已备份为 .bak） | 替换密钥 |
| subscription_renewed | 已顺延 {periods} 期 | 续费成功 |
| startup_backup_failed | 启动备份失败：{detail}。原数据未改变，请检查数据目录权限。 | 桌面端启动备份错误 |
| startup_key_unavailable | 同步密钥不可用：{detail}。加密备份暂时无法导出或导入；可以在设置里导入恢复码，或处理密钥文件后重启。 | 桌面端启动密钥错误 |
| local_data_unreadable | 无法读取本机数据：{detail}。请检查数据目录权限后重新打开。 | 启动数据库错误 |
| database_unavailable | 数据库不可用 | 桌面上下文错误 |
| operation_failed | 操作失败：{detail}。请检查输入或数据目录后重试。 | 通用操作错误 |
| theme_not_saved | 主题未保存：{detail} | 主题错误 |
| choose_next_charge_date | 请选择下次扣费日 | 新订阅日期必填 |
| choose_date | 请选择日期 | 日期浮层必填 |
| subscription_not_found | 订阅不存在 | 桌面上下文错误 |
| saved_but_reload_failed | 已保存，但读取失败：{detail} | 已成功写入，刷新失败 |
| not_saved | 未保存：{detail} | 编辑错误 |
| nothing_to_undo | 没有可撤销的操作 | 撤销错误 |
| undo_expired | 撤销已过期 | 撤销错误 |
| recovery_code_unreadable | 恢复码内容无法解析，本机密钥未改变 | 恢复码解析错误 |
| key_changed_elsewhere | 本机同步密钥已被其他窗口修改，请重新导入恢复码 | 确认过时 |
| export_failed | 导出失败：{detail} | JSON 导出错误 |
| protected_export_target | 不能覆盖 SnowFold 自己的数据文件，请换一个保存位置 | 导出路径保护 |
| no_file_selected | 没有选择文件 | 文件选择上下文错误；系统取消可静默返回 |
| import_failed_unchanged | 导入失败，原数据未改变：{detail} | JSON 导入错误 |
| sealed_export_failed | 加密备份导出失败：{detail} | 密封包导出错误 |
| key_unreadable_import_aborted | 无法读取同步密钥，加密备份未导入：{detail} | 导入前密钥错误 |
| sealed_import_failed_unchanged | 加密备份导入失败，原数据未改变：{detail} | 密封包导入错误 |
| recovery_export_failed | 恢复码导出失败：{detail} | 恢复码导出错误 |
| recovery_import_failed | 恢复码导入失败：{detail} | 恢复码导入错误 |
| recovery_import_failed_unchanged | 恢复码导入失败，本机密钥未改变：{detail} | 恢复码校验错误 |
| asset_total_overflow | 金额合计超出整数范围，请检查资产数据 | 资产合计错误 |
| out_of_range | 超出范围 | 无法显示合计 |
| total_out_of_range | 合计超出范围 | 分类合计错误 |
| statistics_error | 统计错误：{detail} | 总览订阅统计错误 |
| statistics_failed | 统计失败：{detail} | 订阅页统计错误 |
| renewal_not_due | 下次扣费日尚未到达，无需顺延 | 未到期续费 |

## FFI 错误描述

以下为各稳定错误枚举对应的用户文案。宿主按枚举映射本地化文案作为 `{detail}`，不直接使用 Rust Display；Rust 的 Display 与本表不逐字相同（例如 IncompatibleDatabase 的 Display 只有「数据库版本不兼容」），以本表为准。错误分类使用枚举，键名仅用于本地化资源。

| key | 中文文案 | CoreError |
| --- | --- | --- |
| error_validation | 请检查名称、金额、日期与选项 | Validation |
| error_not_found | 记录不存在，请刷新后重试 | NotFound |
| error_conflict | 数据已变化或正在使用，请刷新后重试 | Conflict |
| error_authentication | 无法解密：同步密钥不匹配或文件已损坏 | Authentication |
| error_incompatible_backup | 不支持的备份版本或加密格式 | IncompatibleBackup |
| error_invalid_backup | 备份内容不完整或不合法，原数据未改变 | InvalidBackup |
| error_invalid_recovery_code | 恢复码不合法，本机密钥未改变 | InvalidRecoveryCode |
| error_key_unavailable | 同步密钥不可用，请检查密钥文件或导入恢复码 | KeyUnavailable |
| error_io | 无法读写文件，请检查目录与权限 | Io |
| error_database | 无法读写本机数据库 | Database |
| error_incompatible_database | 数据库版本不兼容，原文件未改动 | IncompatibleDatabase |
| error_overflow | 金额合计超出整数范围，请检查数据 | Overflow |
| error_internal | 核心状态不可用，请重新打开 | Internal |

## 恢复码文件说明

保留原恢复码注释的含义和文件头，文件本身由核心生成，调用方不拼装密钥格式。

| key | 中文文案 | 用途 |
| --- | --- | --- |
| recovery_file_title | # SnowFold 同步密钥恢复码 | 注释首行 |
| recovery_file_fingerprint | # 密钥指纹：{fingerprint} | 注释指纹行 |
| recovery_file_warning | # 任何拿到这个文件的人都能解密你的加密备份；请离线保存，不要上传网盘或发给别人。 | 明文密钥说明 |
| recovery_file_instructions | # 换机或重装后，在「设置 · 加密备份 · 导入恢复码」中选择本文件即可。 | 恢复入口说明 |
