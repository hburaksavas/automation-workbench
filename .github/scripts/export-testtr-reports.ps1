$ErrorActionPreference = 'Stop'
$output = 'target/ci-testtr-reports'
New-Item -ItemType Directory -Force -Path $output | Out-Null

Get-ChildItem 'example-tests/target/surefire-reports' -Filter 'TEST-dev.testtr.generated.*.xml' -ErrorAction SilentlyContinue |
    ForEach-Object {
        $report = [xml](Get-Content -LiteralPath $_.FullName -Raw -Encoding utf8)
        foreach ($node in $report.SelectNodes('/testsuite/properties | //system-err')) {
            [void]$node.ParentNode.RemoveChild($node)
        }
        foreach ($node in $report.SelectNodes('//system-out')) {
            $lines = $node.InnerText -split '\r?\n' | Where-Object { $_ -match '^TESTTR(?:_RUN)? \{' }
            $node.InnerText = $lines -join "`n"
        }
        $report.Save((Join-Path (Resolve-Path $output).Path $_.Name))
    }
