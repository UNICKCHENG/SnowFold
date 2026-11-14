//! 把应用图标嵌入 Windows 可执行文件。
//!
//! 图标源是 `shared/icons/app/` 下的共享 SVG，二进制 `apps/desktop/packaging/windows/AppIcon.ico`
//! 由 `apps/desktop/packaging/windows/make-icon.sh` 生成并入库；这里只在 Windows 目标上通过
//! `embed-resource` 把它编译成资源并链接进 `SnowFold.exe`。
//!
//! 找不到 `rc.exe` 时 `embed-resource` 会打印 warning 并跳过（开发机不装
//! Developer PowerShell 也能构建），CI 与 `package.ps1` 都要求 rc.exe 存在。

fn main() {
    #[cfg(windows)]
    {
        let manifest_dir = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR"));
        let icon = manifest_dir
            .join("packaging")
            .join("windows")
            .join("AppIcon.ico");
        println!("cargo:rerun-if-changed={}", icon.display());

        let out_dir =
            std::path::PathBuf::from(std::env::var_os("OUT_DIR").expect("cargo 未提供 OUT_DIR"));
        let resource = out_dir.join("snowfold-icon.rc");
        // 用绝对路径生成 .rc，避免 rc.exe 相对路径解析差异。
        std::fs::write(&resource, format!("1 ICON \"{}\"\n", icon.display()))
            .expect("写入 Windows 资源脚本失败");
        let _ = embed_resource::compile(&resource, embed_resource::NONE);
    }
}
