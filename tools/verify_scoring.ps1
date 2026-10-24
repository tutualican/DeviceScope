# Independent verification of the DeviceScope scoring arithmetic (PowerShell twin
# of tools/verify_scoring.py).
#
#   powershell -ExecutionPolicy Bypass -File tools\verify_scoring.ps1
#   powershell -ExecutionPolicy Bypass -File tools\verify_scoring.ps1 -File validation-...-scoring.csv
#
# Exit code 0 = all checks passed. Kept in the repo because the Python script
# needs an interpreter that may not exist on a build machine; the two files
# implement the same formula from the spec, independently of the Kotlin app.

param(
    [string]$File = ""
)

$ErrorActionPreference = "Stop"
$Tolerance = 1e-6
$Inv = [System.Globalization.CultureInfo]::InvariantCulture
[System.Threading.Thread]::CurrentThread.CurrentCulture = $Inv
$Weights = @{ cpu = 0.40; gpu = 0.30; memory = 0.15; ux = 0.15 }
$script:problems = 0

function GeoMean([double[]]$values) {
    $usable = @($values | Where-Object { $null -ne $_ -and $_ -gt 0 })
    if ($usable.Count -eq 0) { return $null }
    $sum = 0.0
    foreach ($v in $usable) { $sum += [Math]::Log($v) }
    return [Math]::Exp($sum / $usable.Count)
}

function Breakdown([hashtable]$inputs, [hashtable]$weights = $Weights) {
    $cpu = GeoMean @($inputs["single"], $inputs["multi"])
    $gpu = if ($inputs["gpu"] -gt 0) { $inputs["gpu"] } else { $null }
    $memory = GeoMean @($inputs["memory"], $inputs["storage"])
    $ux = if ($inputs["ux"] -gt 0) { $inputs["ux"] } else { $null }

    $parts = @()
    if ($null -ne $cpu) { $parts += @{ w = $weights["cpu"]; v = $cpu } }
    if ($null -ne $gpu) { $parts += @{ w = $weights["gpu"]; v = $gpu } }
    if ($null -ne $memory) { $parts += @{ w = $weights["memory"]; v = $memory } }
    if ($null -ne $ux) { $parts += @{ w = $weights["ux"]; v = $ux } }

    $totalWeight = 0.0
    foreach ($p in $parts) { $totalWeight += $p.w }
    $combined = 0.0
    if ($totalWeight -gt 0) {
        $acc = 0.0
        foreach ($p in $parts) { $acc += $p.w * $p.v }
        $combined = $acc / $totalWeight
    }

    return @{ cpu = $cpu; gpu = $gpu; memory = $memory; ux = $ux; combined = $combined }
}

function Shifted([hashtable]$weights, [string]$group, [double]$delta) {
    $out = @{}
    foreach ($k in $weights.Keys) { $out[$k] = $weights[$k] }
    $out[$group] = [Math]::Max(0.01, $out[$group] + $delta)
    return $out
}

function Check([string]$name, [bool]$condition, [string]$detail) {
    if ($condition) {
        "ok   $name"
    } else {
        $script:problems++
        "FAIL $name : $detail"
    }
}

function ParseInputs([string]$text) {
    $values = @{}
    foreach ($chunk in $text.Split(",")) {
        $eq = $chunk.IndexOf("=")
        if ($eq -lt 0) { continue }
        $key = $chunk.Substring(0, $eq).Trim()
        $raw = $chunk.Substring($eq + 1).Trim()
        if ($raw -eq "" -or $raw -eq "null") { continue }
        $values[$key] = [double]::Parse($raw, [Globalization.CultureInfo]::InvariantCulture)
    }
    return $values
}

function VerifyFile([string]$path) {
    $rows = 0
    $failures = 0
    foreach ($row in (Import-Csv -Path $path)) {
        $rows++
        $label = if ($row.label) { $row.label } else { "row $rows" }
        $inputs = ParseInputs $row.inputs
        $b = Breakdown $inputs

        $checks = @(
            @{ name = "combined"; expected = $b.combined; actual = $row.combined },
            @{ name = "cpu"; expected = $b.cpu; actual = $row.cpu },
            @{ name = "gpu"; expected = $b.gpu; actual = $row.gpu },
            @{ name = "memory"; expected = $b.memory; actual = $row.memory },
            @{ name = "ux"; expected = $b.ux; actual = $row.ux }
        )
        foreach ($c in $checks) {
            $hasActual = $c.actual -ne $null -and $c.actual -ne ""
            if ($null -eq $c.expected -and -not $hasActual) { continue }
            if ($null -eq $c.expected -or -not $hasActual -or
                [Math]::Abs($c.expected - [double]$c.actual) -gt $Tolerance) {
                $failures++
                "FAIL $label / $($c.name): powershell=$($c.expected) app=$($c.actual)"
            }
        }
        "ok   ${label}: combined=$('{0:F6}' -f $b.combined)"
    }
    ""
    "$rows satir dogrulandi, $failures uyusmazlik."
    return $(if ($rows -eq 0 -or $failures -gt 0) { 1 } else { 0 })
}

if ($File -ne "") {
    exit (VerifyFile $File)
}

# ---------------------------------------------------------------- self test
$ref = @{ single = 1000.0; multi = 1000.0; memory = 1000.0; storage = 1000.0; gpu = 1000.0; ux = 1000.0 }
$r = Breakdown $ref
Check "referans cihaz = 1000" ([Math]::Abs($r.combined - 1000.0) -lt $Tolerance) "$($r.combined)"

$cpuOnly = Breakdown @{ single = 400.0; multi = 1600.0 }
Check "geometrik ortalama sqrt(400*1600)=800" ([Math]::Abs($cpuOnly.cpu - 800.0) -lt $Tolerance) "$($cpuOnly.cpu)"

$wsum = 0.0
foreach ($v in $Weights.Values) { $wsum += $v }
Check "agirlik toplami = 1" ([Math]::Abs($wsum - 1.0) -lt $Tolerance) "$wsum"

$slow = @{ single = 800.0; multi = 1000.0; memory = 1000.0; storage = 1000.0; gpu = 1000.0; ux = 1000.0 }
$baseCombined = $r.combined
$slowCombined = (Breakdown $slow).combined
$observed = ($baseCombined - $slowCombined) / $baseCombined * 100.0
$expected = $Weights["cpu"] * (1.0 - [Math]::Sqrt(0.8)) * 100.0
Check "20% tek is yuku yavaslatma" ([Math]::Abs($observed - $expected) -lt 1e-9) ("gozlenen {0:F9}%, beklenen {1:F9}%" -f $observed, $expected)
"     gozlenen dusus: {0:F6}%  (beklenen {1:F6}%)" -f $observed, $expected

$slowGpu = @{ single = 1000.0; multi = 1000.0; memory = 1000.0; storage = 1000.0; gpu = 700.0; ux = 1000.0 }
$gpuCombined = (Breakdown $slowGpu).combined
$observedGpu = ($baseCombined - $gpuCombined) / $baseCombined * 100.0
$expectedGpu = $Weights["gpu"] * 30.0
Check "30% kategori yavaslatma" ([Math]::Abs($observedGpu - $expectedGpu) -lt 1e-9) ("gozlenen {0:F9}%, beklenen {1:F9}%" -f $observedGpu, $expectedGpu)
"     gozlenen dusus: {0:F6}%  (beklenen {1:F6}%)" -f $observedGpu, $expectedGpu

$fastCpu = @{ single = 2000.0; multi = 2000.0; memory = 500.0; storage = 500.0; gpu = 500.0; ux = 500.0 }
$normal = (Breakdown $fastCpu).combined
$boosted = (Breakdown $fastCpu (Shifted $Weights "cpu" 0.05)).combined
Check "agirlik +5 puan CPU'yu yukseltir" ($boosted -gt $normal) ("normal {0:F3}, +5p {1:F3}" -f $normal, $boosted)

$noGpu = @{ single = 1000.0; multi = 1000.0; memory = 1000.0; storage = 1000.0; gpu = 0.0; ux = 1000.0 }
$ng = (Breakdown $noGpu).combined
Check "eksik grup yeniden normalise" ([Math]::Abs($ng - 1000.0) -lt $Tolerance) "$ng"

$slowDevice = @{ single = 500.0; multi = 600.0; memory = 400.0; storage = 400.0; gpu = 500.0; ux = 500.0 }
Check "siralama tutarli" ($baseCombined -gt (Breakdown $slowDevice).combined) "$baseCombined vs $((Breakdown $slowDevice).combined)"

""
if ($script:problems -eq 0) { "TUM TESTLER GECTI" } else { "$($script:problems) TEST BASARISIZ" }
exit $(if ($script:problems -eq 0) { 0 } else { 1 })
