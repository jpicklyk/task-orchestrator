# gradle-locked.ps1 - the ONLY gradle entry point for dispatched seats.
#
# Copied verbatim into the session scratchpad at /implement Step 2 (never hand-written per wave).
# Serializes gradle across seats with a directory lock, prints EXIT=<n> as its final line, and
# enforces the dispatch contract's "Who runs gradle" table:
#
#   - Seats (implementer, test author, any hand-dispatched fix or second-round seat) may run the
#     pinned compile tasks, and a SCOPED test run of their own test files via -TestFiles.
#   - :current:test, :current:ktlintCheck, bare test/check/build, and --rerun-tasks are
#     orchestrator-only. They are refused (EXIT=98) unless TO_GRADLE_OWNER=orchestrator is set in
#     the calling process. The orchestrator sets it inline on its own call and nowhere else:
#         $env:TO_GRADLE_OWNER='orchestrator'; & "<scratchpad>\gradle-locked.ps1" -Worktree ... -Tasks ":current:test",":current:ktlintCheck"
#   - --rerun-tasks is accepted from the orchestrator with a warning: it is for the run after a
#     dependency or Gradle upgrade, never a routine verification.
#
# Usage (seat, compile self-check):
#   & "<scratchpad>\gradle-locked.ps1" -Worktree "<wt>" -Tasks ":current:ktlintFormat",":current:compileKotlin",":current:compileTestKotlin"
# Usage (seat, scoped run of its own test classes; paths under src/test/kotlin map to --tests FQCNs):
#   & "<scratchpad>\gradle-locked.ps1" -Worktree "<wt>" -TestFiles "current/src/test/kotlin/.../FooTest.kt current/src/test/kotlin/.../BarTest.kt"
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

$lock = Join-Path $env:TEMP "bugwave-gradle.lock"
$acquired = $false
for ($i = 0; $i -lt 600; $i++) {
  try { New-Item -ItemType Directory -Path $lock -ErrorAction Stop | Out-Null; $acquired = $true; break }
  catch { Start-Sleep -Seconds 5 }
}
if (-not $acquired) { Write-Output "LOCK TIMEOUT after 50 min"; Write-Output "EXIT=99"; exit 99 }
$gradlew = Join-Path $Worktree "gradlew.bat"
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
  Remove-Item $lock -Force -Recurse -ErrorAction SilentlyContinue
}
if (Test-Path $Log) { Get-Content $Log -Tail 40 }
Write-Output "EXIT=$code (log: $Log)"
exit $code
