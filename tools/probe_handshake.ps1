# Read-only uSS HELLO probe (TCP 56800): sends HELLO, prints HELLO_RESP (status + localKey version), closes.
param([Parameter(Mandatory=$true)][string]$Target, [Parameter(Mandatory=$true)][string]$DeviceId)

$dev = New-Object byte[] 32
$d = [Text.Encoding]::ASCII.GetBytes($DeviceId); [Array]::Copy($d, 0, $dev, 0, $d.Length)
$len = 32 + 0x0A
$hdr = [byte[]](0x00,0x00,0xEA,0x60, ($len -shr 8), ($len -band 0xFF), 0x01, 0x00, 0,0,0,1, 0,0, 0,0)
$msg = $hdr + $dev

$c = New-Object System.Net.Sockets.TcpClient
try {
    $c.Connect($Target, 56800)
    $s = $c.GetStream(); $s.ReadTimeout = 4000
    $s.Write($msg, 0, $msg.Length)
    $buf = New-Object byte[] 512
    $n = $s.Read($buf, 0, $buf.Length)
    "read $n bytes: " + (($buf[0..($n-1)] | ForEach-Object { $_.ToString('x2') }) -join '')
    $infoCode = ([int]$buf[0] -shl 24) -bor ([int]$buf[1] -shl 16) -bor ([int]$buf[2] -shl 8) -bor [int]$buf[3]
    "info_code = 0x{0:x8}  type_byte=0x{1:x2} flag={2} session=0x{3:x2}{4:x2}" -f $infoCode,$buf[6],$buf[7],$buf[14],$buf[15]
    if ($n -ge 24) {
        $status = ([int]$buf[16] -shl 24) -bor ([int]$buf[17] -shl 16) -bor ([int]$buf[18] -shl 8) -bor [int]$buf[19]
        $ver    = ([int]$buf[20] -shl 24) -bor ([int]$buf[21] -shl 16) -bor ([int]$buf[22] -shl 8) -bor [int]$buf[23]
        "status = $status (1 = session accepted)   localkey_version = $ver"
    }
} catch { "error: $($_.Exception.Message)" } finally { $c.Close() }
