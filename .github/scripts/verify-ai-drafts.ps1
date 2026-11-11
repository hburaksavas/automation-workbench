$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$repo = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$testtr = Join-Path $repo 'projects/testtr'
$demo = Join-Path $repo ('target/ai-consumer-' + [guid]::NewGuid().ToString('N'))
$guide = Get-Content (Join-Path $testtr 'ilk-test.md') -Raw -Encoding utf8
foreach ($entry in @(
    @{ Language = 'xml'; Path = 'pom.xml' },
    @{ Language = 'html'; Path = 'src/test/resources/index.html' },
    @{ Language = 'json'; Path = 'src/test/registry/ana-sayfa.json' }
)) {
    $blocks = [regex]::Matches($guide, '(?ms)^```' + $entry.Language + '\r?\n(.*?)^```')
    if ($blocks.Count -ne 1) { throw 'Expected one guide example per language' }
    $file = Join-Path $demo $entry.Path
    New-Item -ItemType Directory -Force -Path (Split-Path $file -Parent) | Out-Null
    [IO.File]::WriteAllText($file, $blocks[0].Groups[1].Value)
}
$requirements = @{ schemaVersion = 1; requirements = @(@{
    id = 'REQ-HELLO-001'; version = '1'; status = 'approved'; text = 'Sayfada Merhaba TestTR! karşılama mesajı görünür.'
}) } | ConvertTo-Json -Depth 5
[IO.File]::WriteAllText((Join-Path $demo 'src/test/requirements.json'), $requirements)
$fixture = @{ drafts = @(@{
    requirement = 'REQ-HELLO-001'; version = '1'; rationale = 'Karşılama mesajı için ilk kapsam.'
    source = @'
Senaryo: "AI taslak koşum kontrolü".
  Gereksinim: "REQ-HELLO-001".
  "Ana Sayfa" sayfası açılır.
  "Karşılama Mesajı" metni "Merhaba TestTR!" olmalıdır.
Senaryo sonu.
'@
}); review = @() } | ConvertTo-Json -Depth 5
$fixturePath = Join-Path $demo 'response.json'
[IO.File]::WriteAllText($fixturePath, $fixture)

function Invoke-Maven([string[]] $MavenArgs, [string] $Log) {
    if ($IsWindows) { & (Join-Path $testtr 'mvnw.cmd') @MavenArgs *> $Log }
    else { & sh (Join-Path $testtr 'mvnw') @MavenArgs *> $Log }
    if ($LASTEXITCODE -ne 0) { Get-Content $Log -Tail 30 | Write-Host; throw "Maven failed: $Log" }
}
Invoke-Maven -MavenArgs @('--batch-mode', '-f', (Join-Path $testtr 'pom.xml'), '-pl', 'maven-plugin,runner-support', '-am', 'install') -Log (Join-Path $demo 'install.log')
Invoke-Maven -MavenArgs @('--batch-mode', '-f', (Join-Path $demo 'pom.xml'), 'dev.testtr:testtr-maven-plugin:0.1.0-SNAPSHOT:draft', "-Dtesttr.aiResponse=$fixturePath") -Log (Join-Path $demo 'draft.log')
$manifests = @(Get-ChildItem (Join-Path $demo 'target/testtr-drafts/*/manifest.json'))
if ($manifests.Count -ne 1) { throw 'Expected one draft manifest' }
$manifest = Get-Content $manifests[0].FullName -Raw | ConvertFrom-Json
if ($manifest.status -ne 'ready_for_review' -or $manifest.drafts.Count -ne 1) { throw 'Draft validation failed' }
if (Test-Path (Join-Path $demo 'src/test/testtr')) { throw 'Draft goal modified scenario sources' }

# Promote only this hand-written fixture in the isolated acceptance-test consumer.
$sources = Join-Path $demo 'src/test/testtr'
New-Item -ItemType Directory -Force -Path $sources | Out-Null
Copy-Item (Join-Path $manifests[0].DirectoryName 'draft-1.testtr') (Join-Path $sources 'hello.testtr')
$resources = (Resolve-Path (Join-Path $demo 'src/test/resources')).Path
$baseUrl = ([uri]($resources + [IO.Path]::DirectorySeparatorChar)).AbsoluteUri
Invoke-Maven -MavenArgs @('--batch-mode', '-f', (Join-Path $demo 'pom.xml'), "-Dtesttr.baseUrl=$baseUrl", 'test') -Log (Join-Path $demo 'test.log')
$reports = @(Get-ChildItem (Join-Path $demo 'target/surefire-reports/TEST-*.xml'))
if ($reports.Count -ne 1) { throw 'Expected one generated JUnit test' }
$report = [xml](Get-Content $reports[0].FullName -Raw -Encoding utf8)
if ($report.testsuite.tests -ne '1' -or $report.testsuite.failures -ne '0' -or $report.testsuite.errors -ne '0' -or $report.testsuite.skipped -ne '0') {
    throw 'Expected one passing generated browser test'
}
Write-Host "Offline draft goal, source isolation and generated browser test verified: $demo"
