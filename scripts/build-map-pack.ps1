# Builds one country's offline map pack: OpenMapTiles vector tiles in an MBTiles file, made with planetiler
# from the Geofabrik extract. Buildings and house numbers are left out to keep the pack small.
#
#   ./scripts/build-map-pack.ps1 -Area europe/romania -Code RO
#
# Output: <WorkDir>/packs/<Code>.mbtiles. Needs Java 21. The first run downloads planetiler and its shared
# sources (water polygons, Natural Earth, lake centerlines) into <WorkDir>, then reuses them.

param(
    [Parameter(Mandatory)] [string] $Area,
    [Parameter(Mandatory)] [string] $Code,
    [string] $WorkDir = "",
    [string] $PlanetilerVersion = "v0.10.2",
    [string] $ExcludeLayers = "building,housenumber"
)

$ErrorActionPreference = "Stop"
if (-not $WorkDir) { $WorkDir = Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) "../../map-build" }
$WorkDir = [System.IO.Path]::GetFullPath($WorkDir)
New-Item -ItemType Directory -Force $WorkDir, (Join-Path $WorkDir "packs") | Out-Null
$jar = Join-Path $WorkDir "planetiler.jar"
if (-not (Test-Path $jar)) {
    gh release download $PlanetilerVersion --repo onthegomap/planetiler --pattern planetiler.jar --dir $WorkDir
}

$name = ($Area -split "/")[-1]
$pbf = Join-Path $WorkDir "$name-latest.osm.pbf"
if (-not (Test-Path $pbf)) {
    Invoke-WebRequest "https://download.geofabrik.de/$Area-latest.osm.pbf" -OutFile $pbf
}

$out = Join-Path $WorkDir "packs/$Code.mbtiles"
$sources = Join-Path $WorkDir "sources"
java -Xmx2g -jar $jar --download "--download_dir=$sources" "--osm_path=$pbf" `
    "--exclude-layers=$ExcludeLayers" "--output=$out" --force
if ($LASTEXITCODE -ne 0) { throw "planetiler failed" }
"{0}: {1:N0} MB" -f $Code, ((Get-Item $out).Length / 1MB)
