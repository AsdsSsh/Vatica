# ═══════════════════════════════════════════════════════════════════════
# Vatica sidecar 一键打包脚本（迭代 8 I8-1）
# ═══════════════════════════════════════════════════════════════════════
# 用途：把"Rust 启动器 + 后端 fat jar + jlink 最小 JRE"三件套生成/刷新到
#       tauri build 需要的 staging 目录，之后跑 `npm run tauri build` 即出安装包。
#
# 产物布局（与 tauri.conf.json 的 externalBin/resources 声明一一对应）：
#   binaries/vatica-backend-x86_64-pc-windows-msvc.exe   ← Rust 启动器（externalBin）
#   backend-sidecar/vatica.jar                           ← Spring Boot fat jar（resource）
#   backend-sidecar/jre/                                 ← jlink 最小 JRE（resource 目录）
#   sidecar-stage/                                       ← 安装目录布局镜像（本地冒烟用）
#
# 用法（在 src-tauri 目录执行，PowerShell）：
#   .\package-sidecar.ps1                # 全部重新构建（后端含单测）
#   .\package-sidecar.ps1 -SkipTests     # 后端跳过单测（快速出包）
#   .\package-sidecar.ps1 -ForceJre      # 强制重建 jlink JRE（JDK 升级后需要）
# 依赖：JDK 21（含 jlink）、Maven、Rust 工具链。
# ═══════════════════════════════════════════════════════════════════════
param(
    [switch]$SkipTests,
    [switch]$ForceJre
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$repo = Resolve-Path (Join-Path $root '..\..')
$binaries = Join-Path $root 'binaries'
$sidecar = Join-Path $root 'backend-sidecar'
$stage = Join-Path $root 'sidecar-stage'
$triple = 'x86_64-pc-windows-msvc'
$sidecarExe = "vatica-backend-$triple.exe"

# ── 0. 前置检查：jlink / cargo / mvn ──────────────────────────────────
function Find-Jdk21 {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    $candidates += Get-ChildItem 'C:\Program Files\Java', 'C:\Program Files\Eclipse Adoptium' -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match 'jdk-?21' } | Select-Object -ExpandProperty FullName
    foreach ($c in $candidates) {
        $j = Join-Path $c 'bin\jlink.exe'
        if (Test-Path $j) { return $j }
    }
    throw '未找到 JDK 21 的 jlink.exe。请安装 JDK 21 或设置 JAVA_HOME。'
}

$jlink = $null
if ($ForceJre -or -not (Test-Path (Join-Path $sidecar 'jre\bin\java.exe'))) { $jlink = Find-Jdk21 }
if (-not (Get-Command cargo -ErrorAction SilentlyContinue)) { throw '未找到 cargo（Rust 工具链）。请先安装 rustup。' }
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) { throw '未找到 mvn。请先安装 Maven 并加入 PATH。' }

# ── 1. Rust 启动器 ────────────────────────────────────────────────────
Write-Host '== 1/4 构建 Rust 启动器（launcher）==' -ForegroundColor Cyan
Push-Location (Join-Path $root 'launcher')
try { cargo build --release; if ($LASTEXITCODE -ne 0) { throw 'cargo build 失败' } } finally { Pop-Location }

New-Item -ItemType Directory -Force $binaries | Out-Null
Copy-Item (Join-Path $root 'launcher\target\release\vatica-backend.exe') (Join-Path $binaries $sidecarExe) -Force
Write-Host "启动器 → binaries\$sidecarExe"

# ── 2. 后端 fat jar ───────────────────────────────────────────────────
Write-Host '== 2/4 构建后端 fat jar ==' -ForegroundColor Cyan
Push-Location (Join-Path $repo 'backend')
try {
    if ($SkipTests) { mvn -q clean package -DskipTests } else { mvn -q clean package }
    if ($LASTEXITCODE -ne 0) { throw 'mvn package 失败' }
} finally { Pop-Location }

$jar = Join-Path $repo 'backend\target\vatica-0.0.1-SNAPSHOT.jar'
if (-not (Test-Path $jar)) { throw "找不到 jar：$jar" }
New-Item -ItemType Directory -Force $sidecar | Out-Null
Copy-Item $jar (Join-Path $sidecar 'vatica.jar') -Force
Write-Host 'fat jar → backend-sidecar\vatica.jar'

# ── 3. jlink 最小 JRE（已存在且未指定 -ForceJre 则跳过）──────────────
$jreDir = Join-Path $sidecar 'jre'
if ($jlink) {
    Write-Host '== 3/4 jlink 生成最小 JRE ==' -ForegroundColor Cyan
    if (Test-Path $jreDir) { Remove-Item $jreDir -Recurse -Force }
    # 模块清单 = 打包 JRE 实测所需（release 文件归档）；缺模块启动时会报 ModuleNotFoundException
    $modules = 'java.base,java.compiler,java.datatransfer,java.xml,java.prefs,java.desktop,' +
        'java.instrument,java.logging,java.management,java.security.sasl,java.naming,' +
        'java.net.http,java.security.jgss,java.transaction.xa,java.sql,jdk.crypto.ec,jdk.unsupported'
    & $jlink --strip-debug --no-header-files --no-man-pages `
        --add-modules $modules --output $jreDir
    if ($LASTEXITCODE -ne 0) { throw 'jlink 失败' }
} else {
    Write-Host '== 3/4 跳过 jlink（JRE 已存在，-ForceJre 可强制重建）==' -ForegroundColor DarkGray
}

# ── 4. 镜像到 sidecar-stage（安装目录布局，本地冒烟用）───────────────
Write-Host '== 4/4 镜像 sidecar-stage ==' -ForegroundColor Cyan
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory -Force $stage | Out-Null
Copy-Item (Join-Path $binaries $sidecarExe) (Join-Path $stage $sidecarExe)
Copy-Item (Join-Path $sidecar 'vatica.jar') $stage
Copy-Item $jreDir (Join-Path $stage 'jre') -Recurse

Write-Host ''
Write-Host 'sidecar 三件套就绪。下一步：npm run tauri build 出安装包；' -ForegroundColor Green
Write-Host '本地冒烟：直接运行 sidecar-stage\vatica-backend-x86_64-pc-windows-msvc.exe（工作目录切到 %APPDATA%\Vatica）。'
