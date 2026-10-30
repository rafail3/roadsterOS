param(
    [Parameter(Position = 0)] [string] $Event,
    [Parameter(Position = 1)] [string] $Json = "{}",
    [string] $File
)
if ($File) {
    adb shell mkdir -p /data/local/tmp/rdstr | Out-Null
    adb push $File /data/local/tmp/rdstr/ | Out-Null
    $remote = "/data/local/tmp/rdstr/" + (Split-Path $File -Leaf)
    adb shell am broadcast -a ro.rdstr.sim -n ro.rdstr.os/ro.rdstr.carsim.SimReceiver -e file $remote
    exit
}
$payload = $Json.TrimStart("{")
$body = if ($payload.TrimEnd("}") -eq "") { "{`"type`":`"$Event`"}" } else { "{`"type`":`"$Event`"," + $payload }
adb shell am broadcast -a ro.rdstr.sim -n ro.rdstr.os/ro.rdstr.carsim.SimReceiver -e event "'$body'"
