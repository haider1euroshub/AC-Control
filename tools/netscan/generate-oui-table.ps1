<#
.SYNOPSIS
  Generates core-netscan/.../OuiData.kt (MAC prefix -> manufacturer) from IEEE MA-L registry data.

.DESCRIPTION
  Accepts either source; both carry the IEEE registry's organization names:

    -Manuf  Wireshark's "manuf" file, generated from the IEEE registries by `tshark -G manuf`:
            https://www.wireshark.org/download/automated/data/manuf  (updated weekly; please don't
            download it more often than that)
    -Csv    The IEEE MA-L registry itself: https://standards-oui.ieee.org/oui/oui.csv
            (often rejects scripted downloads; save it from a browser)

      powershell -ExecutionPolicy Bypass -File tools/netscan/generate-oui-table.ps1 -Manuf C:\path\to\manuf
      powershell -ExecutionPolicy Bypass -File tools/netscan/generate-oui-table.ps1 -Csv   C:\path\to\oui.csv

  Only 24-bit blocks whose organization name matches a brand below are bundled, under a short display
  name. 28/36-bit sub-blocks (MA-M/MA-S) and locally administered prefixes (CIDs; they can never be a
  vendor's real MAC) are skipped. A type hint is given only for makers that sell essentially one kind
  of networked device; the classifier uses it only when nothing the device advertises says more.
#>
param(
    [string]$Csv,
    [string]$Manuf,
    [string]$Out = (Join-Path $PSScriptRoot '..\..\core-netscan\src\main\kotlin\com\roomac\controller\netscan\OuiData.kt')
)
$ErrorActionPreference = 'Stop'
if ([bool]$Csv -eq [bool]$Manuf) { throw 'Pass exactly one of -Csv <oui.csv> or -Manuf <manuf>.' }

# Regex on the registry organization name (case-insensitive) -> display name, DeviceType hint ('' = none).
$brands = @(
    @('^Raspberry Pi',                  'Raspberry Pi',          ''),
    @('^Espressif',                     'Espressif',             'IOT'),
    @('^Signify|^Philips Lighting',     'Signify (Philips Hue)', 'IOT'),
    @('^Nest Labs',                     'Nest',                  'IOT'),
    @('^Sonos,? Inc',                   'Sonos',                 'SPEAKER'),  # not SonoSite (ultrasound) or SONOS Co.
    @('^Roku',                          'Roku',                  'MEDIA'),
    @('Hikvision',                      'Hikvision',             'CAMERA'),
    @('Dahua Technology',               'Dahua',                 'CAMERA'),
    @('^Brother Industries',            'Brother',               'PRINTER'),
    @('^Seiko Epson',                   'Epson',                 'PRINTER'),
    @('^Canon Inc',                     'Canon',                 ''),
    @('^Hewlett Packard Enterprise',    'HPE (Aruba)',           ''),  # before HP: a separate company since 2015
    @('^HP Inc|^Hewlett[ -]Packard',    'HP',                    ''),
    @('^Synology',                      'Synology',              ''),
    @('^Ubiquiti',                      'Ubiquiti',              ''),
    @('^TP-LINK',                       'TP-Link',               ''),
    @('^Google',                        'Google',                ''),
    @('^Amazon Technologies',           'Amazon',                ''),
    @('^Apple,? Inc',                   'Apple',                 ''),
    @('^Samsung Electronics',           'Samsung',               ''),
    @('^LG Electronics',                'LG',                    ''),
    @('^Sony (?!Tektronix)',            'Sony',                  ''),  # Sony/Tektronix made test gear
    @('^Xiaomi',                        'Xiaomi',                ''),
    @('Haier',                          'Haier',                 ''),
    @('^Gree Electric',                 'Gree',                  ''),
    @('(^|[^a-z])Midea',                'Midea',                 '')   # not "Limidea"
)

# Normalise both inputs to (Assignment = 6 upper-case hex, Org = registry organization name).
if ($Csv) {
    $src = $Csv
    $raw = Import-Csv -LiteralPath $Csv
    if (-not $raw -or -not ($raw[0].PSObject.Properties.Name -contains 'Assignment') -or -not ($raw[0].PSObject.Properties.Name -contains 'Organization Name')) {
        throw "$Csv does not look like the IEEE oui.csv (expected columns Registry, Assignment, Organization Name)."
    }
    $rows = $raw | Where-Object { $_.Registry -eq 'MA-L' -and $_.Assignment -match '^[0-9A-Fa-f]{6}$' } |
        ForEach-Object { [pscustomobject]@{ Assignment = $_.Assignment.ToUpper(); Org = $_.'Organization Name' } }
    $label = 'IEEE oui.csv'
} else {
    $src = $Manuf
    $lines = Get-Content -LiteralPath $Manuf -Encoding UTF8
    if (-not ($lines | Select-Object -First 5 | Where-Object { $_ -match 'tshark -G manuf' })) {
        throw "$Manuf does not look like Wireshark's manuf file (no 'tshark -G manuf' header)."
    }
    # "00:0E:58<spaces>\tSonos<spaces>\tSonos, Inc."; 28/36-bit blocks carry a /28 or /36 suffix and are skipped.
    $rows = foreach ($l in $lines) {
        if ($l.StartsWith('#') -or -not $l.Trim()) { continue }
        $cols = $l -split "`t"
        if ($cols.Count -lt 3) { continue }
        $block = $cols[0].Trim()
        if ($block -notmatch '^[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}$') { continue }
        [pscustomobject]@{ Assignment = ($block -replace ':', '').ToUpper(); Org = $cols[2].Trim() }
    }
    $label = 'Wireshark manuf (IEEE registry data)'
}
# Locally administered (bit 1 of the first octet): IEEE CIDs, never a manufacturer's real MAC.
$rows = @($rows | Where-Object { ([Convert]::ToInt32($_.Assignment.Substring(0, 2), 16) -band 2) -eq 0 })
if ($rows.Count -lt 1000) { throw "Only $($rows.Count) usable 24-bit rows in $src; refusing to generate from a truncated file." }
$hash = (Get-FileHash -LiteralPath $src -Algorithm SHA256).Hash.ToLower()

$sb = New-Object System.Text.StringBuilder
[void]$sb.AppendLine('package com.roomac.controller.netscan')
[void]$sb.AppendLine('')
[void]$sb.AppendLine('// GENERATED FILE: regenerate with tools/netscan/generate-oui-table.ps1; do not edit by hand.')
[void]$sb.AppendLine("// Source: $label, $($rows.Count) usable 24-bit blocks, sha256 $hash, generated $(Get-Date -Format yyyy-MM-dd).")
if ($Manuf) { [void]$sb.AppendLine('// Downloaded from https://www.wireshark.org/download/automated/data/manuf (standards-oui.ieee.org was unreachable).') }
[void]$sb.AppendLine('')
[void]$sb.AppendLine('/** MAC prefixes from the IEEE MA-L registry, only for the brands the generator selects. */')
[void]$sb.AppendLine('object OuiData {')
[void]$sb.AppendLine('    class Vendor(val name: String, val typeHint: DeviceType?, /** Space-separated 6-hex OUIs. */ val prefixes: String)')
[void]$sb.AppendLine('')
[void]$sb.AppendLine('    /** Source registry file and its SHA-256, recorded by the generator. */')
[void]$sb.AppendLine("    const val SOURCE = `"$label sha256:$hash`"")
[void]$sb.AppendLine('')
[void]$sb.AppendLine('    val VENDORS: List<Vendor> = listOf(')

$total = 0
$claimed = @{}
foreach ($b in $brands) {
    $pattern, $name, $hint = $b
    $prefixes = $rows |
        Where-Object { $_.Org -match $pattern } |
        ForEach-Object { $_.Assignment } |
        Where-Object { -not $claimed.ContainsKey($_) } |
        Sort-Object -Unique
    if (-not $prefixes) { Write-Warning "No registry rows matched '$pattern' ($name)"; continue }
    $prefixes | ForEach-Object { $claimed[$_] = $name }
    $total += @($prefixes).Count
    $type = if ($hint) { "DeviceType.$hint" } else { 'null' }
    # 16 prefixes per line keeps the file readable; the string is concatenated at compile time.
    $chunks = for ($i = 0; $i -lt @($prefixes).Count; $i += 16) { '"' + ((@($prefixes)[$i..([Math]::Min($i + 15, @($prefixes).Count - 1))]) -join ' ') + '"' }
    [void]$sb.AppendLine("        Vendor(`"$name`", $type,")
    [void]$sb.AppendLine('            ' + ($chunks -join " + `" `" +`n            ") + '),')
    Write-Host ("{0,-24} {1,5} prefixes" -f $name, @($prefixes).Count)
}
[void]$sb.AppendLine('    )')
[void]$sb.AppendLine('}')

[System.IO.File]::WriteAllText((Resolve-Path -LiteralPath (Split-Path $Out)).Path + '\' + (Split-Path $Out -Leaf), $sb.ToString(), (New-Object System.Text.UTF8Encoding($false)))
Write-Host "Wrote $total prefixes to $Out"
