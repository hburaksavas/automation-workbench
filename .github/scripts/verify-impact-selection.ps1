$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$repo = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$testtr = Join-Path $repo 'projects/testtr'
$demo = Join-Path $repo ('target/selection-consumer-' + [guid]::NewGuid().ToString('N'))
$guide = Get-Content (Join-Path $testtr 'ilk-test.md') -Raw -Encoding utf8
foreach ($entry in @(@{ Language = 'xml'; Path = 'pom.xml' }, @{ Language = 'html'; Path = 'src/test/resources/index.html' })) {
    $blocks = [regex]::Matches($guide, '(?ms)^```' + $entry.Language + '\r?\n(.*?)^```')
    if ($blocks.Count -ne 1) { throw 'Expected one guide example' }
    $file = Join-Path $demo $entry.Path
    New-Item -ItemType Directory -Force -Path (Split-Path $file -Parent) | Out-Null
    [IO.File]::WriteAllText($file, $blocks[0].Groups[1].Value)
}
New-Item -ItemType Directory -Force -Path @("$demo/src/test/registry", "$demo/src/test/testtr", "$demo/src/test/java", "$demo/app/shared", "$demo/logs") | Out-Null
[IO.File]::WriteAllText("$demo/.gitignore", "target/`nlogs/`n")
[IO.File]::WriteAllText("$demo/src/test/registry/pages.json", '{"pages":{"A":{"url":"index.html","objects":{"Mesaj":{"type":"text","css":"#mesaj"}}},"B":{"url":"index.html","objects":{"Mesaj":{"type":"text","css":"#mesaj"}}}}}')
[IO.File]::WriteAllText("$demo/src/test/impact.json", '{"schemaVersion":1,"pages":{"A":["app/A.js"],"B":["app/B.jsx"]},"imports":{"roots":["app"]}}')
foreach ($page in @('A', 'B')) {
    $source = "Senaryo: `"$page testi`".`n  `"$page`" sayfası açılır.`n  `"Mesaj`" metni `"Merhaba TestTR!`" olmalıdır.`nSenaryo sonu.`n"
    [IO.File]::WriteAllText("$demo/src/test/testtr/$page.testtr", $source)
}
$pageA = "import Card from './shared/Card'; export default Card;`n"
[IO.File]::WriteAllText("$demo/app/A.js", $pageA)
[IO.File]::WriteAllText("$demo/app/shared/Card.jsx", "export default () => null;`n")
[IO.File]::WriteAllText("$demo/app/B.jsx", "export default () => null;`n")
[IO.File]::WriteAllText("$demo/src/test/java/ExistingTest.java", 'public class ExistingTest { @org.junit.jupiter.api.Test void existing() { throw new AssertionError("Must not run during TestTR selection"); } }')

function Invoke-Git([string[]] $GitArgs) {
    & git -C $demo @GitArgs *> "$demo/logs/git.log"
    if ($LASTEXITCODE -ne 0) { throw 'Fixture Git command failed' }
}
Invoke-Git -GitArgs @('init')
Invoke-Git -GitArgs @('add', '.')
Invoke-Git -GitArgs @('-c', 'user.name=TestTR', '-c', 'user.email=testtr@example.invalid', '-c', 'commit.gpgsign=false', 'commit', '-m', 'selection fixture')

function Invoke-Wrapper([string[]] $MavenArgs, [string] $Log) {
    if ($IsWindows) { & (Join-Path $testtr 'mvnw.cmd') @MavenArgs *> $Log }
    else { & sh (Join-Path $testtr 'mvnw') @MavenArgs *> $Log }
    $code = $LASTEXITCODE
    if ($code -ne 0) { Get-Content $Log -Tail 16 | Write-Host }
    return $code
}
$install = Invoke-Wrapper -MavenArgs @('--batch-mode', '-f', "$testtr/pom.xml", '-pl', 'maven-plugin,runner-support', '-am', 'install', '-DskipTests') -Log "$demo/logs/install.log"
if ($install -ne 0) { throw 'Component install failed' }
$resources = (Resolve-Path "$demo/src/test/resources").Path
$baseUrl = ([uri]($resources + [IO.Path]::DirectorySeparatorChar)).AbsoluteUri
function Invoke-Selection([string] $Name, [int] $ExpectedCount, [bool] $ExpectedFailure = $false) {
    $code = Invoke-Wrapper -MavenArgs @('--batch-mode', '-f', "$demo/pom.xml", "-Dtesttr.baseUrl=$baseUrl", 'clean', 'dev.testtr:testtr-maven-plugin:0.1.0-SNAPSHOT:select', 'test') -Log "$demo/logs/$Name.log"
    if (($code -ne 0) -ne $ExpectedFailure) { throw "Unexpected Maven outcome: $Name" }
    $reports = @(Get-ChildItem "$demo/target/surefire-reports/TEST-*.xml" -ErrorAction SilentlyContinue)
    if ($reports.Count -ne $ExpectedCount) { throw "Unexpected report count for ${Name}: $($reports.Count)" }
    foreach ($file in $reports) {
        if ($file.Name -eq 'TEST-ExistingTest.xml') { throw 'Unrelated Java test was selected' }
        $xml = [xml](Get-Content $file.FullName -Raw -Encoding utf8)
        $failed = ([int]$xml.testsuite.failures + [int]$xml.testsuite.errors) -gt 0
        if ($failed -ne $ExpectedFailure -or $xml.testsuite.tests -ne '1' -or $xml.testsuite.skipped -ne '0') {
            throw "Unexpected JUnit result: $Name"
        }
    }
    $impact = Get-Content "$demo/target/testtr-impact.json" -Raw | ConvertFrom-Json
    if ($impact.selected.Count -ne $ExpectedCount) { throw 'Report selection differs from actual run' }
    Copy-Item -LiteralPath "$demo/target/testtr-impact.json" -Destination "$demo/logs/$Name-impact.json"
    if ($Name -eq 'imported-component') {
        if ($impact.changedFiles.Count -ne 1 -or $impact.changedFiles[0] -ne 'app/shared/Card.jsx' -or
            !($impact.selected[0].reasons -match 'Import bağımlılığı:')) {
            throw 'Imported component must be selected through its dependency chain alone'
        }
    }
}
Invoke-Selection -Name 'empty' -ExpectedCount 0
[IO.File]::AppendAllText("$demo/app/A.js", "// changed`n")
Invoke-Selection -Name 'targeted' -ExpectedCount 1
[IO.File]::WriteAllText("$demo/app/A.js", $pageA)
[IO.File]::AppendAllText("$demo/app/shared/Card.jsx", "// component changed`n")
Invoke-Selection -Name 'imported-component' -ExpectedCount 1
[IO.File]::WriteAllText("$demo/unknown.css", '/* unmapped change */')
Invoke-Selection -Name 'broad' -ExpectedCount 2
Remove-Item -LiteralPath "$demo/unknown.css"
$scenario = "$demo/src/test/testtr/A.testtr"
[IO.File]::WriteAllText($scenario, [IO.File]::ReadAllText($scenario).Replace('Merhaba TestTR!', 'Yanlış mesaj'))
Invoke-Selection -Name 'failure' -ExpectedCount 1 -ExpectedFailure $true
Write-Host "Empty, targeted, imported-component, broad and failing selection verified; unrelated Java test excluded: $demo"
