# Builds one country's offline map pack: OpenMapTiles vector tiles in an MBTiles file, made with planetiler
# from the Geofabrik extract. Buildings and house numbers are left out, then slim-map-pack.py drops what the unit
# neither draws nor searches (other languages, mountain peaks, airports, zoom 14 landcover).
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
    [string] $ExcludeLayers = "building,housenumber",
    # the area the Natural Earth sea outlines are cut to when the OSM sea outlines cannot be downloaded
    [string] $SeaBbox = "19.5,41.5,31.5,49"
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
New-Item -ItemType Directory -Force (Join-Path $WorkDir "raw") | Out-Null
$raw = Join-Path $WorkDir "raw/$Code.mbtiles"
$sources = Join-Path $WorkDir "sources"
New-Item -ItemType Directory -Force $sources | Out-Null
$water = Join-Path $sources "water-polygons-split-3857.zip"
$naturalEarth = Join-Path $sources "natural_earth_vector.sqlite.zip"
if (-not (Test-Path $water) -and (Test-Path $naturalEarth)) {
    try { Invoke-WebRequest "https://osmdata.openstreetmap.de/download/water-polygons-split-3857.zip" -Method Head -TimeoutSec 30 -UseBasicParsing | Out-Null }
    catch {
        "OSM sea outlines unreachable: cutting the Natural Earth ocean to $SeaBbox instead"
        python -I (Join-Path $PSScriptRoot "water-from-natural-earth.py") $naturalEarth $sources ($SeaBbox -split ",")
        if ($LASTEXITCODE -ne 0) { throw "sea outlines failed" }
    }
}
# with every source on disk, planetiler is not asked to download (it would check each source online)
$needed = "natural_earth_vector.sqlite.zip", "lake_centerline.shp.zip", "water-polygons-split-3857.zip"
$download = @($needed | Where-Object { -not (Test-Path (Join-Path $sources $_)) }).Count -gt 0
$flags = @("--download_dir=$sources", "--osm_path=$pbf", "--exclude-layers=$ExcludeLayers", "--output=$raw", "--force")
if ($download) { $flags = @("--download") + $flags }
java -Xmx2g -jar $jar @flags
if ($LASTEXITCODE -ne 0) { throw "planetiler failed" }
python -I (Join-Path $PSScriptRoot "slim-map-pack.py") $raw $out
if ($LASTEXITCODE -ne 0) { throw "slimming failed" }
"{0}: {1:N0} MB" -f $Code, ((Get-Item $out).Length / 1MB)
