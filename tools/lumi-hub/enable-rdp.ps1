<#
OPTIONAL helper. Lumi never runs this for you: read it, then run it yourself from an elevated PowerShell:
    powershell -ExecutionPolicy Bypass -File .\enable-rdp.ps1
It does only three things:
  1. turns Remote Desktop on (Windows Pro / Enterprise / Education; Home has no RDP host),
  2. requires Network Level Authentication (NLA),
  3. adds a firewall rule for TCP 3389 limited to the Tailscale range 100.64.0.0/10 (never the internet or the LAN).
Your Windows account needs a password. Undo with: .\enable-rdp.ps1 -Undo
#>
param([switch]$Undo)

$ErrorActionPreference = 'Stop'
$identity = [Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
if (-not $identity.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Error 'Run this from an elevated (Administrator) PowerShell.'
    exit 1
}

$ts = 'HKLM:\SYSTEM\CurrentControlSet\Control\Terminal Server'
$rdpTcp = "$ts\WinStations\RDP-Tcp"
$rule = 'Lumi RDP (Tailscale only)'

if ($Undo) {
    Set-ItemProperty -Path $ts -Name fDenyTSConnections -Value 1
    Get-NetFirewallRule -DisplayName $rule -ErrorAction SilentlyContinue | Remove-NetFirewallRule
    Write-Host 'Remote Desktop turned off and the Lumi firewall rule removed.'
    exit 0
}

Set-ItemProperty -Path $ts -Name fDenyTSConnections -Value 0
Set-ItemProperty -Path $rdpTcp -Name UserAuthentication -Value 1   # NLA required

if (-not (Get-NetFirewallRule -DisplayName $rule -ErrorAction SilentlyContinue)) {
    New-NetFirewallRule -DisplayName $rule -Direction Inbound -Protocol TCP -LocalPort 3389 `
        -RemoteAddress '100.64.0.0/10' -Action Allow -Profile Any | Out-Null
}
# The built-in "Remote Desktop" rules would open 3389 to every network: keep them off
Get-NetFirewallRule -DisplayGroup 'Remote Desktop' -ErrorAction SilentlyContinue | Disable-NetFirewallRule

Write-Host 'Remote Desktop is on, NLA is required and port 3389 is reachable only from the Tailscale range (100.64.0.0/10).'
Write-Host 'Connect from the phone through the Remote control button in Lumi (My PC). Do not forward 3389 on your router.'
