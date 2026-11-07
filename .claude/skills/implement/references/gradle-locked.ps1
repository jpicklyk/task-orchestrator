# gradle-locked.ps1 - the ONLY gradle entry point for dispatched seats.
#
# Tracked in the repo; run it from the worktree it builds (run-profile.json and the dispatch contract reference
# <worktree>\.claude\skills\implement\references\gradle-locked.ps1). Never copy or hand-write it per wave.
# Serializes gradle across seats with a directory lock, prints EXIT=<n> as its final line, and
# enforces the dispatch contract's "Who runs gradle" table:
#
#   - Seats (implementer, test author, any hand-dispatched fix or second-round seat) may run the
#     pinned compile tasks, and a SCOPED test run of their own test files via -TestFiles.
#   - :current:test, :current:ktlintCheck, bare test/check/build, and --rerun-tasks are
#     orchestrator-only. They are refused (EXIT=98) unless TO_GRADLE_OWNER=orchestrator is set in
#     the calling process. The orchestrator sets it inline on its own call and nowhere else:
#         $env:TO_GRADLE_OWNER='orchestrator'; & "<wt>\.claude\skills\implement\references\gradle-locked.ps1" -Worktree ... -Tasks ":current:test",":current:ktlintCheck"
#   - --rerun-tasks is accepted from the orchestrator with a warning: it is for the run after a
#     dependency or Gradle upgrade, never a routine verification.
#
# Usage (seat, compile self-check):
#   & "<wt>\.claude\skills\implement\references\gradle-locked.ps1" -Worktree "<wt>" -Tasks ":current:ktlintFormat",":current:compileKotlin",":current:compileTestKotlin"
# Usage (seat, scoped run of its own test classes; paths under src/test/kotlin map to --tests FQCNs):
#   & "<wt>\.claude\skills\implement\references\gradle-locked.ps1" -Worktree "<wt>" -TestFiles "current/src/test/kotlin/.../FooTest.kt current/src/test/kotlin/.../BarTest.kt"
param(
  [Parameter(Mandatory=$true)][string]$Worktree,
  [string[]]$Tasks = @(),
  [string[]]$TestFiles = @(),
  [string]$Log = (Join-Path $env:TEMP ("gradle-locked-" + [guid]::NewGuid().ToString("N").Substring(0,8) + ".log"))
)

$gated = @(':current:test', ':current:ktlintCheck', 'test', 'check', 'build', '--rerun-tasks')
$owner = $env:TO_GRADLE_OWNER
$isOrchestrator = ($owner -eq 'orchestrator')

$gradleArgs = @()
foreach ($t in $Tasks) { if (-not [string]::IsNullOrWhiteSpace($t)) { $gradleArgs += $t } }

if (-not $isOrchestrator) {
  foreach ($t in $gradleArgs) {
    if ($gated -contains $t) {
      Write-Output "REFUSED: '$t' is orchestrator-only (dispatch contract, 'Who runs gradle'). Seats run the pinned compile tasks, or -TestFiles <own test files> for a scoped run."
      Write-Output "EXIT=98"
      exit 98
    }
  }
} elseif ($gradleArgs -contains '--rerun-tasks') {
  Write-Output "WARNING: --rerun-tasks forces a full recompile and rerun; use it only after a dependency or Gradle upgrade."
}

# -TestFiles: a scoped run, open to every seat. Each element may hold several whitespace-separated
# paths (the run plan's <ownedTests> placeholder renders them that way). Only files under
# src/test/kotlin map to a class; anything else is listed and skipped. No resolvable class means
# no run at all - never fall through to the whole suite.
$classes = @()
foreach ($raw in $TestFiles) {
  foreach ($f in ($raw -split '\s+')) {
    if ([string]::IsNullOrWhiteSpace($f)) { continue }
    $n = $f -replace '\\', '/'
    $marker = 'src/test/kotlin/'
    $i = $n.IndexOf($marker)
    if ($i -lt 0) { Write-Output "SKIP $f : not under src/test/kotlin"; continue }
    $cls = $n.Substring($i + $marker.Length) -replace '\.kt$', '' -replace '/', '.'
    $classes += $cls
  }
}
if ($TestFiles.Count -gt 0) {
  if ($classes.Count -eq 0) {
    Write-Output "SKIP scoped run: no test class resolved from -TestFiles"
    Write-Output "EXIT=0"
    exit 0
  }
  $gradleArgs += ':current:test'
  foreach ($c in $classes) { $gradleArgs += '--tests'; $gradleArgs += $c }
}

if ($gradleArgs.Count -eq 0) {
  Write-Output "REFUSED: nothing to run (pass -Tasks or -TestFiles)"
  Write-Output "EXIT=97"
  exit 97
}

# The lock is a directory holding owner.json {pid, processStart, acquiredAt, worktree, tasks}, so a waiter can tell a
# live holder from one that died without reaching `finally` (killed client, closed terminal, host crash).
# STALE = the holder pid is not running, or that pid's StartTime is later than acquiredAt (pid reuse). A live pid is
# never reclaimed by age. A lock with no readable owner.json is stale only once its directory is older than
# TO_GRADLE_LOCK_STALE_MINUTES (default 30; any other value warns and uses 30).
$lock = Join-Path $env:TEMP "bugwave-gradle.lock"
$ownerName = "owner.json"

$staleMinutes = 30
$rawBound = $env:TO_GRADLE_LOCK_STALE_MINUTES
if (-not [string]::IsNullOrWhiteSpace($rawBound)) {
  $parsedBound = 0
  if ([int]::TryParse($rawBound.Trim(), [ref]$parsedBound) -and $parsedBound -gt 0) { $staleMinutes = $parsedBound }
  else { Write-Output "WARN: TO_GRADLE_LOCK_STALE_MINUTES='$rawBound' is not a positive integer; using 30." }
}

# Returns @{ pid; acquiredAt (UTC DateTime) } for a readable owner.json, else $null (absent, garbage, pid < 1, no time).
function Read-LockOwner([string]$dir) {
  try {
    $text = Get-Content -LiteralPath (Join-Path $dir $ownerName) -Raw -ErrorAction Stop
    $o = $text | ConvertFrom-Json -ErrorAction Stop
    $p = 0
    if (-not [int]::TryParse([string]$o.pid, [ref]$p) -or $p -lt 1) { return $null }
    $v = $o.acquiredAt
    if ($v -is [datetime]) { $at = $v.ToUniversalTime() }
    else {
      $style = [System.Globalization.DateTimeStyles]::AdjustToUniversal -bor [System.Globalization.DateTimeStyles]::AssumeUniversal
      $at = [datetime]::Parse([string]$v, [System.Globalization.CultureInfo]::InvariantCulture, $style)
    }
    return @{ pid = $p; acquiredAt = $at }
  } catch { return $null }
}

# Classifies a lock directory: @{ state = gone|stale|live; reason; pid; since }.
function Get-LockVerdict([string]$dir) {
  if (-not (Test-Path -LiteralPath $dir)) { return @{ state = 'gone' } }
  $o = Read-LockOwner $dir
  if ($null -ne $o) {
    $since = $o.acquiredAt.ToString('o')
    $proc = Get-Process -Id $o.pid -ErrorAction SilentlyContinue
    if ($null -eq $proc) { return @{ state = 'stale'; reason = "holder pid $($o.pid) is not running"; pid = $o.pid; since = $since } }
    $started = $null
    try { $started = $proc.StartTime.ToUniversalTime() } catch { $started = $null }
    if ($null -ne $started -and $started -gt $o.acquiredAt) {
      return @{ state = 'stale'; reason = "pid $($o.pid) was reused (started $($started.ToString('o')), after the lock was taken $since)"; pid = $o.pid; since = $since }
    }
    return @{ state = 'live'; pid = $o.pid; since = $since }
  }
  $created = (Get-Item -LiteralPath $dir -ErrorAction SilentlyContinue).CreationTimeUtc
  if ($null -eq $created) { return @{ state = 'gone' } }
  $since = $created.ToString('o')
  if (([datetime]::UtcNow - $created).TotalMinutes -gt $staleMinutes) {
    return @{ state = 'stale'; reason = "no readable owner.json and the lock is older than $staleMinutes min"; pid = 'unknown'; since = $since }
  }
  return @{ state = 'live'; pid = 'unknown'; since = $since }
}

$acquired = $false
$clock = [System.Diagnostics.Stopwatch]::StartNew()
$lastNotice = $null
for ($i = 0; $i -lt 600; $i++) {
  try { New-Item -ItemType Directory -Path $lock -ErrorAction Stop | Out-Null; $acquired = $true; break }
  catch {
    $v = Get-LockVerdict $lock
    if ($v.state -eq 'gone') { continue }
    if ($v.state -eq 'stale') {
      # Move aside (atomic; a failed move means another waiter got there first), re-judge the moved copy, then delete or restore.
      $aside = "$lock.stale-" + [guid]::NewGuid().ToString('N')
      try { Move-Item -LiteralPath $lock -Destination $aside -ErrorAction Stop } catch { continue }
      $again = Get-LockVerdict $aside
      if ($again.state -eq 'stale' -and "$($again.pid)" -eq "$($v.pid)" -and $again.since -eq $v.since) {
        Remove-Item -LiteralPath $aside -Force -Recurse -ErrorAction SilentlyContinue
        Write-Output "LOCK RECLAIMED: $($v.reason)"
      } else {
        try { Move-Item -LiteralPath $aside -Destination $lock -ErrorAction Stop } catch { }
      }
      continue
    }
    if ($null -eq $lastNotice -or $clock.Elapsed.TotalSeconds - $lastNotice -ge 60) {
      Write-Output "LOCK HELD by pid $($v.pid) since $($v.since)"
      $lastNotice = $clock.Elapsed.TotalSeconds
    }
    Start-Sleep -Seconds 5
  }
}
if (-not $acquired) { Write-Output "LOCK TIMEOUT after 50 min"; Write-Output "EXIT=99"; exit 99 }
$gradlew = Join-Path $Worktree "gradlew.bat"
try {
  $me = Get-Process -Id $PID
  $ownerDoc = [ordered]@{
    pid = $PID
    processStart = $me.StartTime.ToUniversalTime().ToString('o')
    acquiredAt = [datetime]::UtcNow.ToString('o')
    worktree = $Worktree
    tasks = @($gradleArgs)
  }
  $ownerDoc | ConvertTo-Json -Compress | Set-Content -LiteralPath (Join-Path $lock $ownerName) -Encoding UTF8 -ErrorAction Stop
} catch {
  # A lock we cannot label would block every later caller until the age bound; give it back and fail loudly.
  Remove-Item -LiteralPath $lock -Force -Recurse -ErrorAction SilentlyContinue
  Write-Output "REFUSED: could not write owner.json into the lock: $($_.Exception.Message)"
  Write-Output "EXIT=96"
  exit 96
}
try {
  if (-not (Test-Path $gradlew)) {
    Write-Output "REFUSED: $gradlew not found (wrong -Worktree?)"
    $code = 96
  } else {
    & $gradlew -p $Worktree @gradleArgs *> $Log
    $code = $LASTEXITCODE
    if ($null -eq $code) { $code = 1 }   # a launch failure is never a green run
  }
} finally {
  # Release only a lock that still names this process; one reclaimed from us belongs to its new holder.
  $mine = Read-LockOwner $lock
  if ($null -ne $mine -and $mine.pid -eq $PID) {
    Remove-Item -LiteralPath $lock -Force -Recurse -ErrorAction SilentlyContinue
  }
}
if (Test-Path $Log) { Get-Content $Log -Tail 40 }
Write-Output "EXIT=$code (log: $Log)"
exit $code
