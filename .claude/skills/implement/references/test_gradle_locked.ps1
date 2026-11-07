# Black-box harness for gradle-locked.ps1 (item 9f61a885).
# Run: pwsh -NoProfile -File <this file>   (optional: $env:TG_ONLY = a scenario-name prefix such as S2 runs only those cases)   Exit 0 = every scenario passed, 1 = at least one failed.
# Every case runs the script as a child pwsh with TEMP/TMP pointed at a throwaway directory, so the lock is
# <throwaway>/bugwave-gradle.lock and the real %TEMP% lock is never touched. -Worktree is a stub directory whose
# gradlew.bat exits a chosen code (STUB_EXIT), optionally after a sleep (STUB_SLEEP pings), and records what it saw.
$ErrorActionPreference = 'Stop'
$ScriptUnderTest = Join-Path $PSScriptRoot 'gradle-locked.ps1'
$PwshExe = (Get-Process -Id $PID).Path
$Root = Join-Path ([IO.Path]::GetTempPath()) ("test-gradle-locked-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Path $Root | Out-Null
$script:Failures = New-Object System.Collections.Generic.List[string]
$script:Passed = 0
$script:Children = New-Object System.Collections.Generic.List[object]
$EnvNames = @('TEMP', 'TMP', 'TO_GRADLE_OWNER', 'TO_GRADLE_LOCK_STALE_MINUTES', 'STUB_EXIT', 'STUB_SLEEP', 'STUB_REPLACE_OWNER', 'STUB_OWNER')

function Check([string]$Name, $Condition, [string]$Detail = '') {
    if ($Condition) { $script:Passed++; Write-Host "  ok   $Name" }
    else { $script:Failures.Add("$Name $Detail"); Write-Host "  FAIL $Name $Detail" }
}

$StubBat = (@(
    '@echo off',
    'if defined STUB_OWNER if exist "%STUB_OWNER%" copy /y "%STUB_OWNER%" "%~dp0seen-owner.json" >nul',
    'if exist "%~dp0inside.flag" echo overlap> "%~dp0overlap.txt"',
    'echo x> "%~dp0inside.flag"',
    'echo run>> "%~dp0ran.log"',
    'if defined STUB_REPLACE_OWNER echo {"pid":999999,"processStart":"2000-01-01T00:00:00Z","acquiredAt":"2000-01-01T00:00:00Z"}> "%STUB_OWNER%"',
    'if defined STUB_SLEEP ping -n %STUB_SLEEP% 127.0.0.1 >nul',
    'del "%~dp0inside.flag" >nul 2>&1',
    'exit /b %STUB_EXIT%'
) -join "`r`n") + "`r`n"

function New-Case([string]$Name) {
    $dir = Join-Path $Root $Name
    $tmp = Join-Path $dir 'tmp'
    $wt = Join-Path $dir 'wt'
    New-Item -ItemType Directory -Path $tmp, $wt -Force | Out-Null
    Set-Content -Path (Join-Path $wt 'gradlew.bat') -Value $StubBat -Encoding ascii -NoNewline
    $lock = Join-Path $tmp 'bugwave-gradle.lock'
    [pscustomobject]@{ Name = $Name; Tmp = $tmp; Wt = $wt; Lock = $lock; Owner = (Join-Path $lock 'owner.json'); RanLog = (Join-Path $wt 'ran.log') }
}

function Iso([datetime]$d) { $d.ToUniversalTime().ToString('o') }

function Make-Lock($Case, $OwnerText, [double]$AgeMinutes) {
    New-Item -ItemType Directory -Path $Case.Lock | Out-Null
    if ($null -ne $OwnerText) { [IO.File]::WriteAllText($Case.Owner, $OwnerText) }
    (Get-Item $Case.Lock).CreationTimeUtc = (Get-Date).ToUniversalTime().AddMinutes(-$AgeMinutes)
}

function Owner-Json($ProcId, $ProcessStart, $AcquiredAt) {
    $o = [ordered]@{ pid = $ProcId; processStart = (Iso $ProcessStart); acquiredAt = (Iso $AcquiredAt); worktree = 'x'; tasks = 'x' }
    ($o | ConvertTo-Json -Compress)
}

function Start-Child($Case, [hashtable]$Extra = @{}, [string]$Task = ':current:compileKotlin') {
    $vars = @{ TEMP = $Case.Tmp; TMP = $Case.Tmp; TO_GRADLE_OWNER = $null; TO_GRADLE_LOCK_STALE_MINUTES = $null; STUB_EXIT = '0'; STUB_SLEEP = $null; STUB_REPLACE_OWNER = $null; STUB_OWNER = $Case.Owner }
    foreach ($k in $Extra.Keys) { $vars[$k] = $Extra[$k] }
    $saved = @{}
    foreach ($n in $EnvNames) { $saved[$n] = [Environment]::GetEnvironmentVariable($n) }
    $id = [guid]::NewGuid().ToString('N').Substring(0, 6)
    $out = Join-Path $Case.Tmp "out-$id.txt"
    $err = Join-Path $Case.Tmp "err-$id.txt"
    $log = Join-Path $Case.Tmp "gradle-$id.log"
    try {
        foreach ($n in $vars.Keys) { [Environment]::SetEnvironmentVariable($n, $vars[$n]) }
        $argList = @('-NoProfile', '-File', "`"$ScriptUnderTest`"", '-Worktree', "`"$($Case.Wt)`"", '-Tasks', $Task, '-Log', "`"$log`"")
        $p = Start-Process -FilePath $PwshExe -ArgumentList $argList -PassThru -WindowStyle Hidden -RedirectStandardOutput $out -RedirectStandardError $err
        $null = $p.Handle
    }
    finally {
        foreach ($n in $EnvNames) { [Environment]::SetEnvironmentVariable($n, $saved[$n]) }
    }
    $c = [pscustomobject]@{ Proc = $p; Out = $out; Err = $err }
    $script:Children.Add($c)
    $c
}

function Read-Shared([string]$Path) {
    if ((-not $Path) -or (-not (Test-Path $Path))) { return '' }
    $fs = [IO.File]::Open($Path, 'Open', 'Read', 'ReadWrite')
    try { $sr = New-Object IO.StreamReader($fs); $sr.ReadToEnd() } finally { $fs.Dispose() }
}
function Get-Text($C) { (Read-Shared $C.Out) + "`n" + (Read-Shared $C.Err) }
function Get-ExitLine($C) {
    $lines = @((Read-Shared $C.Out) -split "`r?`n" | Where-Object { $_.Trim() -ne '' })
    if ($lines.Count -eq 0) { return $null }
    if ($lines[-1].Trim() -match '^EXIT=(-?\d+)(\s.*)?$') { return [int]$Matches[1] }
    return $null
}
function Stop-Tree($C) { try { if (-not $C.Proc.HasExited) { $C.Proc.Kill($true) } } catch { } }
function Wait-Child($C, [int]$Seconds) {
    if ($C.Proc.WaitForExit($Seconds * 1000)) { return $true }
    Stop-Tree $C
    return $false
}
function Wait-Text($C, [string]$Pattern, [int]$Seconds) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        if ((Get-Text $C) -match $Pattern) { return $true }
        if ($C.Proc.HasExited) { Start-Sleep -Milliseconds 300; return ((Get-Text $C) -match $Pattern) }
        Start-Sleep -Milliseconds 250
    }
    return ((Get-Text $C) -match $Pattern)
}
function Dead-Pid-Owner {
    $p = Start-Process -FilePath $PwshExe -ArgumentList @('-NoProfile', '-Command', 'exit 0') -PassThru -WindowStyle Hidden
    $null = $p.Handle
    $p.WaitForExit()
    $now = Get-Date
    Owner-Json $p.Id $now.AddMinutes(-3) $now.AddMinutes(-2)
}

function Run-Case([string]$Name, [scriptblock]$Body) {
    if ($env:TG_ONLY -and -not $Name.StartsWith($env:TG_ONLY)) { return }
    Write-Host "== $Name"
    try { & $Body } catch { $script:Failures.Add("$Name threw: $($_.Exception.Message)"); Write-Host "  FAIL $Name threw: $($_.Exception.Message)" }
}

try {
    Run-Case 'S1 no lock: acquire, owner.json names the child pid, release' {
        $c = New-Case 'S1'
        $h = Start-Child $c
        Check 'S1 finished' (Wait-Child $h 60)
        Check 'S1 EXIT=0 last line' ((Get-ExitLine $h) -eq 0) (Get-Text $h)
        $seen = Join-Path $c.Wt 'seen-owner.json'
        Check 'S1 stub saw owner.json while holding the lock' (Test-Path $seen)
        if (Test-Path $seen) {
            $o = Get-Content $seen -Raw | ConvertFrom-Json
            Check 'S1 owner pid is the child pid' ($o.pid -eq $h.Proc.Id) "pid=$($o.pid) child=$($h.Proc.Id)"
            $t = [datetimeoffset]::MinValue
            Check 'S1 acquiredAt parses' ([datetimeoffset]::TryParse("$($o.acquiredAt)", [ref]$t))
        }
        Check 'S1 lock dir gone afterwards' (-not (Test-Path $c.Lock))
    }

    Run-Case 'S2 dead holder pid is reclaimed' {
        $c = New-Case 'S2'
        Make-Lock $c (Dead-Pid-Owner) 0
        $h = Start-Child $c
        $done = Wait-Child $h 30
        Check 'S2 finished within 30 s (pre-fix waits 50 min)' $done
        Check 'S2 LOCK RECLAIMED printed' ((Get-Text $h) -match 'LOCK RECLAIMED') (Get-Text $h)
        Check 'S2 EXIT=0' ((Get-ExitLine $h) -eq 0)
        Check 'S2 stub ran' (Test-Path $c.RanLog)
    }

    Run-Case 'S3 live holder blocks, is named, then dies and is reclaimed' {
        $c = New-Case 'S3'
        $holder = Start-Process -FilePath $PwshExe -ArgumentList @('-NoProfile', '-Command', 'Start-Sleep -Seconds 600') -PassThru -WindowStyle Hidden
        $null = $holder.Handle
        $script:Children.Add([pscustomobject]@{ Proc = $holder; Out = ''; Err = '' })
        Make-Lock $c (Owner-Json $holder.Id $holder.StartTime (Get-Date)) 0
        $h = Start-Child $c
        Check 'S3 prints LOCK HELD by pid <holder> within 15 s' (Wait-Text $h "LOCK HELD by pid $($holder.Id)" 15) (Get-Text $h)
        $held = [regex]::Match((Get-Text $h), "LOCK HELD by pid $($holder.Id) since (\S+)")
        Check 'S3 LOCK HELD carries a since <iso> part' $held.Success (Get-Text $h)
        $sinceOk = $false
        if ($held.Success) {
            $sinceT = [datetimeoffset]::MinValue
            $sinceOk = ($held.Groups[1].Value -match '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}') -and [datetimeoffset]::TryParse($held.Groups[1].Value, [ref]$sinceT)
        }
        Check 'S3 since part is an ISO-8601 timestamp' $sinceOk "since=$($held.Groups[1].Value)"
        Check 'S3 stub has not run while holder is alive' (-not (Test-Path $c.RanLog))
        $holder.Kill($true)
        $holder.WaitForExit()
        Check 'S3 waiter finishes within 30 s of holder death' (Wait-Child $h 30)
        Check 'S3 EXIT=0' ((Get-ExitLine $h) -eq 0) (Get-Text $h)
        Check 'S3 stub ran after reclaim' (Test-Path $c.RanLog)
    }

    Run-Case 'S4 gradle failure code propagates and lock is released' {
        $c = New-Case 'S4'
        $h = Start-Child $c @{ STUB_EXIT = '3' }
        Check 'S4 finished' (Wait-Child $h 60)
        Check 'S4 EXIT=3 last line' ((Get-ExitLine $h) -eq 3) (Get-Text $h)
        Check 'S4 lock dir removed' (-not (Test-Path $c.Lock))
    }

    Run-Case 'S5 live pid reused after acquiredAt is reclaimed' {
        $c = New-Case 'S5'
        $self = [Diagnostics.Process]::GetCurrentProcess()
        Make-Lock $c (Owner-Json $PID $self.StartTime (Get-Date).AddDays(-1)) 0
        $h = Start-Child $c
        Check 'S5 finished within 30 s' (Wait-Child $h 30)
        Check 'S5 EXIT=0' ((Get-ExitLine $h) -eq 0) (Get-Text $h)
        Check 'S5 LOCK RECLAIMED printed' ((Get-Text $h) -match 'LOCK RECLAIMED')
    }

    Run-Case 'S6a ownerless fresh lock below the bound keeps waiting' {
        $c = New-Case 'S6a'
        Make-Lock $c $null 0
        $h = Start-Child $c @{ TO_GRADLE_LOCK_STALE_MINUTES = '1' }
        Check 'S6a prints LOCK HELD within 10 s' (Wait-Text $h 'LOCK HELD' 10) (Get-Text $h)
        Start-Sleep -Seconds 10
        Check 'S6a stub not run across the full 10 s window' (-not (Test-Path $c.RanLog))
        Check 'S6a no reclaim across the window' (-not ((Get-Text $h) -match 'LOCK RECLAIMED'))
        Stop-Tree $h
    }
    Run-Case 'S6b ownerless lock older than the bound is reclaimed' {
        $c = New-Case 'S6b'
        Make-Lock $c $null 5
        $h = Start-Child $c @{ TO_GRADLE_LOCK_STALE_MINUTES = '1' }
        Check 'S6b finished within 30 s' (Wait-Child $h 30)
        Check 'S6b EXIT=0' ((Get-ExitLine $h) -eq 0) (Get-Text $h)
        Check 'S6b LOCK RECLAIMED printed' ((Get-Text $h) -match 'LOCK RECLAIMED')
    }

    foreach ($bad in @('0', '-5', 'abc')) {
        Run-Case "S7 invalid bound '$bad' warns and falls back to 30 minutes" {
            $c = New-Case ("S7_" + ($bad -replace '[^a-z0-9]', 'x'))
            Make-Lock $c $null 5
            $h = Start-Child $c @{ TO_GRADLE_LOCK_STALE_MINUTES = $bad }
            Check "S7[$bad] WARN line printed" (Wait-Text $h 'WARN' 15) (Get-Text $h)
            Start-Sleep -Seconds 7
            Check "S7[$bad] 5-minute-old ownerless lock still waits (default 30 applies)" (-not (Test-Path $c.RanLog))
            Stop-Tree $h
        }
    }

    Run-Case 'S8 unparseable owner.json older than the bound is reclaimed' {
        $c = New-Case 'S8'
        Make-Lock $c 'this is not json {{{' 5
        $h = Start-Child $c @{ TO_GRADLE_LOCK_STALE_MINUTES = '1' }
        Check 'S8 finished within 30 s' (Wait-Child $h 30)
        Check 'S8 EXIT=0' ((Get-ExitLine $h) -eq 0) (Get-Text $h)
        Check 'S8 LOCK RECLAIMED printed' ((Get-Text $h) -match 'LOCK RECLAIMED')
    }

    Run-Case 'S9 release guard: lock now owned by another pid is left in place' {
        $c = New-Case 'S9'
        $h = Start-Child $c @{ STUB_REPLACE_OWNER = '1' }
        Check 'S9 finished' (Wait-Child $h 60)
        Check 'S9 EXIT=0 last line' ((Get-ExitLine $h) -eq 0) (Get-Text $h)
        Check 'S9 lock dir left in place' (Test-Path $c.Lock)
        Check 'S9 owner.json still present' (Test-Path $c.Owner)
        $foreign = $null
        if (Test-Path $c.Owner) { $foreign = (Get-Content $c.Owner -Raw | ConvertFrom-Json).pid }
        Check 'S9 foreign owner.json untouched (pid 999999)' ($foreign -eq 999999) "pid=$foreign"
    }

    Run-Case 'S11 unreadable owner.json (held FileShare.None) counts as a live holder' {
        $c = New-Case 'S11'
        Make-Lock $c $null 0
        $fs = [IO.File]::Open($c.Owner, 'CreateNew', 'Write', 'None')
        try {
            $bytes = [Text.Encoding]::ASCII.GetBytes((Owner-Json $PID (Get-Date).AddMinutes(-6) (Get-Date).AddMinutes(-5)))
            $fs.Write($bytes, 0, $bytes.Length)
            $fs.Flush()
            (Get-Item $c.Lock).CreationTimeUtc = (Get-Date).ToUniversalTime().AddMinutes(-5)
            $h = Start-Child $c @{ TO_GRADLE_LOCK_STALE_MINUTES = '1' }
            Check 'S11 prints LOCK HELD by pid unknown within 10 s' (Wait-Text $h 'LOCK HELD by pid unknown' 10) (Get-Text $h)
            Start-Sleep -Seconds 5
            Check 'S11 no LOCK RECLAIMED while owner.json is unreadable' (-not ((Get-Text $h) -match 'LOCK RECLAIMED')) (Get-Text $h)
            Check 'S11 stub not run' (-not (Test-Path $c.RanLog))
            Check 'S11 waiter still waiting' (-not $h.Proc.HasExited)
            Stop-Tree $h
        }
        finally { $fs.Dispose() }
        Check 'S11 lock dir never taken from the holder' (Test-Path $c.Lock)
    }

    Run-Case 'S10 gated task without TO_GRADLE_OWNER is refused, no lock created' {
        $c = New-Case 'S10'
        $h = Start-Child $c @{} ':current:test'
        Check 'S10 finished' (Wait-Child $h 60)
        Check 'S10 EXIT=98' ((Get-ExitLine $h) -eq 98) (Get-Text $h)
        Check 'S10 no lock dir created' (-not (Test-Path $c.Lock))
        Check 'S10 stub not run' (-not (Test-Path $c.RanLog))
    }

    Run-Case 'P1 owner.json without a pid field (aged) is reclaimed' {
        $c = New-Case 'P1'
        Make-Lock $c ('{"acquiredAt":"' + (Iso (Get-Date).AddMinutes(-5)) + '"}') 5
        $h = Start-Child $c @{ TO_GRADLE_LOCK_STALE_MINUTES = '1' }
        Check 'P1 finished within 30 s' (Wait-Child $h 30)
        Check 'P1 EXIT=0' ((Get-ExitLine $h) -eq 0) (Get-Text $h)
    }
    Run-Case 'P2 owner.json with pid 0 (aged) is reclaimed' {
        $c = New-Case 'P2'
        $now = Get-Date
        Make-Lock $c (Owner-Json 0 $now.AddMinutes(-5) $now.AddMinutes(-5)) 5
        $h = Start-Child $c @{ TO_GRADLE_LOCK_STALE_MINUTES = '1' }
        Check 'P2 finished within 30 s' (Wait-Child $h 30)
        Check 'P2 EXIT=0' ((Get-ExitLine $h) -eq 0) (Get-Text $h)
    }
    Run-Case 'P3 dead pid with extra fields and CRLF is reclaimed' {
        $c = New-Case 'P3'
        $json = (Dead-Pid-Owner)
        $json = $json.TrimEnd('}') + ',"extra":{"a":1},"note":"x"}' + "`r`n"
        Make-Lock $c $json 0
        $h = Start-Child $c
        Check 'P3 finished within 30 s' (Wait-Child $h 30)
        Check 'P3 EXIT=0' ((Get-ExitLine $h) -eq 0) (Get-Text $h)
        Check 'P3 LOCK RECLAIMED printed' ((Get-Text $h) -match 'LOCK RECLAIMED')
    }
    Run-Case 'P4 two waiters on one stale lock never overlap inside the stub' {
        $c = New-Case 'P4'
        Make-Lock $c (Dead-Pid-Owner) 0
        $a = Start-Child $c @{ STUB_SLEEP = '5' }
        $b = Start-Child $c @{ STUB_SLEEP = '5' }
        Check 'P4 waiter A finished' (Wait-Child $a 90)
        Check 'P4 waiter B finished' (Wait-Child $b 90)
        Check 'P4 A EXIT=0' ((Get-ExitLine $a) -eq 0) (Get-Text $a)
        Check 'P4 B EXIT=0' ((Get-ExitLine $b) -eq 0) (Get-Text $b)
        Check 'P4 no overlap recorded' (-not (Test-Path (Join-Path $c.Wt 'overlap.txt')))
        $runs = if (Test-Path $c.RanLog) { @(Get-Content $c.RanLog).Count } else { 0 }
        Check 'P4 stub ran exactly twice' ($runs -eq 2) "runs=$runs"
    }
    Run-Case 'P5 replay: two serial runs both succeed and release' {
        $c = New-Case 'P5'
        $h1 = Start-Child $c
        Check 'P5 first finished' (Wait-Child $h1 60)
        $h2 = Start-Child $c
        Check 'P5 second finished' (Wait-Child $h2 60)
        Check 'P5 both EXIT=0' (((Get-ExitLine $h1) -eq 0) -and ((Get-ExitLine $h2) -eq 0))
        Check 'P5 lock dir gone' (-not (Test-Path $c.Lock))
    }
}
finally {
    foreach ($c in $script:Children) { Stop-Tree $c }
    Remove-Item -Recurse -Force -Path $Root -ErrorAction SilentlyContinue
}

Write-Host ""
Write-Host "passed checks: $script:Passed, failed checks: $($script:Failures.Count)"
foreach ($f in $script:Failures) { Write-Host "FAILED: $f" }
if ($script:Failures.Count -gt 0) { exit 1 }
exit 0