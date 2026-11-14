# 开发指南

从干净检出到各端构建、绑定生成、打包与验收的完整操作。各端最短入口见 [README](../README.md)；构建是否属于某次验收，以对应发布记录为准，本文只写稳定的命令与检查。

## 工具链与 workspace 约定

- Rust **1.96.0**；项目是 Cargo workspace，根目录保留唯一 `Cargo.lock`。
- 共享核心 `snowfold-core` 不依赖 GPUI。桌面程序直接依赖核心 Rust API；SwiftUI 与 Compose 使用 UniFFI **0.32.2** 生成的绑定。
- Cargo 产物目录：未设置 `CARGO_TARGET_DIR` 时，使用当前仓库自己的 `target/`。多个工作区默认互不共享产物目录；若特意让多个目录共享同一个 `CARGO_TARGET_DIR`，同一时刻只允许一个目录在编译核心——核心产物 `libsnowfold_core.{rlib,a,dylib}` 文件名固定无哈希，并行编译会互相覆盖，导致读入错误的核心库。
- 首次构建下载依赖需要网络；应用运行不需要。

通用检查（宿主 macOS/Linux/Windows，仓库根目录）：

```sh
cargo fmt --check
cargo check --locked --workspace
cargo test --locked --workspace
```

## Windows 桌面（GPUI）

目标 **Windows 10/11 x64（`x86_64-pc-windows-msvc`）与 arm64（`aarch64-pc-windows-msvc`，`-Arch arm64`）**，在 Windows 本机构建，生成单文件便携 exe 与 ZIP；不提供安装器。

构建机需要 Rust 1.96.0 MSVC 工具链、Visual Studio 2022 / Build Tools 的 **Desktop development with C++** 工作负载，以及 Windows 10/11 SDK（包含 `rc.exe` 和 `fxc.exe`）。请使用 x64 Developer PowerShell for Visual Studio（工作目录：仓库根目录）：

```powershell
rustup toolchain install 1.96.0-x86_64-pc-windows-msvc --profile minimal --component rustfmt
rustup override set 1.96.0-x86_64-pc-windows-msvc
rustup target add x86_64-pc-windows-msvc
cargo fmt --check
cargo check --locked --workspace
cargo test --locked --workspace
powershell -NoProfile -ExecutionPolicy Bypass -File .\apps\desktop\packaging\windows\package.ps1
```

最后一条命令只为当前脚本进程设置执行策略，不修改机器策略；也可以在允许本地脚本的 Developer PowerShell 中直接运行 `./apps/desktop/packaging/windows/package.ps1`。若组织策略禁止脚本，需要遵守本机构建机的策略。

打包脚本会验证 MSVC 主机工具链及编译工具，查找最新已安装 SDK 中的 x64 `fxc.exe`，将路径传给 GPUI，再以锁定依赖构建 release。也可事先设置 `$env:GPUI_FXC_PATH` 指向指定编译器。该检查不能省略：GPUI 0.2.2 在 Windows release 构建中编译 HLSL，macOS 的 `runtime_shaders` 特性不会免除这一步。

输出：

- `dist/SnowFold-windows-<x64|arm64>.exe`：单文件便携程序，即 Release 资产。
- `dist/SnowFold-windows-<x64|arm64>.zip` 与同名 `.sha256`：包含 `SnowFold.exe`、运行说明、资源说明及版本/目标/二进制哈希信息，供本地分发。

程序静态链接 CRT，运行端无需安装 VC++ 运行库，只需可用的 Direct3D 11 显卡驱动；SQLite 和图标已嵌入程序。当前 Windows 程序不做 Authenticode 签名，可能出现 SmartScreen 提示。

开发时快速运行（macOS/Linux/Windows 开发机，仓库根目录）：

```sh
cargo run --locked -p snowfold
cargo build --locked --release -p snowfold
```

## iPhone 与 Mac（SwiftUI）

`apps/apple/` 是 iPhone、iPad 与 Mac 共用 SwiftUI 源码的工程，最低版本 **iOS 17 / macOS 14**。当前原生库提供 iOS 真机 arm64、iOS 模拟器 arm64、macOS arm64 三个切片。宿主 macOS；需要完整 Xcode、Rust 工具链与 XcodeGen，切换 Xcode 版本时先确认 `xcode-select -p` 指向完整 Xcode。

工程使用 XcodeGen：提交 `project.yml` 而不提交生成的 `.xcodeproj`，双平台目标和共享源码的设置集中维护。

```sh
brew install xcodegen
rustup target add aarch64-apple-ios aarch64-apple-ios-sim aarch64-apple-darwin
./apps/apple/scripts/build-core.sh
open apps/apple/SnowFold.xcodeproj
```

构建脚本（仓库根目录运行）从零完成三切片 release 编译、用仓库内同版本 `uniffi-bindgen` 从实际核心库生成 Swift 绑定、打包 XCFramework 和生成 Xcode 工程。产物在 `apps/apple/Generated/`，临时文件与 DerivedData 放 `apps/apple/.build/`，均不入库。核心与生成器的 manifest 路径只在脚本开头的 `CORE_MANIFEST`、`BINDGEN_MANIFEST` 两处，目录迁移时改这两个变量。

接口变动后重跑同一脚本，再构建两个 app；若调用签名变化，适配点集中在 `Sources/OverviewModel.swift` 的 `open` 和 `overview`。构建不会自动执行 Cargo，避免每次 SwiftUI 迭代都重复编译三套 Rust 库。

从仓库根目录构建（无需开发者账号）：

```sh
xcodebuild -project apps/apple/SnowFold.xcodeproj -scheme SnowFold-iOS \
  -configuration Debug -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath apps/apple/.build/ios ARCHS=arm64 ONLY_ACTIVE_ARCH=YES CODE_SIGNING_ALLOWED=NO build
xcodebuild -project apps/apple/SnowFold.xcodeproj -scheme SnowFold-macOS \
  -configuration Debug -destination 'platform=macOS,arch=arm64' \
  -derivedDataPath apps/apple/.build/macos build
open apps/apple/.build/macos/Build/Products/Debug/SnowFold.app
```

Xcode 中选择 `SnowFold-iOS` 或 `SnowFold-macOS` scheme 运行。两者 Bundle ID 均为 `app.snowfold`；Mac 默认 ad-hoc 签名并开启 App Sandbox，iOS 模拟器构建关闭签名。iOS 真机切片已打包，真机安装需另选开发团队签名。

两个 app 均用系统返回的沙盒 `Application Support/SnowFold/` 保存核心数据。Mac 为 `~/Library/Containers/app.snowfold/Data/Library/Application Support/SnowFold/`，与 GPUI 桌面库分开。

## 安卓（Compose）

工程在 `apps/android/`，applicationId `app.snowfold`。工具链固定为 AGP **9.5.0**、Gradle **9.8.2**、Kotlin / Compose compiler **2.4.21**、Compose BOM **2026.09.00**；`compileSdk` / `targetSdk` **37**、`minSdk` **26**；NDK **30.0.16248370**、cargo-ndk **4.1.2**。AGP 9.5 支持 API 37，Gradle 使用配套版本；AGP 内置 Kotlin 与 Compose compiler 显式使用同一 Kotlin 版本，BOM 统一 Compose 稳定依赖。可直接使用系统 `java.time` 生成核心要求的本地自然日，不增加日期反糖配置。

构建机需要 JDK 21、Android SDK Platform 37.0、Build Tools 37.0.0、上述 NDK 与 Rust（宿主 macOS/Linux/Windows）。项目自带 Gradle Wrapper，不需要全局 Gradle。

将 `JAVA_HOME` 指向本机 JDK 21 安装目录、`ANDROID_HOME` 指向 Android SDK 目录，然后（工作目录：`apps/android/`）：

```sh
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk --version 4.1.2 --locked
./gradlew clean assembleDebug
./gradlew lintDebug
```

`assembleDebug` 自动执行 `:app:buildRust`（锁定 Cargo 依赖，release 编译 `arm64-v8a` 与 `x86_64`）和 `:app:generateCoreBindings`（使用 workspace 中锁定的 UniFFI 生成器）。原生库输出至 `app/build/generated/jniLibs/<ABI>/libsnowfold_core.so`，Kotlin 输出至 `app/build/generated/uniffi/kotlin/`，均直接纳入 APK 编译，均不入库。JNA 使用 Android AAR，APK 同时包含其 `libjnidispatch.so`。APK 在 `app/build/outputs/apk/debug/app-debug.apk`，使用 debug 签名。

核心接口改变后重新运行 `./gradlew assembleDebug` 即可更新库与绑定。仓库根目录由工程位置推导；内核与生成器路径只在 `apps/android/gradle.properties` 的 `rust.corePath`（`core`）/ `rust.bindgenPath`（`core/bindgen`）定义。Cargo 产物目录取环境变量 `CARGO_TARGET_DIR`，未设置时用仓库根目录的 `target/`。

安装到指定模拟器或设备（工作目录：`apps/android/`；`<serial>` 换成 `adb devices` 的实际序列号）：

```sh
"$ANDROID_HOME/platform-tools/adb" -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
"$ANDROID_HOME/platform-tools/adb" -s <serial> shell am start -W -n app.snowfold/.MainActivity
```

## 绑定生成与手动调试

Swift/Kotlin 绑定不入库：各端构建时用内核自带的生成器 `core/bindgen` 从刚编译的内核生成，核心是唯一来源。生成代码的契约与产物归属见 [core-api.md](core-api.md#生成与接入)。

手动生成仅用于调试绑定内容（宿主 macOS，仓库根目录）：

```sh
cargo build --locked -p snowfold-core
cargo run --locked -p snowfold-uniffi-bindgen --bin uniffi-bindgen -- generate \
  --library target/debug/libsnowfold_core.dylib --language swift --out-dir /tmp/snowfold-swift --no-format
```

示例中的 `libsnowfold_core.dylib` 是 macOS 调试库名，其他平台换成对应产物路径。UniFFI 0.32.2 的 `--library` 标记已 deprecated，但当前生成链仍依赖它；升级生成器前先验证该用法仍可用。

## GPUI macOS 兼容链（待退役）

GPUI 桌面同时提供 macOS 打包：`apps/desktop/packaging/macos/` 仍在维护并在 CI 中产出应用包。按既定决策，SwiftUI Mac 版完成验收后，此链路（`package.sh`、`make-icon.sh`、`Info.plist`、`AppIcon.icns` 及相关 CI 打包步骤）成套退役；退役时保留仍被 Windows 使用的 `NOTICE.txt`、共享 SVG 与 ICO。

打包（宿主 macOS，仓库根目录）：

```sh
sh apps/desktop/packaging/macos/package.sh
```

脚本使用锁定依赖构建 release，复制 Info.plist、应用图标与本地资源说明，执行 ad-hoc 签名及验证，再生成 ZIP。GPUI Component 的 Lucide SVG 通过 `gpui-component-assets` 编入二进制，SQLite 也随二进制编译；应用没有资源 CDN 或运行时图标请求。

产物为 `dist/SnowFold.app` 与 `dist/SnowFold-macos.zip`（默认按本机架构，ad-hoc 签名，无 Developer ID 签名或公证）。加 `--universal` 时编译 Intel 与 Apple 芯片两个架构合成通用包，并额外生成 `dist/SnowFold-macos.dmg`（发版用）：

```sh
sh apps/desktop/packaging/macos/package.sh --universal
```

启动：

```sh
open dist/SnowFold.app
```

默认数据库为 `~/Library/Application Support/SnowFold/snowfold.sqlite3`。设置中显示实际路径。可用 `SNOWFOLD_DATA_DIR` 使用独立目录，验收与正常数据应分开：

```sh
SNOWFOLD_DATA_DIR=/tmp/snowfold-dev open -n dist/SnowFold.app
```

`open -n` 会创建新实例，避免多个实例同时编辑同一个数据库。应用关闭最后一个窗口或 Cmd/Ctrl+Q 后退出。

## Windows 实机验收

打包产出不等于验收。发布维护者在 CI 产包后，还需在 Windows 桌面实机完成以下检查；首次验收请在独立目录运行，避免使用正常业务库：

```powershell
$env:SNOWFOLD_DATA_DIR = <独立的空目录>
.\SnowFold.exe
# 关闭应用后移除当前 PowerShell 的目录覆盖：
Remove-Item Env:SNOWFOLD_DATA_DIR
```

- 正常开窗与关闭，浅色/深色、960×720 最窄窗口及高 DPI 显示。
- 中文 IME 输入资产/订阅名称，Tab、Shift+Tab、Enter、Esc 和 Ctrl+1/2/3/N 的操作。
- 修改余额、修正历史及撤销，订阅日期日历、币种、周期、暂停与续费。
- JSON 导出/导入的系统文件对话框与覆盖确认；重开后数据和主题保持一致。
- 对照 macOS 的统计口径与示例总额，确认 SQLite 完整性；在未安装开发工具与 VC++ 运行库的干净机器上确认 exe 能直接启动。

## 测试覆盖

`cargo test --locked --workspace`（仓库根目录）覆盖以下领域；发布材料如需具体数量，写入对应版本的验收记录并注明代码状态：

- 核心与存储：金额精度、日历续费与统计、数据库约束、事务回滚、历史同步及撤销回归、删除恢复、示例总额、JSON 往返、备份保留。
- 加密：往返、nonce 不复用、错钥/错 AAD/逐字节篡改/截断/版本降级、密钥文件权限与恢复码、符号链接不跟随、加密快照往返与失败不落库、导出时间/设备标识校验、数据库版本不兼容拒绝、未知备份版本拒绝、密钥替换确认。
- 桌面：GPUI 输入事件驱动的资产/订阅录入、余额撤销、币种/周期、暂停恢复、主题、导出、加密备份导出、清空取消/确认、示例恢复与键盘操作。

上游 `block 0.1.6` 有 future-incompatibility 提示，不影响当前构建。

## CI 与发版

工作流配置见 `.github/workflows/`。

推送与 PR 按改动路径决定检查范围：

- 改 `core/`、`shared/`、根 `Cargo.toml`、`Cargo.lock`、`rust-toolchain*` 或 `.github/workflows/`：四端全测（桌面 macOS+Windows、苹果、安卓）。
- 只改 `apps/desktop/`：只测桌面（macOS、Windows）。
- 只改 `apps/apple/`：只测苹果（内核 XCFramework、iOS 模拟器与 macOS 构建）。
- 只改 `apps/android/`：只测安卓（含 Gradle Wrapper 官方版本校验）。
- 只改 `docs/`、根 README 等说明文档：不跑构建与测试。

实现上由一个判断改动的 job 算出受影响的端，各端 job 按结果跳过或执行；末尾的汇总 job 把跳过视为通过，作为分支保护的唯一必需检查。GitHub Actions 的 action 一律钉提交 SHA。

分支保护只需把 **`CI Summary`** 设为必需检查（首次真实运行后才会出现）。

打 `v*` tag 发版不按路径过滤，始终全量构建，tag 必须与 workspace 版本一致。Release 资产直接是可运行文件：

| 资产 | 说明 |
| --- | --- |
| `SnowFold-windows-x64.exe`、`SnowFold-windows-arm64.exe` | 单文件便携程序，静态链接 CRT，无需 VC++ 运行库；arm64 在 x64 runner 上交叉编译，首次实机验证前按未验证产物对待 |
| `SnowFold-macos.dmg` | Intel + Apple 芯片通用包，打开后拖入「应用程序」；ad-hoc 签名，未公证 |
| `SnowFold-android.apk` | release 签名 APK |
| `NOTICE.txt`、`SHA256SUMS` | 第三方声明；全部资产的校验和 |

安卓签名从仓库 Settings → Secrets and variables → Actions → Repository secrets 读取 `ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`，缺任一个发版失败并报出缺哪个。iOS 只在 CI 中构建，不发布（需要苹果开发者账号签名）。

tag 构建直接发布为正式版（非草稿）；tag 含 `-` 时标为预发布。说明由 GitHub 自动生成（含 New Contributors），再追加本版与上个 tag 之间全部提交作者的贡献者名单。
