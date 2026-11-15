# 图标

## 应用图标

`shared/icons/app/` 是**唯一**的应用图标矢量源。目前从它生成应用图标的只有 GPUI 桌面端（Windows）；SwiftUI 与 Compose 端的应用图标尚未接入这条生成链，接入前不以本文档验收原生端图标。

| 文件 | 用途 |
| --- | --- |
| `icon-1024.svg` | 1024×1024 主图标；用于 128px 以上尺寸、App Store / 官网展示 |
| `icon-small.svg` | 小尺寸优化版；用于 16–64px（Windows 16/32/48/64），保证任务栏和资源管理器里不糊 |

生成产物是桌面端直接引用的二进制资源，均已入库：

- Windows：`apps/desktop/packaging/windows/AppIcon.ico` ← `sh apps/desktop/packaging/windows/make-icon.sh`（需要 librsvg + python3），
  再由 `apps/desktop/build.rs` 通过 `embed-resource` 嵌入 `SnowFold.exe`

改图流程（GPUI 桌面端）：先改 `shared/icons/app/` 的 SVG，再运行 Windows 的 `make-icon.sh`，最后用 `apps/desktop/packaging/windows/package.ps1` 重新打包。

## 界面图标

四端界面图标只用一套：IconMCP（MIT，许可见 `shared/icons/LICENSE-IconMCP.txt`），不用 SF Symbols、Material Icons、Lucide 等系统或第三方图标库。

- `shared/icons/line/`：线条版，默认状态用。
- `shared/icons/fill/`：同名实心版，导航选中态、强调态用（plus、check、refresh、undo、trending-up、chevron 等本身没有实心差异，两版相同）。
- 尺寸只有 16（行内）、20（工具栏、列表）、24（移动导航）三档，空状态另用 48；描边粗细随尺寸等比，用途与名称对照见 [design.md](design.md) §3。
- 各端从这两个目录生成自己的资源：苹果进 Asset Catalog（Preserve Vector Data + Template），安卓构建时转 vector drawable，桌面编译时嵌进程序。新增图标先放进这两个目录，再在 design.md §3 登记用途。
