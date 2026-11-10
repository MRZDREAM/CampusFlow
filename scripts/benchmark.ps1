#requires -Version 7.0
param(
    [string] $BaseUrl = 'http://127.0.0.1:8080',
    [ValidateSet('DB', 'REDIS', 'MQ')] [string] $Mode = 'DB',
    [ValidateRange(2, 10000)] [int] $Users = 100,
    [ValidateRange(1, 1000)] [int] $Capacity = 10,
    [ValidateRange(1, 256)] [int] $Concurrency = 32
)
. (Join-Path $PSScriptRoot 'api-helpers.ps1')
$BaseUrl = $BaseUrl.TrimEnd('/')
$admin = Get-CampusAdminToken
$stamp = [guid]::NewGuid().ToString('N').Substring(0, 12)
$password = 'load-' + [guid]::NewGuid().ToString('N')
$resource = Invoke-CampusApi 'POST' '/api/v1/resources' @{ name = "Benchmark $Mode $stamp" } $admin
$slot = New-CampusDemoSlot $resource.id ([DateTimeOffset]::UtcNow.AddDays(1)) $Capacity $admin
$tokens = @(for ($i = 1; $i -le $Users; $i++) {
    New-CampusDemoUser "load-$stamp-$i" $password
})
$payload = @{ slotId = $slot.id } | ConvertTo-Json -Compress

$wall = [Diagnostics.Stopwatch]::StartNew()
$results = @($tokens | ForEach-Object -Parallel {
    $base = $using:BaseUrl
    $mode = $using:Mode
    $body = $using:payload
    $headers = @{ Authorization = "Bearer $_" }
    $timer = [Diagnostics.Stopwatch]::StartNew()
    $submitted = $null
    $outcome = 'ERROR'
    try {
        if ($mode -eq 'MQ') {
            $key = [guid]::NewGuid().ToString()
            $headers['Idempotency-Key'] = $key
            $url = "$base/api/v1/reservation-requests"
        } else {
            $url = "$base/api/v1/reservations?mode=$mode"
        }
        $response = Invoke-WebRequest -Method Post -Uri $url -Headers $headers -ContentType 'application/json' -Body $body -TimeoutSec 15 -SkipHttpErrorCheck
        $submitted = $timer.Elapsed.TotalMilliseconds
        if ($response.StatusCode -eq 201) { $outcome = 'SUCCEEDED' }
        if ($response.StatusCode -eq 409) { $outcome = 'REJECTED' }
        if ($response.StatusCode -eq 202) {
            do {
                $result = Invoke-RestMethod -Uri "$url/$key" -Headers $headers -TimeoutSec 10
                if ($result.status -ne 'PENDING') {
                    $outcome = $result.status
                    break
                }
                Start-Sleep -Milliseconds 150
            } while ($timer.Elapsed.TotalSeconds -lt 40)
        }
    } catch {
        $outcome = 'ERROR'
    }
    [pscustomobject]@{
        outcome = $outcome
        submitMs = $submitted
        totalMs = $timer.Elapsed.TotalMilliseconds
    }
} -ThrottleLimit $Concurrency)
$wall.Stop()

function Get-P95($Values) {
    $ordered = @($Values | Where-Object { $null -ne $_ } | Sort-Object)
    if ($ordered.Count -eq 0) { return $null }
    [Math]::Round($ordered[[Math]::Ceiling(0.95 * $ordered.Count) - 1], 2)
}

$success = @($results | Where-Object outcome -eq 'SUCCEEDED').Count
$rejected = @($results | Where-Object outcome -eq 'REJECTED').Count
$errors = $results.Count - $success - $rejected
$stored = Invoke-CampusApi 'GET' "/api/v1/resources/$($resource.id)/slots/$($slot.id)"
$result = [ordered]@{
    utc = [DateTimeOffset]::UtcNow.ToString('o')
    mode = $Mode
    users = $Users
    concurrencyLimit = $Concurrency
    capacity = $Capacity
    confirmed = $success
    rejected = $rejected
    errors = $errors
    databaseReserved = $stored.reserved
    slotId = $slot.id
    submitP95Ms = (Get-P95 $results.submitMs)
    finalResultP95Ms = (Get-P95 $results.totalMs)
    attemptsPerSecond = [Math]::Round($results.Count / $wall.Elapsed.TotalSeconds, 2)
}
$result | ConvertTo-Json -Compress
if ($errors -ne 0 -or $results.Count -ne $Users -or $success -le 0 -or $success -gt $Capacity -or $success -ne $stored.reserved) {
    throw 'Benchmark failed the booking invariant or encountered an unexpected request result.'
}
