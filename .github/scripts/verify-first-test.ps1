$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$repo = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$testtr = Join-Path $repo 'projects/testtr'
$demo = Join-Path $repo ('target/first-test-' + [guid]::NewGuid().ToString('N'))
$guide = Get-Content (Join-Path $testtr 'ilk-test.md') -Raw -Encoding utf8

# Use the guide's examples in a separate Maven consumer, outside the reactor.
$files = @{
    xml = 'pom.xml'
    html = 'src/test/resources/index.html'
    json = 'src/test/registry/ana-sayfa.json'
    text = 'src/test/testtr/ilk-test.testtr'
}
foreach ($language in $files.Keys) {
    $blocks = [regex]::Matches($guide, '(?ms)^```' + $language + '\r?\n(.*?)^```')
    if ($blocks.Count -ne 1) { throw "Expected one $language example in ilk-test.md" }
    $path = Join-Path $demo $files[$language]
    New-Item -ItemType Directory -Force -Path (Split-Path $path -Parent) | Out-Null
    [IO.File]::WriteAllText($path, $blocks[0].Groups[1].Value)
}

function Invoke-Wrapper([string[]] $MavenArgs, [string] $LogName) {
    $log = Join-Path $demo $LogName
    if ($IsWindows) {
        & (Join-Path $testtr 'mvnw.cmd') @MavenArgs *> $log
    } else {
        & sh (Join-Path $testtr 'mvnw') @MavenArgs *> $log
    }
    $code = $LASTEXITCODE
    Get-Content $log -Tail 16 | Write-Host
    return $code
}

$install = Invoke-Wrapper -MavenArgs @('--batch-mode', '-f', (Join-Path $testtr 'pom.xml'), '-pl', 'maven-plugin,runner-support', '-am', 'install') -LogName 'install.log'
if ($install -ne 0) { throw "Component install failed; see $demo/install.log" }

Push-Location $demo
try {
    $resources = (Resolve-Path './src/test/resources').Path
    $baseUrl = ([uri]($resources + [IO.Path]::DirectorySeparatorChar)).AbsoluteUri
    $arguments = @('--batch-mode', "-Dtesttr.baseUrl=$baseUrl", 'clean', 'test')
    $success = Invoke-Wrapper -MavenArgs $arguments -LogName 'passing.log'
    if ($success -ne 0) { throw "First test failed; see $demo/passing.log" }
    $reports = @(Get-ChildItem 'target/surefire-reports/TEST-*.xml')
    if ($reports.Count -ne 1) { throw 'Expected exactly one JUnit report' }
    $report = [xml](Get-Content $reports[0].FullName -Raw -Encoding utf8)
    if ($report.testsuite.tests -ne '1' -or $report.testsuite.failures -ne '0' -or $report.testsuite.errors -ne '0' -or $report.testsuite.skipped -ne '0') {
        throw 'Expected one passing test without skips'
    }
    $scenario = Join-Path $demo 'src/test/testtr/ilk-test.testtr'
    $original = [IO.File]::ReadAllText($scenario)
    try {
        [IO.File]::WriteAllText($scenario, $original.Replace('Merhaba TestTR!', 'Farklı mesaj'))
        $failure = Invoke-Wrapper -MavenArgs $arguments -LogName 'expected-failure.log'
        if ($failure -eq 0) { throw 'Incorrect expected text unexpectedly passed' }
        $failedReports = @(Get-ChildItem 'target/surefire-reports/TEST-*.xml')
        if ($failedReports.Count -ne 1) { throw 'Expected one failing JUnit report' }
        $failedXml = Get-Content $failedReports[0].FullName -Raw -Encoding utf8
        if ($failedXml -notmatch 'ilk-test\.testtr:3') { throw 'Missing Turkish source location in failure report' }
        Copy-Item $failedReports[0].FullName (Join-Path $demo 'expected-failure.xml')
    } finally {
        [IO.File]::WriteAllText($scenario, $original)
    }
    $restored = Invoke-Wrapper -MavenArgs $arguments -LogName 'restored.log'
    if ($restored -ne 0) { throw "Restored first test failed; see $demo/restored.log" }
    Write-Host "First-test guide verified: passing test, source-mapped failure, restored passing test. Logs: $demo"
} finally {
    Pop-Location
}
