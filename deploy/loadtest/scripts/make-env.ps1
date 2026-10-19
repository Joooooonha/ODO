# Measurement-only secrets (.env) from random bytes. Values never printed. Keeps an existing .env.
# Usage (Windows): powershell -NoProfile -ExecutionPolicy Bypass -File deploy\loadtest\scripts\make-env.ps1
# NOTE: ASCII only - Windows PowerShell 5.1 reads BOM-less UTF-8 as the ANSI code page and breaks on Korean text.
$ErrorActionPreference = 'Stop'
$dir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$path = Join-Path $dir '.env'
if (Test-Path $path) { Write-Output ".env exists - kept"; exit 0 }
function New-Secret([int]$bytes) {
  $b = New-Object byte[] $bytes
  [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b)
  -join ($b | ForEach-Object { $_.ToString('x2') })
}
$lines = @(
  "# measurement-only random values (scripts/make-env.ps1). NOT production. git-ignored (.env)",
  "POSTGRES_PASSWORD=$(New-Secret 24)",
  "MONITOR_PASSWORD=$(New-Secret 24)",
  "REDIS_PASSWORD=$(New-Secret 24)",
  "LOADTEST_JWT_SECRET=$(New-Secret 48)",
  "GRAFANA_ADMIN_PASSWORD=$(New-Secret 16)"
)
# BOM-less UTF-8 so docker compose reads it
[System.IO.File]::WriteAllLines($path, $lines, (New-Object System.Text.UTF8Encoding $false))
Write-Output (".env created (" + ($lines.Count - 1) + " variables)")
