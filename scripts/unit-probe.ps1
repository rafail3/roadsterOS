<#
.SYNOPSIS
    Reads the unit's build, privileges, partition table and boot files, and pulls the small partitions when root is
    available. Writes nothing on the unit except temporary files under /data/local/tmp, removed at the end.

.DESCRIPTION
    Over adb: build properties, `adb root`, an existing su, the chip, the partition table with sizes, mounts, verified
    boot state and the boot animation's location. With a root shell it copies every partition of 64 MB or less and
    the eMMC boot areas (preloader) with dd, pulls them and records their SHA-256.
    Results go outside the repository (default ..\..\unit-backup\<time>\): the partitions hold the unit's serial
    number and MAC addresses.

.EXAMPLE
    .\scripts\unit-probe.ps1 -Unit 192.168.43.120
#>
param(
    [Parameter(Mandatory = $true)] [string] $Unit,
    [string] $Out = (Join-Path $PSScriptRoot ("..\..\unit-backup\" + (Get-Date -Format 'yyyyMMdd-HHmmss'))),
    [int64] $MaxBytes = 64MB
)

$ErrorActionPreference = 'Stop'
$target = if ($Unit -match ':') { $Unit } else { "${Unit}:5555" }
New-Item -ItemType Directory -Force $Out | Out-Null
$report = Join-Path $Out 'probe.txt'

function Sh([string] $command) {
    # one string, run by the unit's shell; errors come back as text, never stop the probe
    $o = & adb -s $target shell $command 2>&1
    return (($o | ForEach-Object { "$_" }) -join "`n").Trim()
}
function Bytes([string] $command) {
    $t = Sh $command
    if ($t -match '^\d+$') { return [int64] $t } else { return [int64] 0 }
}
function Note([string] $title, [string] $text) {
    $block = "== $title`n$text`n"
    Add-Content -Encoding utf8 $report $block
    Write-Host $block
}

$connect = & adb connect $target 2>&1
if ($connect -notmatch 'connected') { throw "adb connect $target failed: $connect" }

$props = 'ro.product.model', 'ro.product.board', 'ro.board.platform', 'ro.hardware', 'ro.mediatek.platform',
    'ro.build.type', 'ro.build.tags', 'ro.build.display.id', 'ro.build.version.release', 'ro.build.fingerprint',
    'ro.secure', 'ro.debuggable', 'ro.adb.secure', 'ro.boot.verifiedbootstate', 'ro.boot.veritymode',
    'ro.boot.flash.locked', 'ro.oem_unlock_supported', 'ro.boot.bootreason'
Note 'Build' (($props | ForEach-Object { "$_ = " + (Sh "getprop $_") }) -join "`n")
Note 'Chip' (Sh "grep -iE 'hardware|processor|model name' /proc/cpuinfo | sort -u")

# root: adb root first (userdebug builds allow it), then an su already on the system
$rootTry = (& adb -s $target root 2>&1) -join ' '
Start-Sleep -Seconds 3
& adb connect $target 2>&1 | Out-Null
$id = Sh 'id'
$su = Sh 'which su; ls -l /system/xbin/su /system/bin/su /sbin/su 2>/dev/null'
$suId = if ($su) { Sh "su -c id 2>&1" } else { '' }
Note 'Root' "adb root: $rootTry`nshell id: $id`nsu: $su`nsu -c id: $suId"
$root = $id -match 'uid=0'

Note 'Partitions by name' (Sh 'ls -l /dev/block/platform/*/by-name/ /dev/block/by-name/ 2>/dev/null')
Note 'Partition sizes (KB)' (Sh 'cat /proc/partitions')
Note 'Mounts' (Sh "mount | grep -E ' /system | / | /vendor | /cust | /oem | /data '")
Note 'Kernel command line' (Sh 'cat /proc/cmdline')
Note 'Boot animation' (Sh 'ls -l /system/media/bootanimation.zip /system/media/*.zip /oem/media/*.zip /cust/media/*.zip /data/local/bootanimation.zip 2>/dev/null')
Note 'Free space' (Sh 'df -h /data /system 2>/dev/null')

if (-not $root) {
    Note 'Backup' 'Skipped: no root shell. The partitions can only be read as root (adb root refused); they are read over USB with the MediaTek flash tool instead.'
    Write-Host "Report: $report"
    exit 0
}

# backup: every partition up to $MaxBytes and the eMMC boot areas, through /data/local/tmp
$dir = Sh 'for d in /dev/block/platform/*/by-name /dev/block/by-name; do [ -d $d ] && echo $d && break; done'
$names = (Sh "ls $dir").Split("`n") | ForEach-Object { $_.Trim() } | Where-Object { $_ }
$targets = @()
foreach ($n in $names) {
    $size = Bytes "blockdev --getsize64 $dir/$n 2>/dev/null"
    if ($size -gt 0 -and $size -le $MaxBytes) { $targets += [pscustomobject]@{ Name = $n; Path = "$dir/$n"; Bytes = $size } }
}
foreach ($b in 'mmcblk0boot0', 'mmcblk0boot1') {
    $size = Bytes "blockdev --getsize64 /dev/block/$b 2>/dev/null"
    if ($size -gt 0) { $targets += [pscustomobject]@{ Name = $b; Path = "/dev/block/$b"; Bytes = $size } }
}
Sh 'mkdir -p /data/local/tmp/rdstr-bk' | Out-Null
$lines = @()
foreach ($t in $targets) {
    Write-Host ("Reading {0} ({1:N0} KB)" -f $t.Name, ($t.Bytes / 1KB))
    Sh "dd if=$($t.Path) of=/data/local/tmp/rdstr-bk/$($t.Name).img bs=1048576 2>/dev/null" | Out-Null
    & adb -s $target pull "/data/local/tmp/rdstr-bk/$($t.Name).img" (Join-Path $Out "$($t.Name).img") 2>&1 | Out-Null
    Sh "rm /data/local/tmp/rdstr-bk/$($t.Name).img" | Out-Null
    $file = Join-Path $Out "$($t.Name).img"
    $ok = (Test-Path $file) -and ((Get-Item $file).Length -eq $t.Bytes)
    $hash = if (Test-Path $file) { (Get-FileHash $file -Algorithm SHA256).Hash.ToLower() } else { '-' }
    $lines += "{0,-16} {1,12} bytes  {2}  {3}" -f $t.Name, $t.Bytes, $(if ($ok) { 'OK  ' } else { 'FAIL' }), $hash
}
Sh 'rmdir /data/local/tmp/rdstr-bk' | Out-Null
$lines | Set-Content -Encoding utf8 (Join-Path $Out 'SHA256SUMS.txt')
Note 'Backup' ($lines -join "`n")
Write-Host "Report and backup: $Out"
