<#
.SYNOPSIS
  Lists the HTTP calls in a HaiSmart capture log that could be the AC "bind" call.

.DESCRIPTION
  Reads a log written by C:\Users\Haider\htk-capture\htk-log-phone.ps1 (one @@HTK@@{json} line per
  OkHttp call), removes the duplicates the logger emits (one per interceptor level), drops endpoints
  RoomAC already knows are not binding (sign-in, sign-up, device list, product search, family, scenes,
  resources, push), and prints what is left in capture order: method, URL, status, request body and
  response body (truncated). Header values are never printed, so access tokens stay out of the output.

    powershell -ExecutionPolicy Bypass -File tools\haier-capture\extract-bind-candidates.ps1 C:\Users\Haider\haismart-acpair.log
    ... -All          also list the known endpoints
    ... -Json out.json  also write the candidate calls (full bodies, no headers) to a JSON file

  Request bodies can contain the account password (AES-wrapped) or provisioning tokens: share the
  output only with someone you trust, and never commit it.
#>
param(
    [Parameter(Mandatory = $true)][string]$Log,
    [switch]$All,
    [string]$Json,
    [int]$MaxBody = 600
)
$ErrorActionPreference = 'Stop'

# Paths seen in sign-in/sign-up/pairing captures (2026-11-18) that are not the bind call.
$known = @(
    '/uplussea/accounts/', '/uplussea/users/v2/user/detail', '/uplussea/devices/v2/user/devices',
    '/uplussea/devices/v3/prods/global/search', '/uplussea/devices/v2/category/appTypes',
    '/uplussea/resources/', '/wisdomfamily/family/', '/api-gw/seasceneapi/', '/ums/v3/account/register',
    '/download/resource/'
)

function Short([string]$s) {
    if ($null -eq $s) { return '' }
    $one = $s -replace '\s+', ' '
    if ($one.Length -le $MaxBody) { return $one }
    return $one.Substring(0, $MaxBody) + " ... (+$($one.Length - $MaxBody) chars)"
}

$seen = @{}
$calls = New-Object System.Collections.Generic.List[object]
$bad = 0
foreach ($line in [System.IO.File]::ReadLines((Resolve-Path -LiteralPath $Log).Path)) {
    $i = $line.IndexOf('@@HTK@@')
    if ($i -lt 0) { continue }
    try { $e = $line.Substring($i + 7) | ConvertFrom-Json } catch { $bad++; continue }
    if ($e.tag -ne 'http') { continue }
    $key = "$($e.method) $($e.url) $($e.reqBody) $($e.status)"
    if ($seen.ContainsKey($key)) { continue }
    $seen[$key] = $true
    $path = ([Uri]$e.url).AbsolutePath
    $isKnown = [bool]($known | Where-Object { $path.StartsWith($_) })
    $calls.Add([pscustomobject]@{ ts = $e.ts; method = $e.method; url = $e.url; path = $path; status = $e.status; known = $isKnown; reqBody = $e.reqBody; respBody = $e.respBody })
}

$show = if ($All) { $calls } else { $calls | Where-Object { -not $_.known } }
Write-Host ("{0} unique HTTP calls, {1} not yet known{2}" -f $calls.Count, @($calls | Where-Object { -not $_.known }).Count, $(if ($bad) { ", $bad unreadable lines" } else { '' })) -ForegroundColor Cyan
foreach ($c in $show) {
    $flag = if ($c.known) { '   ' } elseif ($c.path -match 'bind|device|gw') { '>>>' } else { ' ? ' }
    Write-Host ("{0} {1} {2} {3} -> {4}" -f $flag, $c.ts, $c.method, $c.url, $c.status) -ForegroundColor $(if ($flag -eq '>>>') { 'Yellow' } else { 'Gray' })
    if ($c.reqBody) { Write-Host ("      req : " + (Short $c.reqBody)) }
    if ($c.respBody) { Write-Host ("      resp: " + (Short $c.respBody)) }
}
if (-not @($show).Count) { Write-Host 'No unknown calls: the bind call was not captured (see HAIER_SIGNUP_BIND_ENDPOINTS.md, "If nothing new shows up").' -ForegroundColor Yellow }
if ($Json) {
    @($calls | Where-Object { -not $_.known }) | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $Json -Encoding utf8
    Write-Host "Wrote candidates to $Json"
}
