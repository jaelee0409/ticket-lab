# 실험 러너.
#
# 파라미터 하나를 목록대로 바꿔가며 부하 테스트를 반복 실행하고, 결과를 CSV에
# 한 줄씩 쌓는다. 손으로 하면 한 실험에 수십 분이 걸리고 매번 조건이 조금씩
# 달라져서 비교가 불가능해진다.
#
# 실행마다 하는 일:
#   1. DB를 같은 상태로 초기화
#   2. 그 조건에 맞는 환경변수로 앱을 새로 기동
#   3. 워밍업 (JIT 컴파일이 끝나기 전 측정치는 버린다)
#   4. 같은 조건으로 N회 측정 후 중간값 선택
#   5. 매 회차와 중간값을 CSV에 기록
#
# 예:
#   .\loadtest\run-experiment.ps1 -Sweep SEAT_COUNT -Values 1,5,10,20,50 -Label "경합강도"
#   .\loadtest\run-experiment.ps1 -Sweep DB_POOL_SIZE -Values 5,10,20,40,80 -Label "풀크기"
#   .\loadtest\run-experiment.ps1 -Sweep VUS -Values 10,50,100,200 -Label "부하량"

param(
    # 바꿔가며 측정할 파라미터. SEAT_COUNT | VUS | DB_POOL_SIZE
    [ValidateSet("SEAT_COUNT", "VUS", "DB_POOL_SIZE")]
    [string] $Sweep = "SEAT_COUNT",

    # 그 파라미터에 넣어볼 값들
    [int[]] $Values = @(1, 5, 10, 20, 50),

    # 스윕 대상이 아닌 파라미터의 고정값
    [int]    $Vus = 50,
    [string] $Duration = "20s",
    [int]    $SeatCount = 20,
    [int]    $PoolSize = 10,

    # 같은 조건 반복 횟수. 홀수여야 중간값이 실측값이 된다.
    [int]    $Repeats = 3,
    [string] $WarmupDuration = "10s",

    # 각 조건마다 이 전략들을 모두 측정한다.
    [string[]] $Strategies = @("none"),

    [string] $Label = "",
    [string] $Out = "loadtest/results/results.csv",
    [int]    $Port = 8080
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$jar = "build/libs/ticket-lab-0.0.1-SNAPSHOT.jar"
$baseUrl = "http://localhost:$Port"
$tmpDir = Join-Path $env:TEMP "ticket-lab-runner"
New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Out) | Out-Null

# ─────────────────────────────────────────────────────────── helpers

function Write-Step($text) {
    Write-Host ""
    Write-Host "── $text" -ForegroundColor Cyan
}

function Invoke-Psql($sql) {
    docker compose exec -T postgres psql -U ticketlab -d ticketlab -q -t -A -c $sql
}

# 모든 실행이 완전히 같은 상태에서 출발하도록 되돌린다. 이게 없으면 두 번째
# 실행은 첫 번째가 팔아치운 좌석 위에서 시작해 조건이 달라진다.
function Reset-Database {
    Invoke-Psql @"
DELETE FROM payment;
DELETE FROM reservation;
DELETE FROM seat;
DELETE FROM event;
INSERT INTO event (title, venue, starts_at)
VALUES ('loadtest event', 'measurement hall', now() + interval '30 days');
INSERT INTO seat (event_id, seat_no, grade, price, status)
SELECT (SELECT id FROM event LIMIT 1), 'A-' || lpad(n::text, 4, '0'), 'R', 120000, 'AVAILABLE'
FROM generate_series(1, 500) n;
"@ | Out-Null
}

# 예약 기록만 지우고 좌석은 되돌린다. 회차 사이에는 이것만 하면 된다.
function Reset-Reservations {
    Invoke-Psql "DELETE FROM payment; DELETE FROM reservation; UPDATE seat SET status='AVAILABLE';" | Out-Null
}

function Get-ExcessReservations {
    $sql = @"
SELECT COALESCE(sum(c - 1), 0) FROM (
  SELECT count(*) AS c FROM reservation
  WHERE status IN ('PENDING','CONFIRMED')
  GROUP BY seat_id HAVING count(*) > 1) x;
"@
    return [int](Invoke-Psql $sql).Trim()
}

function Get-DuplicatedSeats {
    $sql = @"
SELECT count(*) FROM (
  SELECT seat_id FROM reservation
  WHERE status IN ('PENDING','CONFIRMED')
  GROUP BY seat_id HAVING count(*) > 1) x;
"@
    return [int](Invoke-Psql $sql).Trim()
}

# 좌석이 다 팔리기까지 걸린 시간. 첫 예약과 마지막 예약의 간격으로 잰다.
# 두 값 모두 애플리케이션 시계에서 나오므로 컨테이너와의 시각 차이가 끼지 않는다.
# 좌석이 하나뿐이면 예약도 하나뿐이라 0 이 나온다. 그 조건에서는 의미가 없는 값이다.
function Get-SelloutSeconds {
    $sql = "SELECT COALESCE(round(EXTRACT(EPOCH FROM (max(created_at) - min(created_at)))::numeric, 3), 0) FROM reservation;"
    return [double](Invoke-Psql $sql).Trim()
}

function Get-SoldSeats {
    return [int](Invoke-Psql "SELECT count(*) FROM reservation;").Trim()
}

function Start-App($poolSize, $strategy) {
    $env:DB_POOL_SIZE = "$poolSize"
    $env:SERVER_PORT = "$Port"
    $env:LOCK_STRATEGY = "$strategy"
    $proc = Start-Process -FilePath "java" `
        -ArgumentList @("-jar", $jar) `
        -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $tmpDir "app.out") `
        -RedirectStandardError  (Join-Path $tmpDir "app.err")

    for ($i = 0; $i -lt 90; $i++) {
        Start-Sleep -Milliseconds 500
        try {
            $r = Invoke-WebRequest -Uri "$baseUrl/actuator/health" -UseBasicParsing -TimeoutSec 2
            if ($r.StatusCode -eq 200) { return $proc }
        } catch { }
    }
    throw "앱이 기동되지 않았습니다. 로그: $tmpDir\app.err"
}

function Stop-App($proc) {
    if ($null -ne $proc -and -not $proc.HasExited) {
        Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
        $proc.WaitForExit(10000) | Out-Null
    }
}

function Invoke-K6($vus, $duration, $seatCount, $summaryPath) {
    $args = @(
        "run", "--quiet", "--log-output=none",
        "--summary-trend-stats", "avg,min,med,p(95),p(99),max",
        "--summary-export", $summaryPath,
        "--env", "BASE_URL=$baseUrl",
        "--env", "VUS=$vus",
        "--env", "DURATION=$duration",
        "--env", "SEAT_COUNT=$seatCount",
        "loadtest/reserve.js"
    )
    & k6 @args | Out-Null
}

function Read-K6Summary($path) {
    $s = Get-Content $path -Raw -Encoding UTF8 | ConvertFrom-Json
    $d = $s.metrics.http_req_duration
    $checks = $s.metrics.checks

    # checks.value 는 모든 체크의 평균 통과율이다. 요청 하나가 201이면
    # "201" 체크는 통과하고 "409" 체크는 실패하므로 3개 중 2개, 즉 항상
    # 0.667 근처가 나온다. 오류율로 읽으면 안 된다.
    # reserve.js 가 setResponseCallback 으로 201/409를 정상으로 선언하면
    # http_req_failed 가 진짜 오류율이 된다.
    $errorRate = 0.0
    if ($null -ne $s.metrics.http_req_failed) { $errorRate = [double]$s.metrics.http_req_failed.value }
    $checkRate = 0.0
    if ($null -ne $checks) { $checkRate = [double]$checks.value }

    return [pscustomobject]@{
        tps       = [math]::Round([double]$s.metrics.http_reqs.rate, 1)
        reqs      = [int]$s.metrics.http_reqs.count
        p50_ms    = [math]::Round([double]$d.med, 2)
        p95_ms    = [math]::Round([double]$d.'p(95)', 2)
        p99_ms    = [math]::Round([double]$d.'p(99)', 2)
        max_ms    = [math]::Round([double]$d.max, 2)
        check_ok    = [math]::Round($checkRate, 4)
        error_rate  = [math]::Round($errorRate, 4)
    }
}

# 중간값. 세 번 중 가운데 값은 실제로 측정된 값이지, 평균처럼 만들어진
# 값이 아니다. 한 번 튄 실행이 결과를 끌고 가지 못한다.
function Get-Median([double[]] $numbers) {
    $sorted = $numbers | Sort-Object
    $n = $sorted.Count
    if ($n -eq 0) { return 0 }
    if ($n % 2 -eq 1) { return $sorted[[math]::Floor($n / 2)] }
    return ($sorted[$n / 2 - 1] + $sorted[$n / 2]) / 2
}

function Add-CsvRow($row) {
    $exists = Test-Path $Out
    $line = [pscustomobject]$row
    if ($exists) {
        $line | Export-Csv -Path $Out -NoTypeInformation -Append -Encoding UTF8
    } else {
        $line | Export-Csv -Path $Out -NoTypeInformation -Encoding UTF8
    }
}

# ─────────────────────────────────────────────────────────── main

if (-not (Test-Path $jar)) { throw "$jar 가 없습니다. .\gradlew.bat bootJar 를 먼저 실행하세요." }

$runId = Get-Date -Format "yyyyMMdd-HHmmss"
if ($Label -eq "") { $Label = $Sweep }

Write-Host ""
Write-Host "실험: $Label" -ForegroundColor Green
Write-Host "  스윕     $Sweep = $($Values -join ', ')"
Write-Host "  고정     VUS=$Vus DURATION=$Duration SEAT_COUNT=$SeatCount POOL=$PoolSize"
Write-Host "  전략     $($Strategies -join ', ')"
Write-Host "  반복     $Repeats 회 (+ 워밍업 $WarmupDuration)"
Write-Host "  출력     $Out"

foreach ($strategy in $Strategies) {
foreach ($value in $Values) {
    # 이번 조건 확정
    $vus = $Vus; $seats = $SeatCount; $pool = $PoolSize
    switch ($Sweep) {
        "VUS"          { $vus   = $value }
        "SEAT_COUNT"   { $seats = $value }
        "DB_POOL_SIZE" { $pool  = $value }
    }

    Write-Step "[$strategy]  $Sweep = $value  (VUS=$vus SEAT_COUNT=$seats POOL=$pool)"

    Reset-Database
    $proc = Start-App $pool $strategy

    try {
        # 워밍업. JVM은 처음 수십 초를 인터프리터로 돌다가 자주 실행되는
        # 코드를 기계어로 컴파일한다. 그 전 측정치는 같은 코드의 다른
        # 성능이라 버린다. 결과는 기록하지 않는다.
        Write-Host "   워밍업..." -NoNewline
        Reset-Reservations
        Invoke-K6 $vus $WarmupDuration $seats (Join-Path $tmpDir "warmup.json")
        Write-Host " 완료"

        $tpsList = @(); $p50List = @(); $p95List = @(); $p99List = @(); $excessList = @(); $selloutList = @()

        for ($r = 1; $r -le $Repeats; $r++) {
            Reset-Reservations
            $summaryPath = Join-Path $tmpDir "run-$r.json"
            Invoke-K6 $vus $Duration $seats $summaryPath

            $m = Read-K6Summary $summaryPath
            $excess = Get-ExcessReservations
            $dupSeats = Get-DuplicatedSeats
            $sellout = Get-SelloutSeconds
            $sold = Get-SoldSeats

            $tpsList += $m.tps; $p50List += $m.p50_ms; $p95List += $m.p95_ms
            $p99List += $m.p99_ms; $excessList += $excess; $selloutList += $sellout

            Write-Host ("   {0}회차  p50 {1,7}ms  p95 {2,7}ms  매진 {3,7}s  판매 {4,5}  초과예약 {5,4}  오류율 {6}" -f `
                $r, $m.p50_ms, $m.p95_ms, $sellout, $sold, $excess, $m.error_rate)

            Add-CsvRow @{
                run_id = $runId; label = $Label; sweep = $Sweep; value = $value
                strategy = $strategy; sellout_sec = $sellout; sold_seats = $sold
                kind = "run"; repeat = $r
                vus = $vus; duration = $Duration; seat_count = $seats; pool_size = $pool
                tps = $m.tps; p50_ms = $m.p50_ms; p95_ms = $m.p95_ms; p99_ms = $m.p99_ms
                max_ms = $m.max_ms; http_reqs = $m.reqs; check_ok_rate = $m.check_ok; error_rate = $m.error_rate
                excess_reservations = $excess; duplicated_seats = $dupSeats
                tps_spread_pct = ""
            }
        }

        $medTps = Get-Median $tpsList
        $medExcess = Get-Median ([double[]]$excessList)
        $spread = 0.0
        if ($medTps -gt 0) {
            $spread = [math]::Round(((($tpsList | Measure-Object -Maximum).Maximum - ($tpsList | Measure-Object -Minimum).Minimum) / $medTps) * 100, 1)
        }

        Write-Host ("   중간값   TPS {0,8}  p95 {1,7}ms  초과예약 {2,4}   (TPS 편차 {3}%)" -f `
            $medTps, (Get-Median $p95List), $medExcess, $spread) -ForegroundColor Yellow

        Add-CsvRow @{
            run_id = $runId; label = $Label; sweep = $Sweep; value = $value
            strategy = $strategy; sellout_sec = (Get-Median ([double[]]$selloutList)); sold_seats = ""
            kind = "median"; repeat = 0
            vus = $vus; duration = $Duration; seat_count = $seats; pool_size = $pool
            tps = $medTps; p50_ms = (Get-Median $p50List); p95_ms = (Get-Median $p95List)
            p99_ms = (Get-Median $p99List); max_ms = ""; http_reqs = ""
            check_ok_rate = ""; error_rate = ""; excess_reservations = $medExcess; duplicated_seats = ""
            tps_spread_pct = $spread
        }
    }
    finally {
        Stop-App $proc
    }
}
}

Write-Host ""
Write-Host "완료. 결과: $Out" -ForegroundColor Green
Write-Host "그래프: python loadtest/plot.py --label `"$Label`"" -ForegroundColor Green
