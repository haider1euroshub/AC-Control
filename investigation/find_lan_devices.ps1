# Ping sweep 192.168.100.0/24 and report active IP and MAC addresses
param(
    [string]$Subnet = "192.168.100"
)

Write-Host "Pinging subnet ${Subnet}.1 to ${Subnet}.254 to discover live hosts..."
1..254 | ForEach-Object {
    $ip = "${Subnet}.$_"
    $p = New-Object System.Net.NetworkInformation.Ping
    try {
        $p.SendAsync($ip, 150, $null)
    } catch {}
}

Start-Sleep -Seconds 2

Write-Host "Reading local ARP table..."
$arp = arp -a | Out-String
Write-Host $arp

