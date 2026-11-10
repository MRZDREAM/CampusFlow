$ErrorActionPreference = 'Stop'

function Get-LocalSetting([string] $Name) {
    $runtimeValue = [Environment]::GetEnvironmentVariable($Name)
    if (-not [string]::IsNullOrWhiteSpace($runtimeValue)) { return $runtimeValue }
    $config = Join-Path (Split-Path -Parent $PSScriptRoot) '.env'
    if (Test-Path -LiteralPath $config) {
        $prefix = $Name + '='
        foreach ($line in [IO.File]::ReadAllLines($config)) {
            if ($line.StartsWith($prefix, [StringComparison]::Ordinal)) {
                return $line.Substring($prefix.Length)
            }
        }
    }
    throw "Missing $Name. Run scripts/init-local.ps1 first."
}

function Invoke-CampusApi([string] $Method, [string] $Path, $Body = $null,
        [string] $Token = '', [string] $RequestKey = '') {
    $headers = @{}
    if ($Token) { $headers['Authorization'] = "Bearer $Token" }
    if ($RequestKey) { $headers['Idempotency-Key'] = $RequestKey }
    $arguments = @{ Method = $Method; Uri = "$BaseUrl$Path"; Headers = $headers; TimeoutSec = 12 }
    if ($null -ne $Body) {
        $arguments['ContentType'] = 'application/json'
        $arguments['Body'] = ($Body | ConvertTo-Json -Compress -Depth 5)
    }
    Invoke-RestMethod @arguments
}

function Get-CampusAdminToken {
    $username = Get-LocalSetting 'CAMPUS_ADMIN_USERNAME'
    $password = Get-LocalSetting 'CAMPUS_ADMIN_PASSWORD'
    (Invoke-CampusApi 'POST' '/api/v1/auth/login' @{ username = $username; password = $password }).accessToken
}

function New-CampusDemoUser([string] $Username, [string] $Password) {
    $credentials = @{ username = $Username; password = $Password }
    $null = Invoke-CampusApi 'POST' '/api/v1/auth/register' $credentials
    (Invoke-CampusApi 'POST' '/api/v1/auth/login' $credentials).accessToken
}

function New-CampusDemoSlot([long] $ResourceId, [DateTimeOffset] $StartsAt, [int] $Capacity, [string] $Admin) {
    Invoke-CampusApi 'POST' "/api/v1/resources/$ResourceId/slots" @{
        startsAt = $StartsAt.ToString('o')
        endsAt = $StartsAt.AddHours(1).ToString('o')
        capacity = $Capacity
    } $Admin
}

function Wait-CampusRequest([string] $Id, [string] $Token) {
    $end = [DateTimeOffset]::UtcNow.AddSeconds(30)
    do {
        $request = Invoke-CampusApi 'GET' "/api/v1/reservation-requests/$Id" $null $Token
        if ($request.status -ne 'PENDING') { return $request }
        Start-Sleep -Milliseconds 200
    } while ([DateTimeOffset]::UtcNow -lt $end)
    throw "Request $Id did not finish within the demo's 30-second wait."
}
