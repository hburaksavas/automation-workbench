const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { randomUUID } = require('node:crypto');
const { McpClient, waitForJob } = require('../out/mcpClient');
const root = path.resolve(__dirname, '../../..');
const enabled = process.env.TESTTR_REAL_MCP === '1';
const finished = (client, job, cancel = false) => waitForJob(client, job, { cancelled: () => cancel, status() {} });

test('real servers: discovery → validated snapshot → TestTR preview, impact, Maven results and independent cancellation', { skip: !enabled, timeout: 180_000 }, async () => {
    assert.ok(process.env.TESTTR_MAVEN_HOME, 'Set TESTTR_MAVEN_HOME to the real Maven installation directory');
    const directory = path.join(root, 'target', 'testtr-vscode-acceptance', randomUUID());
    await fs.mkdir(directory, { recursive: true });
    const clients = [];
    try {
        const discovery = await McpClient.connect({ command: 'java', args: ['-jar', path.join(root, 'projects/testtr-discovery/target/testtr-discovery-0.1.0-SNAPSHOT.jar'), 'mcp', '--root', root], cwd: root });
        clients.push(discovery);
        const testtr = await McpClient.connect({ command: 'java', args: ['-jar', path.join(root, 'projects/testtr/mcp-server/target/testtr-mcp-server-0.1.0-SNAPSHOT.jar'), '--root', root, '--maven-home', process.env.TESTTR_MAVEN_HOME], cwd: root });
        clients.push(testtr);
        const html = path.join(directory, 'page.html');
        await fs.writeFile(html, '<!doctype html><html><body><button data-testid="save">Save</button></body></html>');
        const url = pathToFileURL(html).href;
        const registry = path.join(directory, 'registry.json');
        await fs.writeFile(registry, JSON.stringify({ pages: { Demo: { url, objects: { Existing: { type: 'button', css: '#old' } } } } }));
        const report = path.join(directory, 'discovery.json');
        // Both processes own independent jobs; their IDs and cancellation channels never cross.
        const [scan, analysis] = await Promise.all([
            discovery.call('start_discovery', { url, output: report, duration: 1, headless: true, timeoutSeconds: 30 }),
            testtr.call('calculate_impact', { pom: 'projects/testtr/example-tests/pom.xml', timeoutSeconds: 60 })
        ]);
        assert.equal((await finished(discovery, scan)).status, 'completed');
        assert.equal((await finished(testtr, analysis)).status, 'completed');
        const impact = await testtr.call('read_results', { jobId: analysis.jobId });
        assert.ok(Array.isArray(impact.results.impact.selected));
        const observed = (await discovery.call('read_report', { path: report })).report;
        assert.ok(observed.objects.some(object => object.name === 'Save'));
        const proposal = path.join(directory, 'proposal.json');
        const proposed = await discovery.call('suggest_registry', { discovery: [report], registry, output: proposal });
        const candidate = proposed.candidates.find(candidate => candidate.name === 'Save');
        assert.equal(candidate.status, 'new');
        const validated = path.join(directory, 'validated.json');
        const validation = await discovery.call('validate_registry', { proposal, output: validated, headless: true, timeoutSeconds: 30 });
        assert.equal((await finished(discovery, validation)).status, 'completed');
        const evidence = (await discovery.call('read_report', { path: validated })).report;
        assert.equal(evidence.candidates.find(c => c.id === candidate.id).validation, 'passed');
        const snapshot = path.join(directory, 'snapshot.json');
        await discovery.call('apply_registry', { proposal: validated, registry, output: snapshot, accept: [candidate.id] });
        assert.equal(JSON.parse(await fs.readFile(registry, 'utf8')).pages.Demo.objects.Save, undefined);
        assert.equal(JSON.parse(await fs.readFile(snapshot, 'utf8')).pages.Demo.objects.Save.css, '[data-testid="save"]');
        const source = path.join(directory, 'sample.testtr');
        await fs.writeFile(source, 'Senaryo: "Kaydet".\n"Demo" sayfası açılır.\n"Save" düğmesine tıklanır.\nSenaryo sonu.\n');
        const args = { source, relativeSource: 'sample.testtr', registry: snapshot, framework: 'PLAYWRIGHT' };
        const preview = await testtr.call('generate_java', args);
        assert.match(preview.scenarios[0].java, /data-testid/);
        assert.equal((await testtr.call('validate_scenario', args)).scenarios[0].scenarioId, preview.scenarios[0].scenarioId);
        const longScan = await discovery.call('start_discovery', { url, output: path.join(directory, 'cancelled.json'), duration: 60, headless: true, timeoutSeconds: 90 });
        assert.equal((await finished(discovery, longScan, true)).status, 'cancelled');
        assert.equal((await testtr.call('validate_scenario', args)).valid, true);
        await fs.mkdir(path.join(directory, 'src/test/java'), { recursive: true });
        await fs.writeFile(path.join(directory, 'src/test/java/ConsumerTest.java'), 'public class ConsumerTest { @org.junit.jupiter.api.Test void example() { org.junit.jupiter.api.Assertions.assertTrue(true); } }');
        await fs.writeFile(path.join(directory, 'pom.xml'), `<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
          <groupId>dev.testtr.acceptance</groupId><artifactId>editor-client</artifactId><version>1</version>
          <properties><maven.compiler.release>17</maven.compiler.release></properties>
          <dependencies><dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>5.11.4</version><scope>test</scope></dependency></dependencies>
          <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version></plugin>
          <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.5.2</version></plugin></plugins></build></project>`);
        const run = await testtr.call('run_tests', { pom: path.join(directory, 'pom.xml'), timeoutSeconds: 60 });
        assert.equal((await finished(testtr, run)).status, 'completed');
        const results = await testtr.call('read_results', { jobId: run.jobId });
        assert.equal(results.results.suites[0].testcases[0].status, 'passed');
    } finally {
        await Promise.allSettled(clients.map(client => client.close()));
        assert.equal(path.dirname(directory), path.join(root, 'target', 'testtr-vscode-acceptance'));
        await fs.rm(directory, { recursive: true, force: true });
    }
});
