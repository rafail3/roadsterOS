$sdk = $env:ANDROID_HOME
if (-not $sdk) { throw "ANDROID_HOME is not set" }
$name = "rdstr"
& "$sdk\cmdline-tools\latest\bin\avdmanager.bat" create avd -n $name -k "system-images;android-27;google_apis;x86" -d "7in WSVGA (Tablet)" --force
$ini = "$env:USERPROFILE\.android\avd\$name.avd\config.ini"
$overrides = Get-Content "$PSScriptRoot\avd\config.ini" | Where-Object { $_ -match "=" }
$current = Get-Content $ini | Where-Object { $line = $_; -not ($overrides | Where-Object { $line.StartsWith(($_ -split "=")[0] + "=") }) }
Set-Content -Path $ini -Value ($current + $overrides) -Encoding ascii
Write-Host "AVD '$name' ready. Start it with: & `"$sdk\emulator\emulator.exe`" -avd $name"
