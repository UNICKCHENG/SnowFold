# 架构与目录

SnowFold 是完全离线的个人资产与订阅台账。四端（iPhone、Mac、安卓、Windows）共用一个 Rust 内核，各端只负责界面。

## 三层

```text
              apps/  各端应用（界面与打包）
   apple/ (SwiftUI)     android/ (Compose)     desktop/ (GPUI)
        │                     │                       │
        ├──────── 调用 ────────┼───────────────────────┤
        ▼                     ▼                       ▼
              core/  内核（数据规则、表结构、加密备份）

              shared/  通用资源（图标，各端按需取用）
```

- **内核 `core/`**：所有业务规则只在这里实现一次。各端不自己读写数据库，也不自己解析备份。`core/bindgen/` 是内核自带的生成器，把内核接口翻译成 Swift/Kotlin 调用代码。
- **各端应用 `apps/`**：每端一个目录，自成一体，包含自己的源码、工程、打包脚本。
- **通用资源 `shared/`**：多端共用、会被构建用到、但不是代码的文件。

各端界面用各自平台的原生框架（SwiftUI、Compose、GPUI），语言不同，界面组件代码不能跨端共用。能共用的是内核和资源。iPhone 与 Mac 共用的 SwiftUI 组件放在 `apps/apple/` 内部。

## 目录

| 目录 | 放什么 | 谁用它 |
| --- | --- | --- |
| `core/` | Rust 内核 `snowfold-core`：模型、存储、表结构与迁移、加密备份、给各端的接口、测试 | 所有端 |
| `core/bindgen/` | 调用代码生成器，与内核同版本，不进任何应用 | 苹果端、安卓端构建 |
| `apps/desktop/` | GPUI 桌面端（以 Windows 为主）：`src/` 源码、`build.rs`、`packaging/` 打包 | — |
| `apps/apple/` | SwiftUI 工程，iPhone 与 Mac 共用；`scripts/` 编译内核并生成 Swift 调用代码 | — |
| `apps/android/` | Gradle + Compose 工程；Gradle 任务编译内核并生成 Kotlin 调用代码 | — |
| `shared/icons/app/` | 应用图标 SVG 源 | 各端图标脚本 |
| `shared/icons/line/`、`shared/icons/fill/` | 界面图标 SVG（IconMCP，线条与实心两版） | 四端 |
| `shared/fonts/inter/` | Inter 可变字体（OFL） | 安卓、桌面 |
| `shared/design/` | 设计令牌（`tokens.css`、`tokens.json`）与设计稿源（`mock/`、`render_all.sh`） | 四端实现、设计评审 |
| `docs/` | 给人读的说明：本文、内核接口 `core-api.md`、开发指南 `development.md`、使用说明 `usage.md`、四端文案 `copy.md`、图标说明 `icons.md` | 开发者 |
| `.github/workflows/` | 持续集成（覆盖范围见 [development.md](development.md)） | — |

根目录只放 workspace 的 `Cargo.toml`、唯一的 `Cargo.lock`、`README.md`（入口说明）、`SECURITY.md`（安全说明，GitHub 约定放根目录）。

打包目录里的文本是**装进安装包给用户看的**，不是开发文档：

| 文件 | 去向 |
| --- | --- |
| `apps/desktop/packaging/NOTICE.txt` | 第三方组件声明；发版时作为独立附件随全部安装包发布 |
| `apps/desktop/packaging/windows/zip-readme.txt` | Windows 压缩包里的 `README.txt`（运行说明） |

GPUI 桌面端只发布 Windows 版；Mac 版由 `apps/apple/` 的 SwiftUI 应用提供。GPUI 在 Mac 上仍可编译运行，仅用于本地开发和跑桌面测试，不打包、不发布。

不入库的本地目录：`target/`（编译产物）、`dist/`（打包产物）、`apps/apple/Generated/`、各端构建目录。

## 约定

- **说明文档的位置**：入口 `README.md`、安全说明 `SECURITY.md`（GitHub 约定放根目录）、开发与使用说明在 `docs/`。随包交付的声明和运行说明留在对应 packaging 目录，有打包脚本消费，不搬动。其他目录只放会被编译、构建或打包用到的文件，不放单独的说明文档。
- 新增一端：在 `apps/` 下新建一个自成一体的目录，不往顶层放该端专用的文件。
- 改数据规则、表结构或备份格式：只改 `core/`，各端重新生成调用代码。表结构改动必须带迁移步骤。
- 某一端专用的资源放该端目录；两端及以上会用到的资源放 `shared/`。
- 依赖方向只有一个：`apps/` → `core/`、`apps/` → `shared/`。`core/` 不依赖任何一端。
- 四端文案目前是 `docs/copy.md` 这份人读的表，各端照它写进自己的字符串资源。以后做成由构建自动生成各端字符串时，数据源再放进 `shared/`。
