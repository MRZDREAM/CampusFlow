#requires -Version 5.1
param([string] $BaseUrl = 'http://127.0.0.1:8080', [switch] $DatabaseOnly)
. (Join-Path $PSScriptRoot 'api-helpers.ps1')
$BaseUrl = $BaseUrl.TrimEnd('/')
$admin = Get-CampusAdminToken
$stamp = [guid]::NewGuid().ToString('N').Substring(0, 12)
$password = 'demo-' + [guid]::NewGuid().ToString('N')
$alice = New-CampusDemoUser "demo-$stamp-a" $password
$bob = New-CampusDemoUser "demo-$stamp-b" $password
$carol = New-CampusDemoUser "demo-$stamp-c" $password
$resource = Invoke-CampusApi 'POST' '/api/v1/resources' @{
    name = "Demo GPU $stamp"
    description = 'Synthetic data for a reproducible booking demonstration'
} $admin
$starts = [DateTimeOffset]::UtcNow.AddDays(1)
$slot = New-CampusDemoSlot $resource.id $starts 1 $admin
$order = Invoke-CampusApi 'POST' '/api/v1/reservations' @{ slotId = $slot.id } $alice
$entry = Invoke-CampusApi 'POST' '/api/v1/waitlist' @{ slotId = $slot.id } $bob
$next = Invoke-CampusApi 'POST' '/api/v1/waitlist' @{ slotId = $slot.id } $carol
$cancelPath = "/api/v1/reservations/$($order.id)/cancellation"
$null = Invoke-CampusApi 'POST' $cancelPath $null $alice
$repeated = Invoke-CampusApi 'POST' $cancelPath $null $alice
$offer = Invoke-CampusApi 'GET' "/api/v1/waitlist/$($entry.id)" $null $bob
$waiting = Invoke-CampusApi 'GET' "/api/v1/waitlist/$($next.id)" $null $carol
if ($repeated.status -ne 'CANCELLED' -or $offer.status -ne 'OFFERED' -or $waiting.status -ne 'WAITING') {
    throw 'Unexpected repeated cancellation or FIFO result.'
}
$accepted = Invoke-CampusApi 'POST' "/api/v1/waitlist/$($entry.id)/confirmation" $null $bob
if ($accepted.status -ne 'ACCEPTED' -or -not $accepted.reservationId) { throw 'Offer did not become an order.' }
$confirmedSlot = Invoke-CampusApi 'GET' "/api/v1/resources/$($resource.id)/slots/$($slot.id)"
if ($confirmedSlot.reserved -ne 1) { throw 'An offer was counted twice.' }
Write-Host "DB: resource=$($resource.id), slot=$($slot.id), FIFO offer accepted, reserved=1."

if (-not $DatabaseOnly) {
    $redisSlot = New-CampusDemoSlot $resource.id ($starts.AddHours(1)) 1 $admin
    $null = Invoke-CampusApi 'GET' "/api/v1/resources/$($resource.id)/slots"
    $null = Invoke-CampusApi 'GET' "/api/v1/resources/$($resource.id)/slots"
    $redisOrder = Invoke-CampusApi 'POST' '/api/v1/reservations?mode=REDIS' @{ slotId = $redisSlot.id } $alice
    if ($redisOrder.status -ne 'CONFIRMED') { throw 'Redis admission demo failed.' }
    $asyncSlot = New-CampusDemoSlot $resource.id ($starts.AddHours(2)) 1 $admin
    $id = [guid]::NewGuid().ToString()
    $request = Invoke-CampusApi 'POST' '/api/v1/reservation-requests' @{ slotId = $asyncSlot.id } $alice $id
    $repeat = Invoke-CampusApi 'POST' '/api/v1/reservation-requests' @{ slotId = $asyncSlot.id } $alice $id
    if ($repeat.requestId -ne $request.requestId) { throw 'An identical idempotency key returned a different request.' }
    $result = Wait-CampusRequest $id $alice
    if ($result.status -ne 'SUCCEEDED') { throw "Async request ended in $($result.status)." }
    Write-Host "Redis: slot=$($redisSlot.id), reservation=$($redisOrder.id)."
    Write-Host "RabbitMQ: request=$id, status=SUCCEEDED, reservation=$($result.reservationId)."
}
