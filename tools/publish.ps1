# 发布脚本：把构建产物发到 GitHub Releases（可上传 >25MB，网页上传上限只有 25MB）
#
# 用法：
#   pwsh -File tools/publish.ps1                     # 用 gradle.properties 的版本，标签 v<version>
#   pwsh -File tools/publish.ps1 -Version 0.3.3
#   pwsh -File tools/publish.ps1 -Draft              # 先发成草稿
#   pwsh -File tools/publish.ps1 -SkipBuild          # 直接用 build/libs 里现成的 jar
#
# 凭据：从 Git Credential Manager 里读（git credential fill），不需要手输 token。
# 前提：本机已成功 push 过这个仓库，或设置过 GITHUB_TOKEN 环境变量。

[CmdletBinding()]
param(
    [string]$Version,
    [string]$Repo = 'nightcat630/minetendo-craftstation',
    [switch]$Draft,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Push-Location $root
try {
    # ── 版本 ──
    if (-not $Version) {
        $props = Get-Content 'gradle.properties' -Encoding UTF8
        $line = $props | Where-Object { $_ -match '^mod_version=' } | Select-Object -First 1
        $Version = ($line -split '=', 2)[1].Trim()
    }
    $tag = "v$Version"
    $jarName = "minetendo-craftstation-$Version.jar"
    Write-Host "== 发布 $tag ==" -ForegroundColor Cyan

    # ── 构建 ──
    if (-not $SkipBuild) {
        Write-Host '-- gradlew build' -ForegroundColor Gray
        & ./gradlew build --console=plain
        if ($LASTEXITCODE -ne 0) { throw "构建失败（exit $LASTEXITCODE）" }
    }
    $jar = Join-Path $root "build/libs/$jarName"
    if (-not (Test-Path $jar)) { throw "找不到产物: $jar" }
    $sizeMB = [math]::Round((Get-Item $jar).Length / 1MB)
    Write-Host "   产物 $jarName  ${sizeMB}MB" -ForegroundColor Gray

    # ── 校验和 ──
    $hash = (Get-FileHash $jar -Algorithm SHA256).Hash.ToLower()
    $sumsPath = Join-Path $env:TEMP 'SHA256SUMS.txt'
    [System.IO.File]::WriteAllText($sumsPath, "$hash  $jarName`n", (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "   SHA256 $hash" -ForegroundColor Gray

    # ── 凭据 ──
    $token = $env:GITHUB_TOKEN
    if (-not $token) {
        $in = Join-Path $env:TEMP 'publish_cred_in.txt'
        [System.IO.File]::WriteAllText($in, "protocol=https`nhost=github.com`n`n", (New-Object System.Text.UTF8Encoding($false)))
        $out = & cmd /c ('git credential fill < "' + $in + '" 2>&1')
        Remove-Item $in -Force -ErrorAction SilentlyContinue
        $token = ($out | Where-Object { $_ -match '^password=' } | Select-Object -First 1) -replace '^password=', ''
    }
    if (-not $token) { throw '没有可用的 GitHub 凭据：先 push 一次或设置 GITHUB_TOKEN' }
    $auth = @{ Authorization = "token $token"; Accept = 'application/vnd.github+json'; 'User-Agent' = 'publish-script' }

    # ── 建/取 release ──
    $existing = $null
    try {
        $existing = (Invoke-WebRequest -Uri "https://api.github.com/repos/$Repo/releases/tags/$tag" `
                -Headers $auth -UseBasicParsing -TimeoutSec 60).Content | ConvertFrom-Json
    } catch { }

    $notes = @"
NeoForge 1.21.1 / Minecraft 1.21.1 / Java 21

六机种 libretro 核心已内置，把 jar 丢进 mods/ 即可运行。

- 3DS (Azahar) / NDS (自研核心) / GBA (mGBA) / SFC (Snes9x) / PS2 (PCSX2) / PSP (PPSSPP)
- 世界内电视投屏（位置音频、单电视单主机）
- 掌机全屏界面（GBA / 3DS / NDS / PSP，PSP 双摇杆）
- 3DS .cia 自动解密为 .app
- 首次启动自解包核心、PS2 BIOS、PPSSPP 资源

SHA256（$jarName）：
$hash

本包不含任何游戏 ROM。
"@

    if ($existing) {
        Write-Host '   已存在同名 release，复用' -ForegroundColor Gray
        $rel = $existing
    } else {
        $payload = @{ tag_name = $tag; name = "Minetendo & CraftStation $Version"; draft = [bool]$Draft; prerelease = $false; body = $notes } |
            ConvertTo-Json -Depth 5
        $jsonFile = Join-Path $env:TEMP 'publish_release.json'
        [System.IO.File]::WriteAllText($jsonFile, $payload, (New-Object System.Text.UTF8Encoding($false)))
        $rel = (& curl.exe -s -X POST -H "Authorization: token $token" -H 'Accept: application/vnd.github+json' `
                -H 'User-Agent: publish-script' -H 'Content-Type: application/json' `
                --data-binary "@$jsonFile" "https://api.github.com/repos/$Repo/releases") | ConvertFrom-Json
        Remove-Item $jsonFile -Force -ErrorAction SilentlyContinue
        if (-not $rel.id) { throw "创建 release 失败" }
        Write-Host "   已创建 release id=$($rel.id)" -ForegroundColor Gray
    }

    # ── 上传资产（同名先删）──
    foreach ($asset in @($rel.assets)) {
        if ($asset.name -eq $jarName -or $asset.name -eq 'SHA256SUMS.txt') {
            & curl.exe -s -o NUL -X DELETE -H "Authorization: token $token" -H 'Accept: application/vnd.github+json' `
                -H 'User-Agent: publish-script' "https://api.github.com/repos/$Repo/releases/assets/$($asset.id)"
            Write-Host "   覆盖旧资产 $($asset.name)" -ForegroundColor Gray
        }
    }

    $uploads = @(
        @{ Path = $jar; Name = $jarName; Type = 'application/java-archive' },
        @{ Path = $sumsPath; Name = 'SHA256SUMS.txt'; Type = 'text/plain' }
    )
    foreach ($u in $uploads) {
        Write-Host "   上传 $($u.Name)…" -ForegroundColor Gray
        $code = & curl.exe -s -o NUL -w "%{http_code}" -X POST `
            -H "Authorization: token $token" -H 'Accept: application/vnd.github+json' `
            -H 'User-Agent: publish-script' -H "Content-Type: $($u.Type)" `
            --data-binary "@$($u.Path)" `
            "https://uploads.github.com/repos/$Repo/releases/$($rel.id)/assets?name=$($u.Name)"
        if ($code -ne '201') { throw "上传 $($u.Name) 失败（HTTP $code）" }
        Write-Host "     HTTP $code" -ForegroundColor Gray
    }

    $relUrl = if ($rel.html_url) { $rel.html_url } else { "https://github.com/$Repo/releases/tag/$tag" }
    Write-Host ''
    Write-Host "完成: $relUrl" -ForegroundColor Green
    Write-Host "直链: https://github.com/$Repo/releases/download/$tag/$jarName" -ForegroundColor Green
} finally {
    Pop-Location
}
