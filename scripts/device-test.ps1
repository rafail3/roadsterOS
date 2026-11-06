<#
.SYNOPSIS
    Runs the automatic part of the display checks on a connected unit and captures every screen.

.DESCRIPTION
    Over adb: checks HOME and the map pack, measures cold start (median of three) and memory, then opens each screen in
    the four looks (dark/light x solid/glassy) through the app's adb test commands (ServiceReceiver) and saves a
    screenshot of each. Ends with the theme on Auto and the design on Solid, and scans logcat for crashes.
    Results go to build/device-test/<time>/ (git-ignored; screenshots may show phone names and contacts).

.EXAMPLE
    .\scripts\device-test.ps1 -Unit 192.168.43.120
#>
param(
    [Parameter(Mandatory = $true)] [string] $Unit,
    [string] $Package = 'ro.rdstr.os',
    [string] $Out = (Join-Path $PSScriptRoot ("..\build\device-test\" + (Get-Date -Format 'yyyyMMdd-HHmmss'))),
    [int] $SettleMs = 1500
)

$ErrorActionPreference = 'Stop'
$target = if ($Unit -match ':') { $Unit } else { "${Unit}:5555" }
$results = [System.Collections.Generic.List[object]]::new()
New-Item -ItemType Directory -Force $Out | Out-Null

function Adb([string[]] $arguments) {
    $o = & adb -s $target @arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "adb $($arguments -join ' ') failed: $o" }
    return $o
}
function Check([string] $name, [bool] $ok, [string] $detail) {
    $results.Add([pscustomobject]@{ Check = $name; Result = $(if ($ok) { 'PASS' } else { 'FAIL' }); Detail = $detail })
    Write-Host ("{0,-4} {1} - {2}" -f $(if ($ok) { 'PASS' } else { 'FAIL' }), $name, $detail)
}
function Send([string] $kind, [string] $json) {
    # the JSON goes through adb shell as one quoted argument
    Adb @('shell', 'am', 'broadcast', '-a', "ro.rdstr.$kind", '-n', "$Package/.ServiceReceiver", '--es', 'json', "'$json'") | Out-Null
}
function Shot([string] $name) {
    Start-Sleep -Milliseconds $SettleMs
    Adb @('shell', 'screencap', '-p', "/data/local/tmp/rdstr-shot.png") | Out-Null
    Adb @('pull', "/data/local/tmp/rdstr-shot.png", (Join-Path $Out "$name.png")) | Out-Null
}

$connect = & adb connect $target 2>&1
if ($connect -notmatch 'connected') { throw "adb connect $target failed: $connect" }
$model = (Adb @('shell', 'getprop', 'ro.product.model')).Trim()
$release = (Adb @('shell', 'getprop', 'ro.build.version.release')).Trim()
Write-Host "Unit: $model, Android $release"
Adb @('logcat', '-c') | Out-Null

# A: install state
$homeApp = (Adb @('shell', 'cmd', 'package', 'resolve-activity', '--brief', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')) -join ' '
Check 'HOME is roadsterOS' ($homeApp -match "$Package/") $homeApp.Trim()
$pack = (Adb @('shell', "stat -c %s /sdcard/Android/data/$Package/files/maps/RO.mbtiles 2>/dev/null || echo 0")).Trim()
Check 'Romania map pack present' ([int64] $pack -gt 0) "$pack bytes"

# C: cold start (median of three) and memory after the screen settles
$times = foreach ($i in 1..3) {
    Adb @('shell', 'am', 'force-stop', $Package) | Out-Null
    $line = (Adb @('shell', 'am', 'start', '-W', '-n', "$Package/.HomeActivity")) | Select-String 'TotalTime'
    [int](($line -split ':')[1].Trim())
}
$median = ($times | Sort-Object)[1]
Check 'Cold start under 1000 ms' ($median -le 1000) ("median $median ms ({0})" -f ($times -join ', '))
Start-Sleep -Seconds 8
$mem = Adb @('shell', 'dumpsys', 'meminfo', $Package) | Select-String 'TOTAL PSS:|TOTAL\s+\d+' | Select-Object -First 1
$pss = [math]::Round([int](($mem -replace '[^0-9 ]', '').Trim() -split '\s+')[0] / 1024, 1)
Check 'Memory under 30 MB PSS' ($pss -le 30) "$pss MB"

# the screens in the four looks
$screens = [ordered]@{
    'home'      = @()
    'drawer'    = @('{"type":"OpenDrawer"}')
    'devices'   = @('{"type":"OpenDevices"}')
    'networks'  = @('{"type":"OpenNetworks"}')
    'recents'   = @('{"type":"OpenApp","app":"MUSIC"}', '{"type":"OpenApp","app":"RADIO"}', '{"type":"Home"}', '{"type":"OpenRecents"}')
    'music'     = @('{"type":"OpenApp","app":"MUSIC"}')
    'radio'     = @('{"type":"OpenApp","app":"RADIO"}')
    'phone'     = @('{"type":"OpenApp","app":"PHONE"}')
    'maps'      = @('{"type":"OpenApp","app":"MAPS"}')
    'settings'  = @('{"type":"OpenApp","app":"SETTINGS"}')
    'settings-wifi'      = @('{"type":"OpenApp","app":"SETTINGS"}', '{"type":"OpenSettingsPage","page":"wifi"}')
    'settings-bluetooth' = @('{"type":"OpenApp","app":"SETTINGS"}', '{"type":"OpenSettingsPage","page":"bluetooth"}')
    'settings-about'     = @('{"type":"OpenApp","app":"SETTINGS"}', '{"type":"OpenSettingsPage","page":"about"}')
    'sound'     = @('{"type":"OpenSound"}')
}
$reset = @('{"type":"CloseSound"}', '{"type":"CloseDevices"}', '{"type":"CloseNetworks"}', '{"type":"CloseRecents"}', '{"type":"CloseDrawer"}', '{"type":"CloseAllApps"}', '{"type":"Home"}')
foreach ($theme in 'DARK', 'LIGHT') {
    foreach ($design in 'SOLID', 'GLASSY') {
        Send 'SHELL' "{`"type`":`"SetTheme`",`"mode`":`"$theme`"}"
        Send 'SHELL' "{`"type`":`"SetDesign`",`"design`":`"$design`"}"
        foreach ($s in $screens.GetEnumerator()) {
            foreach ($a in $reset) { Send 'SHELL' $a }
            foreach ($a in $s.Value) { Send 'SHELL' $a; Start-Sleep -Milliseconds 300 }
            Shot ("{0}-{1}-{2}" -f $s.Key, $theme.ToLower(), $design.ToLower())
        }
    }
}
foreach ($a in $reset) { Send 'SHELL' $a }
Send 'SHELL' '{"type":"SetTheme","mode":"AUTO"}'
Send 'SHELL' '{"type":"SetDesign","design":"SOLID"}'
$shots = (Get-ChildItem $Out -Filter *.png).Count
Check 'Screenshots taken' ($shots -eq $screens.Count * 4) "$shots of $($screens.Count * 4) in $Out"

# crashes and errors from our app during the run
$log = Adb @('logcat', '-d', '-v', 'brief')
$log | Set-Content -Encoding utf8 (Join-Path $Out 'logcat.txt')
$crashes = $log | Select-String -Pattern 'FATAL EXCEPTION|ANR in ro\.rdstr|Process: ro\.rdstr'
Check 'No crash or ANR' ($crashes.Count -eq 0) ("{0} lines (logcat.txt)" -f $crashes.Count)
$tests = $log | Select-String -Pattern 'rdstr\.test'
Check 'Test commands accepted' ($tests.Count -eq 0) ("{0} rejected (logcat.txt, tag rdstr.test)" -f $tests.Count)

$results | Format-Table -AutoSize | Out-String | Set-Content -Encoding utf8 (Join-Path $Out 'summary.txt')
$failed = @($results | Where-Object Result -eq 'FAIL').Count
Write-Host ("`n{0} checks, {1} failed. Results in {2}" -f $results.Count, $failed, $Out)
if ($failed -gt 0) { exit 1 }
