# Measurement-only secrets (.env) from random bytes. Values never printed. Existing values are kept, missing ones added.
# Usage (Windows): powershell -NoProfile -ExecutionPolicy Bypass -File deploy\loadtest\scripts\make-env.ps1
# NOTE: ASCII only - Windows PowerShell 5.1 reads BOM-less UTF-8 as the ANSI code page and breaks on Korean text.
$ErrorActionPreference = 'Stop'
$dir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$path = Join-Path $dir '.env'
function New-Secret([int]$bytes) {
  $b = New-Object byte[] $bytes
  [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b)
  -join ($b | ForEach-Object { $_.ToString('x2') })
}
$wanted = [ordered]@{
  POSTGRES_PASSWORD = 24; MONITOR_PASSWORD = 24; REDIS_PASSWORD = 24
  LOADTEST_JWT_SECRET = 48; GRAFANA_ADMIN_PASSWORD = 16; RENDERER_TOKEN = 24
}
$lines = New-Object System.Collections.Generic.List[string]
if (Test-Path $path) { [System.IO.File]::ReadAllLines($path) | ForEach-Object { $lines.Add($_) } }
else { $lines.Add("# measurement-only random values (scripts/make-env.ps1). NOT production. git-ignored (.env)") }
# existing values are kept; only missing names are added
$added = 0
foreach ($name in $wanted.Keys) {
  if (-not ($lines | Where-Object { $_ -like "$name=*" })) { $lines.Add("$name=$(New-Secret $wanted[$name])"); $added++ }
}
# BOM-less UTF-8 so docker compose reads it
[System.IO.File]::WriteAllLines($path, $lines, (New-Object System.Text.UTF8Encoding $false))
Write-Output (".env: added " + $added + ", kept " + ($wanted.Count - $added))
