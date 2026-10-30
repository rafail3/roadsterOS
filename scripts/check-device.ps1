$pkg = "ro.rdstr.os"
$mem = adb shell dumpsys meminfo $pkg | Select-String "TOTAL PSS:|TOTAL\s+\d+" | Select-Object -First 1
$pss = [int](($mem -replace "[^0-9 ]", "").Trim() -split "\s+")[0]
$pssMb = [math]::Round($pss / 1024, 1)
adb shell am force-stop $pkg | Out-Null
$start = adb shell am start -W -n "$pkg/.HomeActivity" | Select-String "TotalTime"
$ms = [int](($start -split ":")[1].Trim())
$packages = (adb shell pm list packages -e | Measure-Object -Line).Lines
"PSS: $pssMb MB  (limit 30)  -> " + $(if ($pssMb -le 30) { "PASS" } else { "FAIL" })
"Cold start: $ms ms (limit 1000) -> " + $(if ($ms -le 1000) { "PASS" } else { "FAIL" })
"Enabled packages: $packages"
