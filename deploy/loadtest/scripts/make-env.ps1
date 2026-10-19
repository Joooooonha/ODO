# 측정 전용 비밀값(.env)을 난수로 만든다. 값은 화면·로그에 내지 않는다. 이미 있으면 그대로 둔다(덮어쓰지 않음).
# 사용(윈도우): powershell -NoProfile -ExecutionPolicy Bypass -File deploy\loadtest\scripts\make-env.ps1
$ErrorActionPreference = 'Stop'
$path = Join-Path $PSScriptRoot '..\.env'
if (Test-Path $path) { Write-Output ".env 있음 - 그대로 둠"; exit 0 }
function New-Secret([int]$bytes) {
  $b = New-Object byte[] $bytes
  [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b)
  -join ($b | ForEach-Object { $_.ToString('x2') })
}
$lines = @(
  "# 측정 전용 난수(scripts/make-env.ps1). 운영 값이 아니다. git 제외(.gitignore의 .env)",
  "POSTGRES_PASSWORD=$(New-Secret 24)",
  "MONITOR_PASSWORD=$(New-Secret 24)",
  "REDIS_PASSWORD=$(New-Secret 24)",
  "LOADTEST_JWT_SECRET=$(New-Secret 48)",
  "GRAFANA_ADMIN_PASSWORD=$(New-Secret 16)"
)
# compose가 읽을 수 있게 BOM 없는 UTF-8
[System.IO.File]::WriteAllLines((Resolve-Path (Split-Path $path)).Path + '\.env', $lines, (New-Object System.Text.UTF8Encoding $false))
Write-Output ".env 만듦 (변수 $($lines.Count - 1)개)"
