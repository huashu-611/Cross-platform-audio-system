# Graph replication smoke test: two real hub processes on one machine exchange
# GRAPH frames over the handshake port (audioPort + 2) and switch the hub.
# This validates the UDP wire format + merge/LWW + broadcast end to end, i.e.
# the "shared network graph" behaviour, without any audio devices involved.
#
# Requires the optional test hook AUDIOHUB_LOCAL_IP (see docs/GRAPH-PROTOCOL.md
# section 10.1): both hubs advertise 127.0.0.1 so the test does not depend on
# the machine's LAN interface or the Windows firewall.
#
# Usage: powershell -NoProfile -File tools/graph-smoke.ps1
# (build first: powershell -NoProfile -File tools/build.ps1)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

$HubExe = Join-Path $Root 'build\audiohub-hub.exe'
if (-not (Test-Path $HubExe)) { throw "missing $HubExe, run tools/build.ps1 first" }

$PortA = 47000
$HttpA = 4780
$PortB = 47100
$HttpB = 4790

$env:AUDIOHUB_LOCAL_IP = '127.0.0.1'

$tempx = [System.IO.Path]::GetTempPath()
$logA = Join-Path $tempx 'audiohub-graph-smoke-a.log'
$logB = Join-Path $tempx 'audiohub-graph-smoke-b.log'
Remove-Item $logA, $logB -ErrorAction SilentlyContinue

function Start-TestHub([int]$port, [int]$http, [string]$name, [string]$log) {
  # --no-discovery: keep the two test instances from fighting over port 47300
  $args = @('--port', "$port", '--http-port', "$http", '--name', $name,
            '--no-discovery', '--log', $log, '--auto-accept')
  $p = Start-Process -FilePath $HubExe -ArgumentList $args -PassThru -WindowStyle Hidden
  return $p
}

function Wait-Http([int]$http, [int]$timeoutSec) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  $lastErr = ''
  while ((Get-Date) -lt $deadline) {
    try {
      $s = Invoke-RestMethod -Uri "http://127.0.0.1:$http/api/state" -TimeoutSec 1
      if ($s) { return $s }
    } catch { $lastErr = $_.Exception.Message; Start-Sleep -Milliseconds 150 }
  }
  throw "hub on http port $http did not come up ($lastErr)"
}

function Get-Graph([int]$http) {
  $s = Invoke-RestMethod -Uri "http://127.0.0.1:$http/api/state" -TimeoutSec 2
  if (-not $s.graph) { throw "state from $http has no 'graph' field (core not updated?)" }
  return $s.graph
}

function Post-Json([int]$http, [string]$path, [string]$body) {
  return Invoke-RestMethod -Uri "http://127.0.0.1:$http$path" -Method Post `
    -ContentType 'application/json; charset=utf-8' `
    -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 3
}

function Send-GraphFrame([int]$targetPort, [string]$frame) {
  $udp = New-Object System.Net.Sockets.UdpClient
  try {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($frame + "`n")
    $ep = New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Loopback, $targetPort)
    [void]$udp.Send($bytes, $bytes.Length, $ep)
  } finally { $udp.Close() }
}

function Send-SplitBodyPost([int]$http, [string]$path, [string]$body) {
  # Regression: some clients send headers and body as two separate TCP segments.
  # The HTTP server must honour Content-Length instead of returning after headers.
  $client = New-Object System.Net.Sockets.TcpClient
  try {
    $client.Connect('127.0.0.1', $http)
    $stream = $client.GetStream()
    $bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($body)
    $header = "POST $path HTTP/1.1`r`nHost: 127.0.0.1`r`n" +
              "Content-Type: application/json; charset=utf-8`r`n" +
              "Content-Length: $($bodyBytes.Length)`r`nConnection: close`r`n`r`n"
    $headerBytes = [System.Text.Encoding]::ASCII.GetBytes($header)
    $stream.Write($headerBytes, 0, $headerBytes.Length)
    $stream.Flush()
    Start-Sleep -Milliseconds 150
    $stream.Write($bodyBytes, 0, $bodyBytes.Length)
    $stream.Flush()
    $reader = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::UTF8)
    return $reader.ReadToEnd()
  } finally { $client.Close() }
}

function Get-ResponseBody([string]$response) {
  $split = $response.IndexOf("`r`n`r`n")
  if ($split -lt 0) { return $response }
  return $response.Substring($split + 4)
}

function Wait-Graph([int]$http, [scriptblock]$predicate, [int]$timeoutSec, [string]$what) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  $last = $null
  while ((Get-Date) -lt $deadline) {
    $last = Get-Graph $http
    if (& $predicate $last) { return $last }
    Start-Sleep -Milliseconds 120
  }
  throw ("timeout waiting for: " + $what + "`nlast graph: " + ($last | ConvertTo-Json -Depth 6))
}

$failures = New-Object System.Collections.Generic.List[string]
function Check([bool]$cond, [string]$what) {
  if ($cond) { Write-Host ("  [PASS] " + $what) }
  else { Write-Host ("  [FAIL] " + $what); $script:failures.Add($what) }
}

$procA = $null
$procB = $null
try {
  Write-Host '==> start two hubs (A: 47000, B: 47100, both advertise 127.0.0.1)'
  $procA = Start-TestHub $PortA $HttpA 'SmokeA' $logA
  $procB = Start-TestHub $PortB $HttpB 'SmokeB' $logB
  $null = Wait-Http $HttpA 10
  $null = Wait-Http $HttpB 10

  $graphA = Get-Graph $HttpA
  $graphB = Get-Graph $HttpB
  $selfA = $graphA.selfId
  $selfB = $graphB.selfId
  Write-Host ("  A selfId=" + $selfA + "  B selfId=" + $selfB)
  Check ($graphA.isHub -eq $true) 'A starts as its own hub'
  Check ($graphB.isHub -eq $true) 'B starts as its own hub'
  Check ($graphA.nodes.Count -eq 1) 'A graph starts with only itself'
  Check ($graphB.nodes.Count -eq 1) 'B graph starts with only itself'
  Check ($graphA.version -ge 1) 'A graph version starts at >= 1'
  Check ($selfA -eq ('127.0.0.1:' + $PortA)) 'A advertises AUDIOHUB_LOCAL_IP'

  Write-Host '==> inject a newer GRAPH frame into A: learns B, hub stays A'
  # version=2 > A's version -> adopted; hubId points back to A so A stays hub.
  $frame = 'GRAPH|2|' + $selfB + '|' + $selfA + '|' + $selfB + ',SmokeB,pc,' + $PortB + ',0'
  Send-GraphFrame ($PortA + 2) $frame
  $graphA = Wait-Graph $HttpA { param($g) ($g.nodes | Where-Object { $_.id -eq $script:selfB }) } 5 'A learns node B from a raw GRAPH frame'
  $nodeB = $graphA.nodes | Where-Object { $_.id -eq $selfB }
  Check ($nodeB.name -eq 'SmokeB') 'node name decoded from wire format'
  Check ($nodeB.kind -eq 'pc') 'node kind decoded from wire format'
  Check ($nodeB.audioPort -eq $PortB) 'node audioPort decoded from wire format'
  Check ($graphA.hubId -eq $selfA) 'hub unchanged (frame said hub=A)'

  Write-Host '==> A learned B; B also gets A graph via A broadcast (version 2, hub=A)'
  $graphB = Wait-Graph $HttpB { param($g) ($g.nodes.Count -ge 2) -and ($g.version -ge 2) } 5 'B learns node A from A broadcast'
  Check ($graphB.hubId -eq $selfA) 'B converges to hub=A (graph state is shared)'

  Write-Host '==> A switches hub to B via HTTP'
  $r = Post-Json $HttpA '/api/graph/switch' ('{"nodeId":"' + $selfB + '"}')
  Check ($r.ok -eq $true) '/api/graph/switch returned ok'
  Check ($r.graph.hubId -eq $selfB) 'switch response graph.hubId == B'
  Check ($r.graph.version -ge 3) 'switch bumps version'
  Check ($r.graph.isHub -eq $false) 'switch response graph.isHub == false for A'

  Write-Host '==> B receives A broadcast: same graph, hub = B'
  $graphB = Wait-Graph $HttpB { param($g) ($g.hubId -eq $script:selfB) -and ($g.version -ge 3) } 5 'B adopts hub=B'
  Check ($graphB.nodes.Count -ge 2) 'B graph still has both nodes'
  Check (($graphB.nodes | Where-Object { $_.id -eq $selfB }).isHub -eq $true) 'B node marked isHub'

  Write-Host '==> idle: no merge ping-pong'
  $v1 = (Get-Graph $HttpA).version
  Start-Sleep -Milliseconds 800
  $v2 = (Get-Graph $HttpA).version
  Check ($v1 -eq $v2) 'A version stable while idle (no merge ping-pong)'

  Write-Host '==> B switches hub back to A'
  $bVersion = (Get-Graph $HttpB).version
  $expected = $bVersion + 1
  $r2 = Post-Json $HttpB '/api/graph/switch' ('{"nodeId":"' + $selfA + '"}')
  Check ($r2.ok -eq $true) 'B switch ok'
  $graphA = Wait-Graph $HttpA { param($g) ($g.hubId -eq $script:selfA) -and ($g.version -ge $script:expected) } 5 'A converges back to hub=A with B version'
  Check ($graphA.isHub -eq $true) 'A is hub again'

  Write-Host '==> GRAPH-REQ / explicit sync'
  $sync = Post-Json $HttpA '/api/graph/sync' '{}'
  Check ($sync.ok -eq $true) '/api/graph/sync returned ok'
  Check ($sync.sent -ge 1) 'sync reports at least one recipient'

  Write-Host '==> split request body (headers and body in separate TCP segments)'
  $splitText = Get-ResponseBody (Send-SplitBodyPost $HttpA '/api/graph/switch' ('{"nodeId":"' + $selfA + '"}'))
  $splitObj = $null
  try { $splitObj = $splitText | ConvertFrom-Json } catch { }
  Check ($null -ne $splitObj -and $splitObj.ok -eq $true) 'switch works when POST body arrives after headers'
  if ($null -ne $splitObj -and $splitObj.graph) {
    Check ($splitObj.graph.hubId -eq $selfA) 'split-body switch applied nodeId (hub == A)'
  }

  Write-Host '==> unknown node switch must be rejected'
  $bad = Post-Json $HttpA '/api/graph/switch' '{"nodeId":"10.255.255.1:47000"}'
  Check ($bad.ok -eq $false) 'unknown nodeId rejected'

  Write-Host ''
  if ($failures.Count -eq 0) {
    Write-Host '==> GRAPH SMOKE: ALL CHECKS PASSED'
  } else {
    Write-Host ("==> GRAPH SMOKE: " + $failures.Count + ' FAILURE(S)')
    $failures | ForEach-Object { Write-Host ('   - ' + $_) }
    Write-Host '--- hub A log ---'; Get-Content $logA -ErrorAction SilentlyContinue | Select-Object -Last 30
    Write-Host '--- hub B log ---'; Get-Content $logB -ErrorAction SilentlyContinue | Select-Object -Last 30
    exit 1
  }
} catch {
  Write-Host ('SMOKE ERROR: ' + $_.Exception.Message)
  Write-Host ('A log: ' + $logA); Get-Content $logA -ErrorAction SilentlyContinue | Select-Object -Last 40
  Write-Host ('B log: ' + $logB); Get-Content $logB -ErrorAction SilentlyContinue | Select-Object -Last 40
  exit 1
} finally {
  if ($procA -and -not $procA.HasExited) { Stop-Process -Id $procA.Id -Force -ErrorAction SilentlyContinue }
  if ($procB -and -not $procB.HasExited) { Stop-Process -Id $procB.Id -Force -ErrorAction SilentlyContinue }
  Remove-Item Env:\AUDIOHUB_LOCAL_IP -ErrorAction SilentlyContinue
}