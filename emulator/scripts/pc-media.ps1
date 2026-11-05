# Reads the media session Windows shows for the playing app (for example a browser tab with a music site)
# and prints one JSON line per change: track, cover, playback. Reads play, pause, toggle, next and prev
# lines on stdin and sends them to that session. Used by the emulator's laptop media source.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Runtime.WindowsRuntime
$asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
function Await($op, $type) { $t = $asTask.MakeGenericMethod($type).Invoke($null, @($op)); $t.Wait(-1) | Out-Null; $t.Result }

[Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime] | Out-Null
[Windows.Storage.Streams.IRandomAccessStreamWithContentType, Windows.Storage.Streams, ContentType = WindowsRuntime] | Out-Null
$manager = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])
$propsType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties]
$streamType = [Windows.Storage.Streams.IRandomAccessStreamWithContentType]

function Emit($obj) { [Console]::Out.WriteLine(($obj | ConvertTo-Json -Compress)); [Console]::Out.Flush() }

# PowerShell 5 cannot call the stream adapter on a WinRT object directly; reflection binds it at run time
$asStreamForRead = [System.IO.WindowsRuntimeStreamExtensions].GetMethod('AsStreamForRead', [Type[]]@([Windows.Storage.Streams.IInputStream]))

function CoverOf($props) {
  if (-not $props.Thumbnail) { return $null }
  $stream = Await ($props.Thumbnail.OpenReadAsync()) $streamType
  $net = $asStreamForRead.Invoke($null, @($stream))
  $mem = New-Object System.IO.MemoryStream
  $net.CopyTo($mem)
  $net.Dispose()
  $bytes = $mem.ToArray()
  if ($bytes.Length -eq 0) { return $null }
  $type = if ($bytes[0] -eq 0xFF -and $bytes[1] -eq 0xD8) { 'image/jpeg' } else { 'image/png' }
  "data:$type;base64," + [Convert]::ToBase64String($bytes)
}

$stdin = New-Object System.IO.StreamReader([Console]::OpenStandardInput())
$pending = $stdin.ReadLineAsync()
$lastTrack = ''; $lastCover = ''; $coverChecks = 0
$lastPlaying = $null; $lastPos = -1; $lastAt = [DateTime]::UtcNow; $hadSession = $false
$chosenId = $null

# Follow one session: the one already followed while it exists, unless another app starts playing.
# Windows' own "current session" moves to any other paused tab when this one pauses, so it is not used alone.
function Pick() {
  $all = @($manager.GetSessions())
  if ($all.Count -eq 0) { return $null }
  $playing = $all | Where-Object { $_.GetPlaybackInfo().PlaybackStatus -eq 'Playing' }
  $chosen = $all | Where-Object { $_.SourceAppUserModelId -eq $script:chosenId } | Select-Object -First 1
  $other = $playing | Where-Object { $_.SourceAppUserModelId -ne $script:chosenId } | Select-Object -First 1
  $pick = if ($other -and -not ($chosen -and $chosen.GetPlaybackInfo().PlaybackStatus -eq 'Playing')) { $other }
          elseif ($chosen) { $chosen }
          elseif ($playing) { @($playing)[0] }
          else { $manager.GetCurrentSession() }
  if (-not $pick) { $pick = $all[0] }
  $script:chosenId = $pick.SourceAppUserModelId
  $pick
}

while ($true) {
  $session = Pick

  if ($pending.IsCompleted) {
    $cmd = $pending.Result
    if ($null -eq $cmd) { break }
    if ($session) {
      switch ($cmd.Trim()) {
        'play' { $session.TryPlayAsync() | Out-Null }
        'pause' { $session.TryPauseAsync() | Out-Null }
        'toggle' { $session.TryTogglePlayPauseAsync() | Out-Null }
        'next' { $session.TrySkipNextAsync() | Out-Null }
        'prev' { $session.TrySkipPreviousAsync() | Out-Null }
      }
    }
    $pending = $stdin.ReadLineAsync()
  }

  if (-not $session) {
    if ($hadSession) { Emit @{ type = 'session'; active = $false }; $hadSession = $false; $lastTrack = '' }
    Start-Sleep -Milliseconds 500
    continue
  }
  if (-not $hadSession) { Emit @{ type = 'session'; active = $true; app = $session.SourceAppUserModelId }; $hadSession = $true }

  try {
    $props = Await ($session.TryGetMediaPropertiesAsync()) $propsType
    $timeline = $session.GetTimelineProperties()
    $playing = $session.GetPlaybackInfo().PlaybackStatus -eq 'Playing'
    $durationMs = [int64]($timeline.EndTime - $timeline.StartTime).TotalMilliseconds

    $key = "$($props.Title)|$($props.Artist)|$($props.AlbumTitle)"
    if ($key -ne $lastTrack -and $props.Title) {
      $lastTrack = $key; $coverChecks = 20; $lastCover = ''
      Emit @{ type = 'track'; title = $props.Title; artist = $props.Artist; album = $props.AlbumTitle; durationMs = $durationMs }
    }
    # the session reports its position only on changes; carry it forward while playing
    $pos = $timeline.Position.TotalMilliseconds
    if ($playing) { $pos += ([DateTimeOffset]::UtcNow - $timeline.LastUpdatedTime).TotalMilliseconds }
    $expected = if ($lastPlaying) { $lastPos + ([DateTime]::UtcNow - $lastAt).TotalMilliseconds } else { $lastPos }
    if ($playing -ne $lastPlaying -or [Math]::Abs($pos - $expected) -gt 2500) {
      Emit @{ type = 'playback'; playing = $playing; positionMs = [int64][Math]::Max(0, [Math]::Min($pos, $durationMs)) }
      $lastPlaying = $playing; $lastPos = $pos; $lastAt = [DateTime]::UtcNow
    }
  } catch {
    # the session can change or close between two reads; the next round starts fresh
  }
  # the cover is read on its own: a failed read of the image never holds back a play or pause report
  try {
    # sites often set the artwork a moment after the title: look again for a few rounds after a track change
    if ($coverChecks -gt 0) {
      $coverChecks--
      $cover = CoverOf $props
      if ($cover -and $cover -ne $lastCover) { $lastCover = $cover; Emit @{ type = 'cover'; title = $props.Title; url = $cover } }
    }
  } catch { $coverChecks = [Math]::Max(0, $coverChecks - 1) }
  Start-Sleep -Milliseconds 150
}
