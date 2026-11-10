#requires -Version 5.1
$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
$configPath = Join-Path $projectDirectory '.env'

function New-LocalSecret([int] $ByteCount, [bool] $Base64 = $false) {
    $bytes = New-Object byte[] $ByteCount
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
    if ($Base64) { return [Convert]::ToBase64String($bytes) }
    return [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
}

$lines = [Collections.Generic.List[string]]::new()
$existingPath = if (Test-Path -LiteralPath $configPath) { $configPath } else { Join-Path $projectDirectory '.env.example' }
foreach ($line in [IO.File]::ReadAllLines($existingPath)) { $lines.Add($line) }

$defaults = [ordered]@{
    DB_PASSWORD = (New-LocalSecret 20)
    MYSQL_ROOT_PASSWORD = (New-LocalSecret 20)
    REDIS_PASSWORD = (New-LocalSecret 20)
    RABBIT_PASSWORD = (New-LocalSecret 20)
    CAMPUS_JWT_KEY = (New-LocalSecret 32 $true)
    CAMPUS_ADMIN_USERNAME = 'localadmin'
    CAMPUS_ADMIN_PASSWORD = (New-LocalSecret 20)
}

foreach ($entry in $defaults.GetEnumerator()) {
    $prefix = $entry.Key + '='
    $found = $false
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i].StartsWith($prefix, [StringComparison]::Ordinal)) {
            $value = $lines[$i].Substring($prefix.Length)
            if ([string]::IsNullOrWhiteSpace($value) -or $value.StartsWith('replace-with-')) {
                $lines[$i] = $prefix + $entry.Value
            }
            $found = $true
            break
        }
    }
    if (-not $found) { $lines.Add($prefix + $entry.Value) }
}

[IO.File]::WriteAllLines($configPath, $lines, [Text.UTF8Encoding]::new($false))
Write-Host "Local settings prepared at $configPath. Existing configured values were kept."
