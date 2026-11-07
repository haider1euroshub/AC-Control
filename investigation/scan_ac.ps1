# Haier AC LAN Port 56800 Scanner
param(
    [string]$Subnet = "192.168.100",
    [int]$Port = 56800,
    [int]$TimeoutMs = 150
)

Write-Host "Scanning subnet ${Subnet}.0/24 for Haier AC listener on TCP port ${Port}..."

$found = @()
$jobs = @()

1..254 | ForEach-Object {
    $ip = "${Subnet}.$_"
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $async = $client.BeginConnect($ip, $Port, $null, $null)
        if ($async.AsyncWaitHandle.WaitOne($TimeoutMs, $false)) {
            if ($client.Connected) {
                Write-Host "[FOUND] Open TCP port $Port on $ip" -ForegroundColor Green
                $found += $ip
                $client.EndConnect($async)
            }
        }
    } catch {
        # ignore
    } finally {
        $client.Close()
    }
}

if ($found.Count -eq 0) {
    Write-Host "Scan completed. No devices responded on TCP port $Port." -ForegroundColor Yellow
} else {
    Write-Host "Scan completed. Responders on port ${Port}: $($found -join ', ')" -ForegroundColor Green
}

