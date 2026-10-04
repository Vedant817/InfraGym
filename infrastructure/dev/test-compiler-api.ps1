<#
.SYNOPSIS
    Exercises the graph compiler's HTTP contract the way a user would.

.DESCRIPTION
    Written because the earlier suite asserted `assertTrue(true)` and nothing else, and
    the security and error-mapping defects it would have caught shipped. Every check
    below corresponds to a behaviour that was wrong before:

      * health/liveness reachable without credentials  (probe paths required ROLE_ADMIN,
                                                         so a container killed itself)
      * compile requires authentication                 (was unauthenticated)
      * malformed JSON is 400                           (was 500)
      * a cycle is 400 and names the path               (was 500, no path)
      * an unknown type suggests the intended one       (substrings matched key names,
                                                         so overrides were inert)
      * user config reaches the compiled latency and capacity maps
      * actuator/prometheus is served                    (exposed but 404)

.EXAMPLE
    pwsh -File infrastructure/dev/test-compiler-api.ps1 -BaseUrl http://localhost:8081
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:8081',
    [string]$Username = $env:COMPILE_USER,
    [string]$Password = $env:COMPILE_PASSWORD,
    # Actuator and /api/v1/admin/** require ROLE_ADMIN. Checking them as a USER
    # principal would assert 403 where 200 is correct, so admin credentials are
    # separate rather than reusing the compile user.
    [string]$AdminUsername = $env:ADMIN_USER,
    [string]$AdminPassword = $env:ADMIN_PASSWORD
)

$ErrorActionPreference = 'Stop'
$script:passed = 0
$script:failed = 0
$script:failures = @()

function Invoke-Json {
    param(
        [string]$Path,
        [string]$Method = 'GET',
        [string]$Body,
        [switch]$NoAuth,
        [switch]$AsAdmin
    )

    $arguments = @{
        Uri                = "$BaseUrl$Path"
        Method             = $Method
        UseBasicParsing    = $true
        SkipHttpErrorCheck = $true
    }
    if (-not $NoAuth) {
        $user = if ($AsAdmin) { $AdminUsername } else { $Username }
        $pass = if ($AsAdmin) { $AdminPassword } else { $Password }
        $pair = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("$user`:$pass"))
        $arguments['Headers'] = @{ Authorization = "Basic $pair" }
    }
    if ($Body) {
        $arguments['Body'] = $Body
        $arguments['ContentType'] = 'application/json'
    }

    $response = Invoke-WebRequest @arguments

    # Invoke-WebRequest returns Content as byte[] on some hosts, which makes
    # ConvertFrom-Json fail silently and every assertion read $null. Normalise to a
    # string first so a decoding quirk cannot masquerade as an API defect.
    $raw = $response.Content
    if ($raw -is [byte[]]) {
        $raw = [Text.Encoding]::UTF8.GetString($raw)
    }

    $json = $null
    if ($raw) {
        try { $json = $raw | ConvertFrom-Json } catch { $json = $null }
    }

    [pscustomobject]@{
        Status = $response.StatusCode
        Json   = $json
        Raw    = $raw
    }
}

function Assert-Check {
    param(
        [string]$Name,
        # Coerced explicitly: a Where-Object or comparison expression returns
        # Object[] or $null rather than a bool, and a [bool] parameter rejects
        # those outright with a confusing transformation error instead of just
        # failing the assertion.
        $Condition,
        [string]$Detail = ''
    )
    if ([bool]$Condition) {
        $script:passed++
        Write-Host "  PASS  $Name" -ForegroundColor Green
    } else {
        $script:failed++
        $script:failures += "$Name$(if ($Detail) { " -- $Detail" })"
        Write-Host "  FAIL  $Name $Detail" -ForegroundColor Red
    }
}

Write-Host ""
Write-Host "Graph compiler API contract checks against $BaseUrl" -ForegroundColor Cyan
Write-Host "=" * 78

# --- health -------------------------------------------------------------------
Write-Host "`nHealth and probes (must not require credentials)" -ForegroundColor Yellow
$r = Invoke-Json '/actuator/health' -NoAuth
Assert-Check 'health is reachable without credentials' ($r.Status -eq 200) "got $($r.Status)"

$r = Invoke-Json '/actuator/health/readiness' -NoAuth
Assert-Check 'readiness is reachable without credentials' ($r.Status -eq 200) "got $($r.Status)"
# A readiness group reports UP, DOWN or OUT_OF_SERVICE. Anything else means the
# group is not resolving at all, which is the failure mode we are checking for.
Assert-Check 'readiness reports a known status' `
    (@('UP', 'DOWN', 'OUT_OF_SERVICE') -contains [string]$r.Json.status) "got '$($r.Json.status)'"

$r = Invoke-Json '/actuator/health/liveness' -NoAuth
Assert-Check 'liveness is reachable without credentials' ($r.Status -eq 200) "got $($r.Status)"

# --- authentication -----------------------------------------------------------
Write-Host "`nAuthentication" -ForegroundColor Yellow
$r = Invoke-Json '/api/v1/compile' -Method POST -Body '{}' -NoAuth
Assert-Check 'compile requires authentication' ($r.Status -eq 401) "got $($r.Status)"

$r = Invoke-Json '/actuator/metrics' -NoAuth
Assert-Check 'actuator metrics requires authentication' ($r.Status -eq 401) "got $($r.Status)"

# --- contract discovery -------------------------------------------------------
Write-Host "`nContract discovery" -ForegroundColor Yellow
$r = Invoke-Json '/api/v1/compile'
Assert-Check 'compile endpoint is described' ($r.Status -eq 200 -and $r.Json.synchronous -eq $true)
Assert-Check 'description publishes nothing to Kafka' ($r.Json.publishesToKafka -eq $false)
Assert-Check 'node types are advertised' ($r.Json.nodeTypes.Count -ge 13) "got $($r.Json.nodeTypes.Count)"

# --- happy path ---------------------------------------------------------------
Write-Host "`nValid topology compiles" -ForegroundColor Yellow
$topology = @'
{"nodes":[
 {"id":"lb","type":"load_balancer","position":{"x":0,"y":0},"data":{"label":"Load Balancer"}},
 {"id":"api","type":"service","position":{"x":0,"y":0},
  "data":{"label":"API","config":{"requestsPerSecond":2000,"p99Ms":150}}},
 {"id":"cache","type":"cache","position":{"x":0,"y":0},
  "data":{"label":"Redis","config":{"hitRate":0.9}}},
 {"id":"db","type":"database","position":{"x":0,"y":0},
  "data":{"label":"PostgreSQL","config":{"iops":5000}}}
],"edges":[
 {"id":"e1","source":"lb","target":"api"},
 {"id":"e2","source":"api","target":"cache"},
 {"id":"e3","source":"api","target":"db"}
]}
'@

$r = Invoke-Json '/api/v1/compile' -Method POST -Body $topology
Assert-Check 'valid topology returns 200' ($r.Status -eq 200) "got $($r.Status)"
$graph = $r.Json.compiledGraph
Assert-Check 'all four nodes compiled' ($graph.nodes.Count -eq 4) "got $($graph.nodes.Count)"
Assert-Check 'all three edges compiled' ($graph.edges.Count -eq 3) "got $($graph.edges.Count)"
Assert-Check 'no simulation id is invented for a dry run' ($null -eq $r.Json.simulationId)

$api = $graph.nodes | Where-Object id -eq 'api'
Assert-Check 'user rps override reached capacity' ($api.capacity.requestsPerSecond -eq 2000) `
    "got $($api.capacity.requestsPerSecond)"
Assert-Check 'user p99 override reached the latency profile' ($api.latencyProfile.p99Ms -eq 150) `
    "got $($api.latencyProfile.p99Ms)"
Assert-Check 'registry defaults survive alongside the override' ($api.latencyProfile.p50Ms -gt 0) `
    "p50Ms=$($api.latencyProfile.p50Ms)"

$cache = $graph.nodes | Where-Object id -eq 'cache'
Assert-Check 'cache hit rate override reached properties' ($cache.simulationMetadata.properties.hitRate -eq 0.9) `
    "got $($cache.simulationMetadata.properties.hitRate)"

$db = $graph.nodes | Where-Object id -eq 'db'
Assert-Check 'database iops override reached capacity' ($db.capacity.iops -eq 5000) `
    "got $($db.capacity.iops)"
Assert-Check 'database latency uses its own read keys, not a service default' `
    ($db.latencyProfile.readP50Ms -gt 0) "readP50Ms=$($db.latencyProfile.readP50Ms)"

Assert-Check 'topological order puts the entry point first' ($graph.nodes[0].id -eq 'lb') `
    "got $($graph.nodes[0].id)"
Assert-Check 'dependency and dependent sets are populated' `
    ($api.dependencies -contains 'lb' -and $api.dependents.Count -eq 2)

$meta = $graph.graphMetadata
Assert-Check 'graph depth is the longest path' ($meta.graphDepth -eq 2) "got $($meta.graphDepth)"
Assert-Check 'entry point is detected' ($meta.hasEntryPoint -eq $true)
Assert-Check 'category census is emitted' (@($meta.categoryCounts.PSObject.Properties).Count -ge 5)

# --- error mapping ------------------------------------------------------------
Write-Host "`nError mapping" -ForegroundColor Yellow

$r = Invoke-Json '/api/v1/compile' -Method POST -Body '{"nodes":['
Assert-Check 'malformed JSON is 400, not 500' ($r.Status -eq 400) "got $($r.Status)"
Assert-Check 'malformed JSON has a stable code' ($r.Json.code -eq 'MALFORMED_REQUEST') `
    "got $($r.Json.code)"
Assert-Check 'malformed JSON carries an actionable hint' (-not [string]::IsNullOrWhiteSpace($r.Json.hint))

$cycle = @'
{"nodes":[
 {"id":"a","type":"service","position":{"x":0,"y":0},"data":{"label":"A"}},
 {"id":"b","type":"service","position":{"x":0,"y":0},"data":{"label":"B"}}
],"edges":[{"id":"e1","source":"a","target":"b"},{"id":"e2","source":"b","target":"a"}]}
'@
$r = Invoke-Json '/api/v1/compile' -Method POST -Body $cycle
Assert-Check 'a cycle is 400, not 500' ($r.Status -eq 400) "got $($r.Status)"
Assert-Check 'the cycle path is named' ($r.Json.errors[0].message -match 'a -> b -> a') `
    "got '$($r.Json.errors[0].message)'"
Assert-Check 'cycle attribution is field-scoped' ($r.Json.errors[0].code -eq 'CYCLE_DETECTED') `
    "got $($r.Json.errors[0].code)"

$r = Invoke-Json '/api/v1/compile' -Method POST `
    -Body '{"nodes":[{"id":"x","type":"databse","position":{"x":0,"y":0},"data":{"label":"X"}}],"edges":[]}'
Assert-Check 'unknown type is 400' ($r.Status -eq 400) "got $($r.Status)"
Assert-Check 'unknown type suggests the intended one' `
    ($r.Json.errors[0].message -match "Did you mean 'database'") "got '$($r.Json.errors[0].message)'"
Assert-Check 'unknown type is attributed to the offending field' `
    ($r.Json.errors[0].field -eq 'nodes[0].type') "got '$($r.Json.errors[0].field)'"

$duplicateIds = @'
{"nodes":[
 {"id":"dup","type":"service","position":{"x":0,"y":0},"data":{"label":"A"}},
 {"id":"dup","type":"service","position":{"x":0,"y":0},"data":{"label":"B"}}
],"edges":[]}
'@
$r = Invoke-Json '/api/v1/compile' -Method POST -Body $duplicateIds
$duplicateCodes = @($r.Json.errors | ForEach-Object { $_.code })
Assert-Check 'duplicate node ids are rejected' `
    ($r.Status -eq 400 -and $duplicateCodes -contains 'DUPLICATE_NODE_ID') `
    "status=$($r.Status) codes=$($duplicateCodes -join ',')"

$r = Invoke-Json '/api/v1/compile' -Method POST -Body '{"nodes":[],"edges":[]}'
Assert-Check 'an empty topology is rejected' ($r.Status -eq 400) "got $($r.Status)"

$dangling = @'
{"nodes":[{"id":"a","type":"service","position":{"x":0,"y":0},"data":{"label":"A"}}],
 "edges":[{"id":"e","source":"a","target":"ghost"}]}
'@
$r = Invoke-Json '/api/v1/compile' -Method POST -Body $dangling
$danglingCodes = @($r.Json.errors | ForEach-Object { $_.code })
Assert-Check 'a dangling edge is attributed to the edge that caused it' `
    ($danglingCodes -contains 'INVALID_EDGE_TARGET') "codes=$($danglingCodes -join ',')"
# -like on a single value returns a bool, but the pipeline wrapping makes the whole
# expression an array, so compare the joined string instead.
$targetFields = @($r.Json.errors |
    Where-Object { $_.code -eq 'INVALID_EDGE_TARGET' } |
    ForEach-Object { [string]$_.field })
Assert-Check 'the dangling-edge finding names the edge index' `
    ($targetFields -contains 'edges[0].target') "fields=$($targetFields -join ',')"

# --- observability ------------------------------------------------------------
Write-Host "`nObservability (ROLE_ADMIN)" -ForegroundColor Yellow
# A 403 here as the compile user is correct, not a failure; these are asserted with an
# admin principal so the check is "is the endpoint served", not "is it public".
$r = Invoke-Json '/actuator/prometheus' -AsAdmin
Assert-Check 'prometheus endpoint is served, not 404' ($r.Status -eq 200) "got $($r.Status)"
Assert-Check 'prometheus exposes JVM metrics' ($r.Raw -match 'jvm_memory_used_bytes') `
    'jvm_memory_used_bytes absent from the scrape'

$r = Invoke-Json '/actuator/metrics' -AsAdmin
Assert-Check 'metrics endpoint is reachable' ($r.Status -eq 200) "got $($r.Status)"

# The lag poll is scheduled, so allow one interval plus slack before asserting.
# Regression: the poll read its partition list from committed offsets, which are empty
# until the group commits its first record, so the gauge never registered at all and a
# freshly started service published no lag metric whatsoever.
$lagDeadline = (Get-Date).AddSeconds(75)
$lagStatus = 0
do {
    Start-Sleep -Seconds 10
    $r = Invoke-Json '/actuator/metrics/graph.kafka.consumer.total.lag' -AsAdmin
    $lagStatus = $r.Status
} while ($lagStatus -ne 200 -and (Get-Date) -lt $lagDeadline)

Assert-Check 'total consumer lag is published before any record is committed' `
    ($lagStatus -eq 200) "status=$lagStatus after waiting for the scheduled poll"

$r = Invoke-Json '/actuator/metrics/graph.kafka.consumer.monitored.partitions' -AsAdmin
$monitored = if (@($r.Json.measurements).Count -gt 0) { [double]$r.Json.measurements[0].value } else { 0 }
Assert-Check 'lag reporting covers the consumed partitions' `
    ($r.Status -eq 200 -and $monitored -gt 0) "status=$($r.Status) partitions=$monitored"

# A USER principal must not reach the admin surface.
$r = Invoke-Json '/api/v1/admin/dlq-info'
Assert-Check 'admin endpoints reject a non-admin principal' `
    ($r.Status -in 401, 403) "got $($r.Status)"

$r = Invoke-Json '/api/v1/admin/dlq-info' -AsAdmin
Assert-Check 'admin endpoints are reachable with ROLE_ADMIN' ($r.Status -eq 200) "got $($r.Status)"
Assert-Check 'dead-letter topic is a declared topic, not graph-compiler.dlt' `
    ($r.Json.dlqTopic -eq 'design.submissions.dlt') "got $($r.Json.dlqTopic)"

# --- summary ------------------------------------------------------------------
Write-Host ""
Write-Host "=" * 78
Write-Host "passed: $script:passed   failed: $script:failed" -ForegroundColor $(if ($script:failed) { 'Red' } else { 'Green' })
if ($script:failed -gt 0) {
    Write-Host "`nFailures:" -ForegroundColor Red
    $script:failures | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
    exit 1
}
Write-Host "all checks passed" -ForegroundColor Green
exit 0
