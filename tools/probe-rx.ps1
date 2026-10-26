# probe-rx.ps1 -- measure the PC source's UDP stream quality without any hub.
#
# Binds <port> (audio) and <port+2> (handshake), answers CONN-REQ with CONN-ACK
# (sent back to the requester's ephemeral port, like a real hub does), then
# records every audio packet for a few seconds and reports:
#   * packets received / effective pps
#   * seq gaps            (network loss or receiver too slow)
#   * timeline holes      (source-side starvation: seq advances by 1 but
#                          sample_pos jumps by 480 -- a 5 ms HOLE in the
#                          timeline, heard as a click / crackle on the phone)
#   * zero-payload ratio  (is the capture producing real audio?)
#
# Launching the source is done through cmd.exe "start /b": PowerShell 5.1's
# native-argument marshalling corrupts this argv combination (known quirk),
# while cmd passes the command line through verbatim.
#
# Live loopback capture only produces data while something is actually
# rendering, so -PlayTone plays a generated WAV through the default device.
#
# ASCII only on purpose: Windows PowerShell 5.1 reads BOM-less UTF-8 as ANSI.
param(
  [int]$Port = 47005,
  [int]$Secs = 12,
  [int]$CapMs = 0,
  [switch]$PlayTone,
  [switch]$NoStart,
  [switch]$ToneSource
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root
$SourceExe = Join-Path $Root 'build\audiohub-source.exe'
if (-not (Test-Path $SourceExe)) { throw "missing $SourceExe" }

$before = @(Get-Process audiohub-source -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Id)

# --- optional: make sure something is rendering, so loopback delivers data ---
$player = $null
if ($PlayTone) {
  $wav = Join-Path $Root 'build\probe-tone.wav'
  if (-not (Test-Path $wav)) {
    $rate = 48000; $wavSecs = 2; $n = $rate * $wavSecs
    $fs = [System.IO.File]::Create($wav); $bw = New-Object System.IO.BinaryWriter($fs)
    $dataBytes = $n * 4
    $bw.Write([System.Text.Encoding]::ASCII.GetBytes('RIFF')); $bw.Write([uint32](36 + $dataBytes))
    $bw.Write([System.Text.Encoding]::ASCII.GetBytes('WAVEfmt ')); $bw.Write([uint32]16)
    $bw.Write([uint16]1); $bw.Write([uint16]2); $bw.Write([uint32]$rate)
    $bw.Write([uint32]($rate * 4)); $bw.Write([uint16]4); $bw.Write([uint16]16)
    $bw.Write([System.Text.Encoding]::ASCII.GetBytes('data')); $bw.Write([uint32]$dataBytes)
    for ($i = 0; $i -lt $n; $i++) {
      $v = [int16](12000 * [math]::Sin(2 * [math]::PI * 440.0 * $i / $rate))
      $bw.Write($v); $bw.Write($v)
    }
    $bw.Flush(); $bw.Close(); $fs.Close()
  }
  $player = New-Object System.Media.SoundPlayer $wav
  $player.PlayLooping()
  Start-Sleep -Milliseconds 300
}

# --- sockets ---
$endpoint = New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Loopback, ($Port + 2))
$audio = New-Object System.Net.Sockets.UdpClient($Port)
$audio.Client.ReceiveBufferSize = 16 * 1024 * 1024
$audio.Client.ReceiveTimeout = 200
$hs = New-Object System.Net.Sockets.UdpClient(($Port + 2))
$hs.Client.ReceiveTimeout = 1
$hsEnd = New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Loopback, 0)
$ack = [System.Text.Encoding]::ASCII.GetBytes("CONN-ACK|probe|1`n")

# --- source process ---
$logPath = 'build\probe-source.log'
if (-not $NoStart) {
  Remove-Item $logPath -ErrorAction SilentlyContinue
  $srcArgs = @('--hub', ('127.0.0.1:' + $Port), '--handshake', '--device-name', 'probe',
               '--stream-id', '1', '--log', $logPath)
  if ($CapMs -gt 0) { $srcArgs += @('--cap-ms', "$CapMs") }
  if ($ToneSource) { $srcArgs += @('--tone', '440') }
  $null = Start-Process -FilePath $SourceExe -ArgumentList $srcArgs -WindowStyle Hidden -PassThru
  Start-Sleep -Milliseconds 900
}

$sw = [System.Diagnostics.Stopwatch]::StartNew()
$count = 0; $lastSeq = -1; $lastPos = -1
$seqBad = 0; $holeCount = 0; $holeSamples = 0; $maxHole = 0
$holesAt = @()
$zeroPkts = 0; $nonzeroPkts = 0; $firstPos = 0; $lastPosSeen = 0
$ackSent = 0; $reqSeen = 0
$latin1 = [System.Text.Encoding]::GetEncoding(28591)

while ($sw.Elapsed.TotalSeconds -lt $Secs) {
  if ($hs.Client.Available -gt 0) {
    try {
      $req = $hs.Receive([ref]$hsEnd)
      if ($req.Length -gt 0) {
        $reqSeen++
        [void]$hs.Send($ack, $ack.Length, $hsEnd)
        $ackSent++
      }
    } catch { }
  }

  if ($audio.Client.Available -le 0) { Start-Sleep -Milliseconds 1; continue }

  while ($audio.Client.Available -gt 0) {
    try { $data = $audio.Receive([ref]$endpoint) } catch { break }
    if ($data.Length -lt 17) { continue }
    if ((($data[0] -band 0xF0) -shr 4) -ne 1) { continue }

    $seq = [BitConverter]::ToUInt32($data, 4)
    $pos = [BitConverter]::ToUInt32($data, 8)
    if ($lastSeq -ge 0) {
      if ([int32]([int64]$seq - [int64]$lastSeq) -ne 1) { $seqBad++ }
      $dp = [int64]$pos - [int64]$lastPos
      if ($dp -ne 240) {
        $holeCount++
        $delta = [math]::Abs($dp - 240)
        $holeSamples += $delta
        if ($delta -gt $maxHole) { $maxHole = $delta }
        if ($holesAt.Count -lt 8) { $holesAt += [math]::Round($sw.Elapsed.TotalSeconds, 2).ToString() + 's:dp=' + $dp }
      }
    }
    $lastSeq = $seq; $lastPos = $pos
    $count++
    if ($firstPos -eq 0) { $firstPos = $pos }
    $lastPosSeen = $pos

    # fast all-zero check: silence is zero everywhere; one regex beats a per-byte loop
    $txt = $latin1.GetString($data, 16, $data.Length - 16)
    if ($txt -match '[^\x00]') { $nonzeroPkts++ } else { $zeroPkts++ }
    if ($sw.Elapsed.TotalSeconds -ge $Secs) { break }
  }
}

$elapsed = $sw.Elapsed.TotalSeconds
$span = [int64]$lastPosSeen - [int64]$firstPos
if ($span -lt 0) { $span += 4294967296 }
$rate = if ($elapsed -gt 0) { $count / $elapsed } else { 0 }

Write-Output ''
Write-Output ('elapsed          : {0:N2} s' -f $elapsed)
Write-Output ('handshake reqs   : {0}  (acks sent {1})' -f $reqSeen, $ackSent)
Write-Output ('packets          : {0}  ({1:N1} pps, expect 200)' -f $count, $rate)
Write-Output ('seq gaps         : {0}' -f $seqBad)
Write-Output ('timeline holes   : {0}  (lost {1} samples = {2:N1} ms, max hole {3} samples)' -f $holeCount, $holeSamples, ($holeSamples / 48.0), $maxHole)
Write-Output ('holes per second : {0:N2}' -f [math]::Round($holeCount / [math]::Max($elapsed, 0.001), 2))
Write-Output ('nonzero payloads : {0} / {1}' -f $nonzeroPkts, $count)
Write-Output ('timeline rate    : {0:N0} samples/s (expect 48000)' -f ($span / [math]::Max($elapsed, 0.001)))
if ($holesAt.Count -gt 0) { Write-Output ('first holes      : {0}' -f ($holesAt -join ' | ')) }

if ($player) { $player.Stop() }
Start-Sleep -Milliseconds 100
if (-not $NoStart) {
  Get-Process audiohub-source -ErrorAction SilentlyContinue |
    Where-Object { $before -notcontains $_.Id } | Stop-Process -Force -ErrorAction SilentlyContinue
}
if ((-not $NoStart) -and (Test-Path $logPath)) {
  Write-Output '--- source log (tail) ---'
  Get-Content $logPath -Encoding UTF8 -ErrorAction SilentlyContinue | Select-Object -Last 6
}