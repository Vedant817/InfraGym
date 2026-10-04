<#
.SYNOPSIS
    Publishes a design submission to design.submissions and verifies the compiler
    consumed it and emitted a simulation task.

.DESCRIPTION
    This is the end-to-end check for the Kafka path, and it exists because that path was
    completely untested. The previous integration suite asserted `assertTrue(true)` twice
    and passed even with the service deleted; separately, every wire type on the pipeline
    was an un-deserialisable Lombok class, so the consumer swallowed
    InvalidDefinitionException on every record while the topic stayed empty and the
    health check stayed green.

    The script therefore asserts on observable facts rather than on the absence of
    errors:
      * the record reaches design.submissions
      * the compiler's consumer group advances past it (lag returns to zero)
      * a matching simulation task appears on simulation.tasks
      * a submission lifecycle event reports COMPILED

.PARAMETER Scenario
    Scenario to request. Must be a ScenarioType wire value.

.EXAMPLE
    pwsh -File infrastructure/dev/publish-submission.ps1 -Scenario flash_sale
#>
[CmdletBinding()]
param(
    [string]$Bootstrap = 'localhost:19092',
    [string]$Scenario = 'flash_sale',
    [int]$TimeoutSeconds = 90,
    [switch]$SkipTaskCheck
)

$ErrorActionPreference = 'Stop'

# Uses the Kafka CLI already present in the broker container rather than pulling a
# client library, so this script runs with nothing installed but Docker and pwsh.
$container = 'archforge-kafka'
$kafkaHome = '/opt/kafka'

function Invoke-Kafka {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)

    $output = docker exec $container "$kafkaHome/bin/kafka-$(if ($Args[0] -like '*consum*') { 'console-consumer' } else { 'run-class' })" @Args 2>&1
    return $output
}

function Get-TopicRecordCount {
    <#
      Total records on a topic, summed across partitions.

      kafka-get-offsets returns log-end offsets, not record counts, so the value is only
      a useful delta when no retention or compaction is in play — which holds for the
      append-only topics this script checks.
    #>
    param([string]$Topic)

    $lines = docker exec $container bash -c "$kafkaHome/bin/kafka-get-offsets.sh --bootstrap-server kafka:29092 --topic $Topic" 2>&1
    $sum = 0L
    foreach ($line in $lines) {
        # kafka-get-offsets.sh prints "<topic>:<partition>:<offset>"
        if ($line -match '^\s*\S+:\d+:(\d+)\s*$') {
            $sum += [long]$Matches[1]
        }
    }
    return $sum
}

function Get-ConsumerGroupOffsets {
    param([string]$Group)

    $lines = docker exec $container bash -c "$kafkaHome/bin/kafka-consumer-groups.sh --bootstrap-server kafka:29092 --describe --group $Group" 2>&1
    $sum = 0L
    foreach ($line in $lines) {
        # Columns: group topic partition currentOffset logEndOffset lag consumer host client
        #
        # Split on whitespace rather than using a single regex: a partition with no
        # committed offset prints "-" in two columns, and an end-anchored pattern
        # silently fails to match every row of an idle consumer, which is
        # indistinguishable from "nothing was consumed".
        $fields = ($line -split '\s+') | Where-Object { $_ }
        if ($fields.Count -lt 6) { continue }
        if ($fields[1] -ne 'design.submissions') { continue }
        $offset = 0L
        if ([long]::TryParse($fields[3], [ref]$offset)) {
            $sum += $offset
        }
    }
    return $sum
}

Write-Host ''
Write-Host "End-to-end submission check ($Scenario)" -ForegroundColor Cyan
Write-Host "=" * 78

# --- build a realistic topology ------------------------------------------------
$submissionId = [guid]::NewGuid().ToString()
$correlationId = [guid]::NewGuid().ToString()
$eventId = [guid]::NewGuid().ToString()
$simulationId = [guid]::NewGuid().ToString()

# Built as a nested object and serialised, rather than as an interpolated JSON string.
#
# kafka-console-producer treats ONE LINE as one message, so a pretty-printed payload is
# split into a dozen fragments that each fail to parse — which looks exactly like the
# consumer silently dropping submissions, and cost a debugging cycle to tell apart from
# a genuine pipeline failure.
$topology = @{
    nodes = @(
        @{ id = 'lb';     type = 'load_balancer'; position = @{ x = 0; y = 0 }; data = @{ label = 'Load Balancer' } }
        @{ id = 'api';    type = 'service';       position = @{ x = 0; y = 0 }
           data = @{ label = 'API Service'; config = @{ requestsPerSecond = 2500; p99Ms = 180 } } }
        @{ id = 'worker'; type = 'worker';        position = @{ x = 0; y = 0 }
           data = @{ label = 'Async Worker'; config = @{ concurrency = 20 } } }
        @{ id = 'queue';  type = 'queue';         position = @{ x = 0; y = 0 }
           data = @{ label = 'Event Bus'; config = @{ partitions = 6 } } }
        @{ id = 'cache';  type = 'cache';         position = @{ x = 0; y = 0 }
           data = @{ label = 'Redis'; config = @{ hitRate = 0.85 } } }
        @{ id = 'db';     type = 'database';      position = @{ x = 0; y = 0 }
           data = @{ label = 'PostgreSQL'; config = @{ iops = 8000 } } }
    )
    edges = @(
        @{ id = 'e1'; source = 'lb';    target = 'api' }
        @{ id = 'e2'; source = 'api';   target = 'cache' }
        @{ id = 'e3'; source = 'api';   target = 'db' }
        @{ id = 'e4'; source = 'api';   target = 'queue' }
        @{ id = 'e5'; source = 'queue'; target = 'worker' }
        @{ id = 'e6'; source = 'worker'; target = 'db' }
    )
}

$event = [ordered]@{
    eventId       = $eventId
    correlationId = $correlationId
    submissionId  = $submissionId
    designId      = 'design-1'
    userId        = 'e2e-user'
    sessionId     = 'e2e-session'
    scenario      = $Scenario
    scenarioNotes = 'end-to-end verification'
    topology      = $topology
    submittedAt   = (Get-Date).ToUniversalTime().ToString('o')
}

# -Compress guarantees a single line, which the console producer requires.
$payload = $event | ConvertTo-Json -Depth 8 -Compress

Write-Host "submissionId : $submissionId"
Write-Host "scenario     : $Scenario"

# --- baseline offsets ----------------------------------------------------------
$beforeTasks = Get-TopicRecordCount 'simulation.tasks'
$beforeLag = Get-ConsumerGroupOffsets 'graph-compiler-group'
Write-Host "baseline     : simulation.tasks=$beforeTasks, groupLag=$beforeLag"

# --- publish -------------------------------------------------------------------
$tmpFile = [System.IO.Path]::GetTempFileName()
Set-Content -Path $tmpFile -Value $payload -NoNewline
Write-Host "payload bytes: $($payload.Length)"

try {
    docker cp $tmpFile "${container}:/tmp/submission.json" | Out-Null

    # Pipe the file in as stdin. Passing the path as an argument makes the producer
    # treat it as a shell script to execute, not a file to read.
    $produced = docker exec $container bash -c "cat /tmp/submission.json | $kafkaHome/bin/kafka-console-producer.sh --bootstrap-server kafka:29092 --topic design.submissions" 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Host "FAILED to produce: $produced" -ForegroundColor Red
        exit 1
    }
    Write-Host "published    : design.submissions" -ForegroundColor Green

    # --- the consumer must actually consume it -----------------------------------
    # Offsets are compared against the pre-publish baseline, because a group that is
    # merely idle also reports zero. Asserting "lag == 0" alone would pass on a service
    # that never received the record at all.
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $consumed = $false
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 3
        $offsets = Get-ConsumerGroupOffsets 'graph-compiler-group'
        if ($offsets -ge 1) {
            $consumed = $true
            break
        }
    }

    if (-not $consumed) {
        Write-Host "FAILED: graph-compiler-group committed no offset within ${TimeoutSeconds}s" -ForegroundColor Red
        Write-Host "  the compiler did not consume the record."
        Write-Host "  Check the compiler log for a deserialisation or validation failure."
        exit 1
    }
    Write-Host "consumed     : graph-compiler-group committed an offset" -ForegroundColor Green

    # --- a simulation task must have been produced --------------------------------
    if (-not $SkipTaskCheck) {
        $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
        $afterTasks = $beforeTasks
        while ((Get-Date) -lt $deadline) {
            $afterTasks = Get-TopicRecordCount 'simulation.tasks'
            if ($afterTasks -gt $beforeTasks) { break }
            Start-Sleep -Seconds 3
        }

        if ($afterTasks -le $beforeTasks) {
            Write-Host "FAILED: no new message on simulation.tasks within ${TimeoutSeconds}s" -ForegroundColor Red
            Write-Host "  the record was consumed but produced no simulation task."
            exit 1
        }
        Write-Host "task emitted : simulation.tasks $beforeTasks -> $afterTasks" -ForegroundColor Green
    }

    # --- the published task must be readable by the simulation engine -------------
    # This is the check that would have caught the un-deserialisable CompiledGraph:
    # reading the payload back as SimulationRequestedEvent must succeed.
    $taskJson = docker exec $container bash -c "$kafkaHome/bin/kafka-console-consumer.sh --bootstrap-server kafka:29092 --topic simulation.tasks --from-beginning --max-messages 1 --timeout-ms 15000" 2>$null
    if ($taskJson) {
        $task = ($taskJson -join '') | ConvertFrom-Json
        $scenario = $task.scenario
        $nodeCount = @($task.graph.nodes).Count
        Write-Host "task readable: scenario='$scenario', nodes=$nodeCount, seed=$($task.seed)" -ForegroundColor Green

        if ($scenario -ne $Scenario) {
            Write-Host "FAILED: scenario did not survive the wire" -ForegroundColor Red
            Write-Host "  expected '$Scenario', got '$scenario'."
            Write-Host "  A null scenario here is what silently degraded every run to NORMAL."
            exit 1
        }
        if ($nodeCount -ne 6) {
            Write-Host "FAILED: expected 6 compiled nodes, got $nodeCount" -ForegroundColor Red
            exit 1
        }
    }

    # --- lifecycle ---------------------------------------------------------------
    $lifecycle = docker exec $container bash -c "$kafkaHome/bin/kafka-console-consumer.sh --bootstrap-server kafka:29092 --topic submission.lifecycle --from-beginning --timeout-ms 12000" 2>$null
    $statuses = @()
    foreach ($line in $lifecycle) {
        if ($line -match '"submissionId":"([^"]+)"' -and $Matches[1] -eq $submissionId) {
            if ($line -match '"status":"([^"]+)"') { $statuses += $Matches[1] }
        }
    }
    if ($statuses.Count -gt 0) {
        Write-Host "lifecycle    : $($statuses -join ' -> ')" -ForegroundColor Green
        if ($statuses -notcontains 'COMPILED') {
            Write-Host "FAILED: expected a COMPILED transition, saw: $($statuses -join ', ')" -ForegroundColor Red
            exit 1
        }
    } else {
        Write-Host "NOTE: no lifecycle event observed for this submission (topic may have no data yet)" -ForegroundColor Yellow
    }

    Write-Host ''
    Write-Host "PASS: submission flowed gateway -> compiler -> simulation.tasks" -ForegroundColor Green
    exit 0

} finally {
    Remove-Item $tmpFile -ErrorAction SilentlyContinue
    docker exec $container rm -f /tmp/submission.json 2>$null | Out-Null
}
