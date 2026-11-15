# SnowFold

完全离线的资产与订阅台账：一个 Rust 内核 `core/`，四端原生界面（`apps/apple` SwiftUI 管 iPhone/iPad/Mac，`apps/android` Compose，`apps/desktop` GPUI 管 Windows）。仓库地图与构建入口见 [README.md](README.md)；目录名 `billfold` 是旧名，产品名、标识与新文件一律用 SnowFold / `app.snowfold`。

## 改代码前先认归属

- **业务规则、表结构、备份格式只写在 `core/`**，接口唯一来源是 `core/src/ffi.rs`。各端只做界面：从不直接读写 SQLite，从不自己解析备份，金额显示一律经核心 `format_money`。表结构改动带迁移步骤。契约见 [docs/core-api.md](docs/core-api.md)。
- **Swift/Kotlin 绑定是生成物**：由 `core/bindgen` 从刚编译的内核生成，不入库、不手写。各端接入内核的适配点：苹果 `apps/apple/Sources/Core/Store.swift`，安卓 `apps/android/app/src/main/java/app/snowfold/data/Core.kt`，桌面直接依赖 Rust API。
- 依赖方向只有 `apps/ → core/`、`apps/ → shared/`；两端及以上共用的非代码资源放 `shared/`。新增一端就在 `apps/` 下新建自成一体的目录。分层与目录规则见 [docs/architecture.md](docs/architecture.md)。

## 冻结的规范

- [docs/copy.md](docs/copy.md)（四端中文文案）和 [docs/design.md](docs/design.md)（视觉）是**冻结**的，只有用户能改。实现照抄；文案或数值看起来不对时，向用户提出，由用户改表。
- 视觉基准是 [docs/design.md](docs/design.md)（桌面以 Mac 为准、Windows 照做；移动以 iOS 为准、安卓照做）与它的设计稿源 `shared/design/`（令牌 `tokens.css` / `tokens.json`，设计稿用 `render_all.sh` 生成）；手机与平板布局见 [docs/mobile-layout.md](docs/mobile-layout.md)。界面图标只用 `shared/icons/line/`（线条）与 `shared/icons/fill/`（实心）里的 IconMCP 图标，不用系统图标库。
- 写给别人照做的规范（布局、对齐清单等），下发前逐条对照桌面端源码与核心接口核实。

## 构建与检查

根目录通用检查（`cargo fmt --check`、`cargo check --locked --workspace`、`cargo test --locked --workspace`）与各端命令见 [docs/development.md](docs/development.md)。容易踩的点：

- **改了 `core/` 之后**：苹果端要重跑 `./apps/apple/scripts/build-core.sh`（Xcode 构建不会自动编译 Rust）；安卓端 `./gradlew assembleDebug` 会自动重编内核和绑定。
- **`libsnowfold_core` 产物文件名不带哈希**。多个目录共用同一个 `CARGO_TARGET_DIR` 时并行编译会互相覆盖，表现为凭空的「找不到方法/类型」编译错。并行工作各用各的 target，或错开编译内核。
- 手动运行桌面端用独立数据目录：`SNOWFOLD_DATA_DIR=<空目录>`，用户的默认库保持原样。
- 改 `.github/workflows/` 时，action 一律钉提交 SHA；CI 按改动路径决定测哪几端，`CI Summary` 是唯一必需检查。

## 测试与验收证据

- GPUI 0.2.2 的 `simulate_keystrokes` / `dispatch_keystroke` 只发 KeyDown，而 Div 的键盘点击在 KeyUp 触发。测试里补发完整的按键事件；生产代码保持 KeyUp 触发（加 KeyDown 回调会让真机双次提交）。
- 事件模拟测试、真实进程启动、重启后持久化、截图、中文 IME 是五种不同的证据，报告时分别列明各自做了哪些。
- 模拟器与设备只操作自己创建的（苹果截图脚本只认名字含 `snowfold-apple` 的模拟器）；收尾时关掉自己开的模拟器，以 `adb devices` 为空作为清理证据。
- 日志、截图、验收记录、过程笔记放 `.fullstack/`（已忽略，不入库）。`.fullstack/lessons.md` 记录可复用的教训，开始较大的任务前先读一遍。

## 文档与提交

- 说明文档只放三处：根 `README.md`、根 `SECURITY.md`、`docs/`。`apps/desktop/packaging/` 下的 `NOTICE.txt`、`zip-readme.txt` 是随安装包交付给用户的文本，由打包脚本读取，留在原处。
- 文档、注释和提交信息用中文；提交标题格式 `类型(范围): 摘要`，如 `feat(android): …`、`fix: …`、`ci: …`。
- 提交由用户自己做：改动留在工作区，汇报改了哪些文件。只有用户明确要求提 PR 时，才在 PR 分支上提交并推送。
