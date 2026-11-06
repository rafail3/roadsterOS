<#
.SYNOPSIS
    Installs roadsterOS on a unit over adb and copies the preinstalled map pack.

.DESCRIPTION
    Connects to the unit, installs the APK, makes roadsterOS the HOME app and copies the Romania map pack into the
    app's external files folder (Android/data/ro.rdstr.os/files/maps), where the map reads its packs. The copy is
    checked by size. Without a pack file the map is left out; the setup's map step downloads it over Wi-Fi.

.EXAMPLE
    .\install\install.ps1 -Unit 192.168.43.120
    .\install\install.ps1 -Unit 192.168.43.120 -Apk app\build\outputs\apk\device\release\app-device-release.apk -MapPack D:\maps\RO.mbtiles
#>
param(
    [Parameter(Mandatory = $true)] [string] $Unit,
    [string] $Apk = (Join-Path $PSScriptRoot '..\app\build\outputs\apk\device\debug\app-device-debug.apk'),
    [string] $MapPack = (Join-Path $PSScriptRoot '..\..\map-build\packs\RO.mbtiles'),
    [string] $Package = 'ro.rdstr.os'
)

$ErrorActionPreference = 'Stop'
$target = if ($Unit -match ':') { $Unit } else { "${Unit}:5555" }

function Adb([string[]] $arguments) {
    $out = & adb -s $target @arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "adb $($arguments -join ' ') failed: $out" }
    return $out
}

Write-Host "Connecting to $target"
$connect = & adb connect $target 2>&1
if ($connect -notmatch 'connected') { throw "adb connect $target failed: $connect" }

if (-not (Test-Path $Apk)) { throw "APK not found: $Apk (build it with ./gradlew assembleDeviceDebug)" }
Write-Host "Installing $(Split-Path $Apk -Leaf)"
Adb @('install', '-r', $Apk) | Out-Null
Adb @('shell', 'cmd', 'package', 'set-home-activity', "$Package/.HomeActivity") | Out-Null

if (Test-Path $MapPack) {
    $name = Split-Path $MapPack -Leaf
    $dir = "/sdcard/Android/data/$Package/files/maps"
    $size = (Get-Item $MapPack).Length
    Write-Host ("Copying map pack {0} ({1:N0} MB) to {2}" -f $name, ($size / 1MB), $dir)
    Adb @('shell', 'mkdir', '-p', $dir) | Out-Null
    # written under a temporary name and renamed once complete, so the map never opens a half-copied pack
    Adb @('push', $MapPack, "$dir/$name.part") | Out-Null
    $copied = (Adb @('shell', 'stat', '-c', '%s', "$dir/$name.part")).Trim()
    if ([int64] $copied -ne $size) { throw "map pack copy is $copied bytes, expected $size" }
    Adb @('shell', 'mv', "$dir/$name.part", "$dir/$name") | Out-Null
    Write-Host "Map pack copied and checked"
} else {
    Write-Host "No map pack at $MapPack; the setup downloads it over Wi-Fi"
}

Write-Host "Done"
