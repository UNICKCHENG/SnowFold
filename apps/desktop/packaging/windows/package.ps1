# Run on an x64 host with the matching x64 or x64-to-ARM64 MSVC tools.
[CmdletBinding()]
param(
    [ValidateSet('x64', 'arm64')]
    [string]$Arch = 'x64',
    [switch]$Standalone
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
    throw 'Windows is required. Run this script in x64 Developer PowerShell for Visual Studio.'
}

foreach ($tool in @('cargo.exe', 'rustc.exe', 'cl.exe', 'link.exe', 'rc.exe')) {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
        throw "Missing $tool. Install Rust MSVC, Visual Studio Desktop development with C++, and a Windows SDK; open the matching Developer PowerShell."
    }
}

# GPUI 0.2.2 otherwise only probes PATH and one hard-coded SDK version.
if ($env:GPUI_FXC_PATH) {
    if (-not (Test-Path -LiteralPath $env:GPUI_FXC_PATH -PathType Leaf)) {
        throw "GPUI_FXC_PATH does not point to a file: $env:GPUI_FXC_PATH"
    }
} else {
    $sdkBin = Join-Path ${env:ProgramFiles(x86)} 'Windows Kits\10\bin'
    $fxc = Get-ChildItem -LiteralPath $sdkBin -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '^\d+\.\d+\.\d+\.\d+$' } |
        Sort-Object { [version]$_.Name } -Descending |
        ForEach-Object { Join-Path $_.FullName 'x64\fxc.exe' } |
        Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
        Select-Object -First 1
    if (-not $fxc) {
        $command = Get-Command fxc.exe -ErrorAction SilentlyContinue
        if ($command) { $fxc = $command.Source }
    }
    if (-not $fxc) {
        throw 'fxc.exe was not found. Install a Windows 10/11 SDK or set GPUI_FXC_PATH to the absolute path of its x64 fxc.exe.'
    }
    $env:GPUI_FXC_PATH = $fxc
}
Write-Host "GPUI shader compiler: $env:GPUI_FXC_PATH"

$projectDir = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..\..'))
$target = if ($Arch -eq 'arm64') { 'aarch64-pc-windows-msvc' } else { 'x86_64-pc-windows-msvc' }
$previousRustflags = $env:RUSTFLAGS
Push-Location $projectDir
try {
    $hostInfo = & rustc -vV
    if ($LASTEXITCODE -ne 0) { throw 'rustc -vV failed.' }
    if (-not ($hostInfo -match '^host: x86_64-pc-windows-msvc$')) {
        throw 'Use the x86_64-pc-windows-msvc Rust host toolchain, not GNU or ARM64.'
    }
    if ($Standalone) {
        if ($env:CARGO_ENCODED_RUSTFLAGS) { throw 'Unset CARGO_ENCODED_RUSTFLAGS before building the standalone executable.' }
        # RUSTFLAGS takes precedence over target-specific flags; restore the caller's value below.
        $env:RUSTFLAGS = "$previousRustflags -C target-feature=+crt-static".Trim()
    }

    & cargo build --locked --release -p snowfold --target $target
    if ($LASTEXITCODE -ne 0) { throw 'Windows release build failed; no new package was generated.' }

    $metadataJson = & cargo metadata --locked --no-deps --format-version 1
    if ($LASTEXITCODE -ne 0) { throw 'cargo metadata failed.' }
    $metadata = ($metadataJson -join "`n") | ConvertFrom-Json
    $package = $metadata.packages | Where-Object { $_.name -eq 'snowfold' } | Select-Object -First 1
    $binary = Join-Path $metadata.target_directory "$target\release\snowfold.exe"
    if (-not (Test-Path -LiteralPath $binary -PathType Leaf)) { throw "Missing built executable: $binary" }

    $dist = Join-Path $projectDir 'dist'
    New-Item -ItemType Directory -Force -Path $dist | Out-Null
    if ($Standalone) {
        $exe = Join-Path $dist "SnowFold-windows-$Arch.exe"
        Copy-Item -LiteralPath $binary -Destination $exe -Force
        # Refuse a release that silently links the redistributable CRT dynamically.
        $dependencies = & dumpbin /dependents $exe
        if ($LASTEXITCODE -ne 0) { throw 'dumpbin dependency verification failed.' }
        if ($dependencies -match '(?i)(VCRUNTIME|MSVCP|MSVCR|ucrtbase|api-ms-win-crt)[^\s]*\.dll') {
            throw 'Standalone executable still depends on a dynamic CRT.'
        }
        Write-Host "Created $exe (static CRT verified)"
        return
    }

    $stage = Join-Path $dist "SnowFold-windows-$Arch"
    if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
    New-Item -ItemType Directory -Path $stage | Out-Null
    Copy-Item -LiteralPath $binary -Destination (Join-Path $stage 'SnowFold.exe')
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'zip-readme.txt') -Destination (Join-Path $stage 'README.txt')
    Copy-Item -LiteralPath (Join-Path $projectDir 'apps\desktop\packaging\NOTICE.txt') -Destination $stage
    [ordered]@{
        product = 'SnowFold'
        version = $package.version
        target = $target
        rustc = ($hostInfo -join "`n")
        shader_compiler = [IO.Path]::GetFileName($env:GPUI_FXC_PATH)
        executable_sha256 = (Get-FileHash -LiteralPath (Join-Path $stage 'SnowFold.exe') -Algorithm SHA256).Hash.ToLowerInvariant()
    } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $stage 'build-info.json') -Encoding UTF8

    $zipName = "SnowFold-windows-$Arch.zip"
    $zip = Join-Path $dist $zipName
    Compress-Archive -LiteralPath $stage -DestinationPath $zip -CompressionLevel Optimal -Force
    $hash = (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLowerInvariant()
    "$hash  $zipName" | Set-Content -LiteralPath (Join-Path $dist "$zipName.sha256") -Encoding ASCII
    Write-Host "Created $zip"
    Write-Host "SHA256 $hash"
} finally {
    $env:RUSTFLAGS = $previousRustflags
    Pop-Location
}
