# SnowFold

SnowFold 是完全离线的资产与订阅台账：一份共享的 Rust 数据核心，iPhone 与 Mac 用 SwiftUI，安卓用 Jetpack Compose，Windows 用 GPUI。数据只存本机 SQLite，运行时不联网，首次启动为空库。

<!-- platform-status: 由集成时按验收结果更新 -->

| 平台 | 界面框架 | 现状 |
| --- | --- | --- |
| Windows 桌面 | GPUI | 功能完整，为主要桌面目标 |
| iOS / iPadOS | SwiftUI | 功能完整，窄屏与 iPad 分栏布局 |
| macOS | SwiftUI | 功能完整，侧边栏布局 |
| Android | Compose | 功能完整，窄屏与平板列表-详情布局 |
| macOS（GPUI 兼容链） | GPUI | 维护中，待 SwiftUI Mac 版验收后退役 |

四端功能一致：资产、订阅、备份与统计，操作说明见 [docs/usage.md](docs/usage.md)。视觉以桌面端为准，见 [docs/design.md](docs/design.md)；移动端布局见 [docs/mobile-layout.md](docs/mobile-layout.md)。

## 如何构建

需要 Rust 1.96.0；首次构建下载依赖需要网络，应用运行不需要。仓库根目录的通用检查：

```sh
cargo fmt --check
cargo check --locked --workspace
cargo test --locked --workspace
```

各端最短构建入口，完整工具安装、签名与打包步骤见 [docs/development.md](docs/development.md)：

- **Windows 桌面**（宿主 Windows，仓库根目录的 Developer PowerShell）：
  `powershell -NoProfile -ExecutionPolicy Bypass -File .\apps\desktop\packaging\windows\package.ps1`
- **iPhone / Mac**（宿主 macOS，仓库根目录）：`./apps/apple/scripts/build-core.sh`，然后打开 `apps/apple/SnowFold.xcodeproj`
- **安卓**（宿主 macOS/Linux/Windows，工作目录 `apps/android/`）：`./gradlew assembleDebug`

## 仓库地图

- `core/`：四端共用的 Rust 数据核心（模型、存储、表结构、加密备份、宿主接口）
- `apps/`：desktop（GPUI）、apple（SwiftUI）、android（Compose），每端自成一体
- `shared/`：各端通用的资源（应用与界面图标 SVG）
- `docs/`：全部说明文档

分层、目录规则、生成产物边界与打包文本归属见 [docs/architecture.md](docs/architecture.md)。

## 去哪里看文档

| 文档 | 内容 |
| --- | --- |
| [docs/architecture.md](docs/architecture.md) | 分层与目录规则，源码与生成产物的边界 |
| [docs/core-api.md](docs/core-api.md) | 共享核心接口契约：类型、错误语义、备份版本兼容 |
| [docs/development.md](docs/development.md) | 各端构建、绑定生成、打包、CI 覆盖与验收 |
| [docs/usage.md](docs/usage.md) | 桌面端使用说明：数据目录、快捷键、备份、换机恢复 |
| [docs/copy.md](docs/copy.md) | 三端共用的中文文案契约 |
| [docs/icons.md](docs/icons.md) | 图标矢量源与生成链 |
| [SECURITY.md](SECURITY.md) | 加密备份的威胁模型与密钥保护 |

本机数据请留意：GPUI 桌面端每次启动会在数据目录保存一份明文 JSON 备份（最多 10 份），不要把数据目录放进云盘同步；需要上传网盘或发给他人时，使用设置里的加密备份（AES-256-GCM 密封包）。详见 [SECURITY.md](SECURITY.md) 与 [docs/usage.md](docs/usage.md)。
