SnowFold for Windows

This portable package contains SnowFold.exe, embedded interface icons and bundled
SQLite. Extract the complete folder to a writable location before launching.

Runtime prerequisites:
- Windows 10/11 (x64 or ARM64, matching this package) and a working Direct3D 11
  graphics driver.
- No Visual C++ runtime install is needed; the C runtime is linked statically.

Double-click SnowFold.exe to launch. This build is not Authenticode signed;
Windows may display a SmartScreen reputation warning.

Data is stored separately from this folder, under:
  %LOCALAPPDATA%\SnowFold\data\snowfold.sqlite3
The actual path is visible in Settings. Keep this data directory when upgrading.
Do not start multiple instances against the same database.

For an isolated test in PowerShell:
  $env:SNOWFOLD_DATA_DIR = Join-Path $env:TEMP 'snowfold-windows-test'
  .\SnowFold.exe
Remove the environment override before normal use:
  Remove-Item Env:SNOWFOLD_DATA_DIR

Shortcuts: Ctrl+1/2/3 switches pages; Ctrl+N creates an entry; Ctrl+, opens
Settings; Enter saves; Esc cancels; Tab/Shift+Tab moves focus. Use the system
window close button or Alt+F4 to exit.

Backups: Settings can export/import native SnowFold JSON. Imports replace the
current business data after confirmation. The theme is a local setting.

Interface icons, the app icon and SQLite are embedded in SnowFold.exe. The
application does not require an Internet connection at runtime. The Visual
C++ runtime is not included.
