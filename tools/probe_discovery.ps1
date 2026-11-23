# Read-only UDISCOVERY probe (UDP 7083). Sends one search datagram, prints the device-info reply.
param([Parameter(Mandatory=$true)][string]$Target, [int]$TimeoutMs = 2500)

$payload = New-Object byte[] 56
$ver = [Text.Encoding]::ASCII.GetBytes("2.0.0");          [Array]::Copy($ver, 0, $payload, 0x10, $ver.Length)
$tag = [Text.Encoding]::ASCII.GetBytes("UDISCOVERY_SDK"); [Array]::Copy($tag, 0, $payload, 0x18, $tag.Length)

$pkt = New-Object System.Collections.Generic.List[byte]
$pkt.AddRange([Text.Encoding]::ASCII.GetBytes("Haier"))
$pkt.AddRange([byte[]](0x00,0x00,0x69,0x15))
$pkt.AddRange((New-Object byte[] 8))
$pkt.AddRange([byte[]](0x00,0x00,0x00,56))
$pkt.AddRange($payload)
$bytes = $pkt.ToArray()

$udp = New-Object System.Net.Sockets.UdpClient
$udp.Client.ReceiveTimeout = $TimeoutMs
try {
    [void]$udp.Send($bytes, $bytes.Length, $Target, 7083)
    $ep = New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Any, 0)
    $data = $udp.Receive([ref]$ep)
    "reply from $($ep.Address):$($ep.Port) len=$($data.Length)"
    function Txt($a,$b){ $s=[Text.Encoding]::ASCII.GetString($data,$a,[Math]::Min($b-$a,$data.Length-$a)); ($s -split "`0")[0] }
    "cmd        = 0x{0:x2}{1:x2}" -f $data[7],$data[8]
    "deviceId   = " + (Txt 0x15 0x25)
    "uPlusId    = " + (($data[0x25..0x44] | ForEach-Object { $_.ToString('x2') }) -join '')
    if ($data.Length -ge 0xF7) { "ip         = " + (Txt 0xE5 0xF5); "ussPort    = " + ($data[0xF5]*256 + $data[0xF6]) }
    if ($data.Length -ge 0x102) { "sdk        = " + (Txt 0xFD 0x102) }
    if ($data.Length -ge 0x115) { "firmware   = " + (Txt 0x105 0x10D) + " / " + (Txt 0x10D 0x115) }
    if ($data.Length -ge 0x135) { "protoTag   = " + (Txt 0x115 0x135) }
    # TLV walk
    $count = ([int]$data[0x45] -shl 24) -bor ([int]$data[0x46] -shl 16) -bor ([int]$data[0x47] -shl 8) -bor [int]$data[0x48]
    $pos = 0x49
    for ($i=0; $i -lt [Math]::Min($count,32); $i++) {
        if ($pos + 2 -gt $data.Length) { break }
        $k=$data[$pos]; $l=$data[$pos+1]; if ($k -eq 0) { break }
        $v=$data[($pos+2)..($pos+1+$l)]
        if ($k -eq 3 -and $l -ge 4) { "cloudState = " + (([int]$v[0] -shl 24) -bor ([int]$v[1] -shl 16) -bor ([int]$v[2] -shl 8) -bor [int]$v[3]) + "  (1000 = connected to Haier cloud)" }
        $pos += 2 + $l
    }
} catch { "no reply: $($_.Exception.Message)" } finally { $udp.Close() }
