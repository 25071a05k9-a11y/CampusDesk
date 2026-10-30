$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
Write-Host 'CampusDesk - Java + AGY chat + Jev routing' -ForegroundColor Green
if (-not (Test-Path '.env')) { Copy-Item '.env.example' '.env'; Write-Host 'Created .env defaults.' }
Get-Content .env | ForEach-Object {
    if ($_ -match '^\s*([A-Za-z_][A-Za-z_0-9]*)=(.*)$') {
        [Environment]::SetEnvironmentVariable($matches[1],$matches[2].Trim().Trim('"'),'Process')
    }
}
$env:PATH = "$env:LOCALAPPDATA\agy\bin;$env:PATH"
$runtime = Get-ChildItem -Path '.runtime' -Recurse -Filter 'java.exe' -ErrorAction SilentlyContinue | Where-Object { $_.FullName -match '\\bin\\java\.exe$' } | Select-Object -First 1
$java = if ($runtime) {$runtime.FullName} else { $null }
if (-not $java) {
    $j = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($j) {
        $v = & $j.Source -version 2>&1 | Select-Object -First 1
        if ($v -match 'version "(\d+)\.' -and [int]$matches[1] -ge 21) { $java=$j.Source }
    }
}
if (-not $java) {
    Write-Host 'Java 21 runtime missing. CampusDesk can download a portable JRE inside its own folder (no system install).' -ForegroundColor Yellow
    $choice = Read-Host 'Download portable Eclipse Temurin Java 21 now? [Y/N]'
    if ($choice -notmatch '^[Yy]') {throw 'A Java runtime is required to run this Java backend. Nothing was installed.'}
    New-Item -ItemType Directory -Force '.runtime' | Out-Null
    $zip = Join-Path (Resolve-Path .runtime).Path 'java21-windows-x64.zip'
    $url = 'https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jre/hotspot/normal/eclipse'
    Write-Host 'Downloading portable JRE from Adoptium over HTTPS...'
    Invoke-WebRequest -Uri $url -OutFile $zip -MaximumRedirection 10 -UseBasicParsing
    Expand-Archive -LiteralPath $zip -DestinationPath '.runtime' -Force
    Remove-Item $zip
    $runtime = Get-ChildItem -Path '.runtime' -Recurse -Filter 'java.exe' | Where-Object { $_.FullName -match '\\bin\\java\.exe$' } | Select-Object -First 1
    if (-not $runtime) {throw 'Portable Java archive did not contain java.exe'}
    $java=$runtime.FullName
}
Write-Host "Java runtime: $java"
if (-not (Test-Path 'CampusDesk.jar')) { throw 'CampusDesk.jar is missing. Re-extract the ZIP.' }
$agy = Get-Command agy.exe -ErrorAction SilentlyContinue
if ($env:AGY_CLI_ENABLED -eq '1' -and -not $agy) {
    Write-Host 'AGY chat engine is not installed. Local guidance will work until it is connected.' -ForegroundColor Yellow
    $choice=Read-Host 'Open the official AGY installation + sign-in setup now? [Y/N]'
    if ($choice -match '^[Yy]') { & (Join-Path $PSScriptRoot 'Setup_AGY_Windows.cmd') }
}
Write-Host 'Starting Java server at http://127.0.0.1:8000'
Start-Process -FilePath 'cmd.exe' -ArgumentList '/c', 'open_browser_windows.bat' -WorkingDirectory $PSScriptRoot -WindowStyle Hidden
& $java -jar 'CampusDesk.jar' 8000
