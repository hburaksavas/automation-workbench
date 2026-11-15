import * as vscode from 'vscode';
import { access } from 'node:fs/promises';
import * as path from 'node:path';
import { randomUUID } from 'node:crypto';
import { McpClient, JsonObject, waitForJob } from './mcpClient';

type Kind = 'testtr' | 'discovery';
interface Project { folder: vscode.WorkspaceFolder; module: string }
interface Connection { key: string; client: Promise<McpClient> }
const sessions = new Map<string, Connection>();
const active = new Set<string>();
const reports = new Map<string, string>();
const proposals = new Map<string, string>();
const resultsByWorkspace = new Map<string, { project: Project; value: JsonObject }>();
let closing = false;

function settings(folder: vscode.WorkspaceFolder) { return vscode.workspace.getConfiguration('testtr', folder.uri); }
async function exists(file: string) { try { await access(file); return true; } catch { return false; } }
async function workspace(): Promise<vscode.WorkspaceFolder | undefined> {
    if (!vscode.workspace.isTrusted) throw new Error('MCP araçları için güvenilen bir çalışma alanı gerekir.');
    const folders = vscode.workspace.workspaceFolders ?? [];
    const current = vscode.window.activeTextEditor?.document;
    const folder = current && vscode.workspace.getWorkspaceFolder(current.uri);
    if (folder) return folder;
    if (folders.length === 1) return folders[0];
    if (!folders.length) throw new Error('Önce bir çalışma alanı açın.');
    return vscode.window.showWorkspaceFolderPick();
}
async function project(folder: vscode.WorkspaceFolder): Promise<Project | undefined> {
    const configured = settings(folder).get<string>('mcp.pom', '');
    if (configured) {
        const pom = path.resolve(folder.uri.fsPath, configured);
        if (path.basename(pom) !== 'pom.xml' || !await exists(pom)) throw new Error('testtr.mcp.pom mevcut test modülünün pom.xml dosyasını göstermelidir.');
        return { folder, module: path.dirname(pom) };
    }
    const document = vscode.window.activeTextEditor?.document;
    if (document?.uri.scheme === 'file' && vscode.workspace.getWorkspaceFolder(document.uri) === folder) {
        for (let dir = path.dirname(document.uri.fsPath); ; dir = path.dirname(dir)) {
            if (await exists(path.join(dir, 'pom.xml'))) return { folder, module: dir };
            if (dir === folder.uri.fsPath || path.dirname(dir) === dir) break;
        }
    }
    if (await exists(path.join(folder.uri.fsPath, 'pom.xml'))) return { folder, module: folder.uri.fsPath };
    const files = await vscode.workspace.findFiles(new vscode.RelativePattern(folder, '**/pom.xml'), '**/{target,node_modules,.git}/**', 100);
    const selected = await vscode.window.showQuickPick(files.map(uri => ({ label: path.relative(folder.uri.fsPath, uri.fsPath), uri })), { placeHolder: 'Test modülünün pom.xml dosyasını seçin' });
    return selected && { folder, module: path.dirname(selected.uri.fsPath) };
}
async function connection(folder: vscode.WorkspaceFolder, kind: Kind): Promise<McpClient> {
    if (closing) throw new Error('MCP bağlantıları kapatılıyor.');
    const config = settings(folder), root = folder.uri.fsPath;
    const jar = path.resolve(root, config.get<string>(`mcp.${kind}Jar`, ''));
    if (!await exists(jar) || !jar.endsWith('.jar')) throw new Error(`testtr.mcp.${kind}Jar ayarında derlenmiş JAR yolunu belirtin.`);
    const args = ['-jar', jar, ...(kind === 'discovery' ? ['mcp'] : []), '--root', root];
    const mavenHome = config.get<string>('mcp.mavenHome', '');
    if (kind === 'testtr' && mavenHome) args.push('--maven-home', path.resolve(root, mavenHome));
    const launch = { command: config.get<string>('mcp.java', 'java'), args, cwd: root };
    const key = JSON.stringify(launch), id = root + ':' + kind;
    const previous = sessions.get(id);
    if (previous) {
        const client = await previous.client;
        if (previous.key === key && client.alive) return client;
        await client.close(); sessions.delete(id);
    }
    const client = McpClient.connect(launch);
    const entry = { key, client }; sessions.set(id, entry);
    try { return await client; }
    catch (error) { if (sessions.get(id) === entry) sessions.delete(id); throw error; }
}
async function registry(project: Project): Promise<string> {
    const configured = settings(project.folder).get<string>('registry', '');
    if (configured) return path.resolve(project.module, configured);
    const directory = path.join(project.module, 'src/test/registry');
    return await exists(directory) ? directory : path.join(project.module, 'src/test/registry.json');
}
async function showJson(value: JsonObject, language = 'json') {
    await vscode.window.showTextDocument(await vscode.workspace.openTextDocument({ language, content: JSON.stringify(value, null, 2) }), { preview: true });
}
async function openFile(file: string) { await vscode.window.showTextDocument(await vscode.workspace.openTextDocument(vscode.Uri.file(file)), { preview: true }); }
async function pickJson(folder: vscode.WorkspaceFolder, title: string, previous?: string): Promise<string | undefined> {
    const files = await vscode.window.showOpenDialog({ title, canSelectMany: false, defaultUri: vscode.Uri.file(previous || folder.uri.fsPath), filters: { JSON: ['json'] } });
    return files?.[0].fsPath;
}
function output(project: Project, file: string) { return path.join(project.module, 'target/testtr-vscode', randomUUID(), file); }
function browser(folder: vscode.WorkspaceFolder): JsonObject {
    const config = settings(folder), options: JsonObject = {};
    const channel = config.get<string>('mcp.browserChannel', '');
    const flow = config.get<string>('mcp.discoveryFlow', '');
    if (channel) options.channel = channel;
    if (flow) options.flow = path.resolve(folder.uri.fsPath, flow);
    return options;
}
async function job(client: McpClient, name: string, args: JsonObject, title: string): Promise<JsonObject> {
    return vscode.window.withProgress({ location: vscode.ProgressLocation.Notification, title, cancellable: true }, async (progress, token) => {
        // Cancellation can arrive before start returns; waitForJob sends stop once the ID is known.
        const started = await client.call(name, args);
        return waitForJob(client, started, {
            cancelled: () => token.isCancellationRequested,
            status: value => progress.report({ message: `${value.jobId}: ${value.cancelRequested ? 'durduruluyor' : value.status}` })
        });
    });
}
async function compile(project: Project, preview: boolean) {
    const document = vscode.window.activeTextEditor?.document;
    if (document?.languageId !== 'testtr' || document.uri.scheme !== 'file') throw new Error('Önce bir .testtr dosyası açın.');
    const config = settings(project.folder), sourceRoot = path.resolve(project.module, config.get('sourceDirectory', 'src/test/testtr'));
    const relative = path.relative(sourceRoot, document.uri.fsPath).split(path.sep).join('/');
    if (relative.startsWith('../') || path.isAbsolute(relative)) throw new Error('Açık dosya testtr.sourceDirectory altında olmalıdır.');
    const client = await connection(project.folder, 'testtr');
    const args: JsonObject = { source: document.uri.fsPath, relativeSource: relative, registry: await registry(project), helpers: path.resolve(project.module, config.get('mcp.helpers', 'src/test/helpers.json')) };
    const requirements = path.resolve(project.module, config.get('mcp.requirements', 'src/test/requirements.json'));
    if (await exists(requirements)) args.requirements = requirements;
    let framework = config.get<string>('profile', 'default');
    if (framework === 'default') {
        const selected = await vscode.window.showQuickPick(['SELENIDE', 'SELENIUM', 'PLAYWRIGHT'], { placeHolder: 'MCP önizlemesi POM okumaz; hedef framework seçin' });
        if (!selected) return; framework = selected;
    }
    args.framework = framework.toUpperCase();
    const value = await client.call(preview ? 'generate_java' : 'validate_scenario', args);
    if (!preview) { await vscode.window.showInformationMessage(`${value.scenarios.length} TestTR senaryosu MCP ile doğrulandı.`); return; }
    const scenarios = value.scenarios as JsonObject[];
    const selected = scenarios.length === 1 ? scenarios[0] : (await vscode.window.showQuickPick(scenarios.map(s => ({ label: s.name, description: s.scenarioId, scenario: s }))))?.scenario;
    if (selected) await vscode.window.showTextDocument(await vscode.workspace.openTextDocument({ language: 'java', content: selected.java }), { preview: true });
}
async function runTests(project: Project, impactOnly: boolean) {
    const client = await connection(project.folder, 'testtr'), config = settings(project.folder);
    const args: JsonObject = { pom: path.join(project.module, 'pom.xml'), base: config.get('mcp.impactBase', 'HEAD'), timeoutSeconds: config.get('mcp.timeoutSeconds', 600) };
    if (!impactOnly) args.selection = true;
    const profile = config.get('profile', 'default'); if (profile !== 'default') args.profile = profile;
    const reportDirectory = config.get<string>('mcp.reportsDirectory', '');
    if (reportDirectory && !impactOnly) args.reportsDirectory = path.resolve(project.module, reportDirectory);
    const terminal = await job(client, impactOnly ? 'calculate_impact' : 'run_tests', args, impactOnly ? 'TestTR: Değişiklik etkisi' : 'TestTR: Etkilenen testler');
    const results = await client.call('read_results', { jobId: terminal.jobId });
    resultsByWorkspace.set(project.folder.uri.fsPath, { project, value: results });
    await showJson({ pom: args.pom, sourceDirectory: path.resolve(project.module, config.get('sourceDirectory', 'src/test/testtr')), ...results });
    if (terminal.status !== 'completed') await vscode.window.showWarningMessage(`TestTR işi ${terminal.status}. Çıkış kodu: ${terminal.exitCode ?? 'yok'}. Yerel log: ${terminal.log}`);
}
async function openResultSource(folder: vscode.WorkspaceFolder) {
    const last = resultsByWorkspace.get(folder.uri.fsPath);
    if (!last) throw new Error('Önce MCP etki analizi veya test koşumu çalıştırın.');
    const selected = (last.value.results?.impact?.selected ?? []) as JsonObject[];
    const cases = (last.value.results?.suites ?? []).flatMap((suite: JsonObject) => suite.testcases ?? []) as JsonObject[];
    const items = selected.map(scenario => {
        const tests = cases.filter(test => test.className?.split('.').pop() === scenario.scenarioId);
        return { label: scenario.name as string, description: tests.map(t => t.status).join(', ') || 'seçildi; koşum sonucu yok', detail: scenario.source as string, scenario };
    });
    if (!items.length) { await vscode.window.showInformationMessage('Son etki raporunda seçilen senaryo yok.'); return; }
    const item = await vscode.window.showQuickPick(items, { placeHolder: 'Kaynakta açılacak senaryoyu seçin' });
    if (!item) return;
    const sourceRoot = path.resolve(last.project.module, settings(folder).get('sourceDirectory', 'src/test/testtr'));
    const file = path.resolve(sourceRoot, item.scenario.source);
    const relative = path.relative(folder.uri.fsPath, file);
    if (relative.startsWith('..' + path.sep) || path.isAbsolute(relative)) throw new Error('Sonuç kaynağı çalışma alanı dışında.');
    await openFile(file);
}
async function discover(project: Project) {
    const url = await vscode.window.showInputBox({ title: 'Discovery: keşfedilecek sayfa adresi', placeHolder: 'https://uygulama.example', ignoreFocusOut: true });
    if (!url) return;
    const client = await connection(project.folder, 'discovery');
    const file = output(project, 'discovery.json'), config = settings(project.folder);
    const terminal = await job(client, 'start_discovery', { ...browser(project.folder), url, output: file, headless: false, duration: config.get('mcp.discoveryDuration', 300), timeoutSeconds: config.get('mcp.timeoutSeconds', 600) }, 'TestTR Discovery: Tarayıcıyı kullanın; bitirmek için İptal');
    if (await exists(file)) { reports.set(project.folder.uri.fsPath, file); await openFile(file); }
    else throw new Error(`Keşif raporu oluşmadı: ${terminal.status}`);
    if (terminal.status === 'failed' || terminal.status === 'timed-out') await vscode.window.showWarningMessage(`Discovery işi ${terminal.status}; rapor kısmi olabilir.`);
}
async function suggest(project: Project) {
    const root = project.folder.uri.fsPath;
    const report = await pickJson(project.folder, 'Discovery JSON raporunu seçin', reports.get(root)); if (!report) return;
    const config = settings(project.folder), client = await connection(project.folder, 'discovery');
    const file = output(project, 'proposal.json');
    const args: JsonObject = { discovery: [report], registry: await registry(project), output: file };
    const pageMap = config.get<string>('mcp.pageMap', ''), baseUrl = config.get<string>('mcp.baseUrl', '');
    if (pageMap) args.pageMap = path.resolve(root, pageMap); if (baseUrl) args.baseUrl = baseUrl;
    const value = await client.call('suggest_registry', args);
    proposals.set(root, file);
    await reviewProposal(project, file, value);
}
async function reviewProposal(project: Project, file: string, value: JsonObject) {
    await openFile(file);
    const candidates = (value.candidates ?? []) as JsonObject[];
    if (!candidates.length) { await vscode.window.showInformationMessage('Registry adayı bulunamadı.'); return; }
    const candidate = await vscode.window.showQuickPick(candidates.map(c => ({ label: `${c.pageName || '?'} / ${c.name}`, description: `${c.status} · ${c.validation}`, detail: (c.reasons ?? []).join(' '), candidate: c })), { placeHolder: 'Önceki ve önerilen locator farkını incelemek için aday seçin' });
    if (candidate) {
        const c = candidate.candidate;
        const before = await vscode.workspace.openTextDocument({ language: 'json', content: JSON.stringify(c.before ?? {}, null, 2) });
        const after = await vscode.workspace.openTextDocument({ language: 'json', content: JSON.stringify(c.after ?? {}, null, 2) });
        await vscode.commands.executeCommand('vscode.diff', before.uri, after.uri, `${c.pageName} / ${c.name}: Registry önerisi`);
    }
}
async function proposal(project: Project, validate: boolean) {
    const root = project.folder.uri.fsPath;
    const file = await pickJson(project.folder, 'Registry önerisi JSON dosyasını seçin', proposals.get(root)); if (!file) return;
    const client = await connection(project.folder, 'discovery');
    if (!validate) { await reviewProposal(project, file, (await client.call('read_report', { path: file })).report); return; }
    const destination = output(project, 'validated-proposal.json');
    const terminal = await job(client, 'validate_registry', { ...browser(project.folder), proposal: file, output: destination, headless: true, timeoutSeconds: settings(project.folder).get('mcp.timeoutSeconds', 600) }, 'TestTR Discovery: Locator doğrulama');
    if (terminal.status !== 'completed') throw new Error(`Locator doğrulaması ${terminal.status}; önceki öneri korunuyor.`);
    proposals.set(root, destination);
    await reviewProposal(project, destination, (await client.call('read_report', { path: destination })).report);
}
async function apply(project: Project) {
    const root = project.folder.uri.fsPath;
    const file = await pickJson(project.folder, 'Doğrulanmış Registry önerisini seçin', proposals.get(root)); if (!file) return;
    const client = await connection(project.folder, 'discovery');
    const value = (await client.call('read_report', { path: file })).report;
    const eligible = (value.candidates as JsonObject[]).filter(c => ['new', 'locator-change'].includes(c.status) && c.validation === 'passed');
    if (!eligible.length) { await vscode.window.showInformationMessage('Uygulanabilir, tarayıcıda doğrulanmış aday yok.'); return; }
    const accepted = await vscode.window.showQuickPick(eligible.map(c => ({ label: `${c.pageName} / ${c.name}`, description: c.status, detail: `${c.before?.css ?? '(yeni)'} → ${c.after.css}`, id: c.id })), { canPickMany: true, placeHolder: 'Yeni Registry snapshot’ına alınacak adayları açıkça seçin' });
    if (!accepted?.length) return;
    const destination = await vscode.window.showSaveDialog({ title: 'Yeni Registry snapshot dosyası', defaultUri: vscode.Uri.file(output(project, 'registry-snapshot.json')), filters: { JSON: ['json'] } });
    if (!destination) return;
    await client.call('apply_registry', { proposal: file, registry: await registry(project), accept: accepted.map(c => c.id), output: destination.fsPath });
    await openFile(destination.fsPath);
}
export async function closeMcpConnections() {
    closing = true;
    const current = [...sessions.values()]; sessions.clear();
    await Promise.allSettled(current.map(async entry => (await entry.client).close()));
    closing = false;
}
export function hasMcpOperation() { return active.size > 0; }
export function registerMcpCommands(context: vscode.ExtensionContext, legacyBusy: () => boolean = () => false) {
    const actions: Record<string, (project: Project) => Promise<void>> = {
        'validate': p => compile(p, false), 'preview': p => compile(p, true), 'impact': p => runTests(p, true),
        'test': p => runTests(p, false), 'discover': discover, 'suggestRegistry': suggest,
        'reviewRegistry': p => proposal(p, false), 'validateRegistry': p => proposal(p, true), 'applyRegistry': apply,
        'openResultSource': p => openResultSource(p.folder)
    };
    for (const [name, action] of Object.entries(actions)) context.subscriptions.push(vscode.commands.registerCommand(`testtr.mcp.${name}`, async () => {
        let id: string | undefined;
        try {
            const folder = await workspace(); if (!folder) return;
            if (active.has(folder.uri.fsPath) || legacyBusy()) throw new Error('Bu çalışma alanında bir TestTR işlemi sürüyor. Bitmesini veya iptal temizliğini bekleyin.');
            id = folder.uri.fsPath;
            active.add(id);
            const selected = await project(folder); if (!selected) return;
            if (!await vscode.workspace.saveAll()) return;
            await action(selected);
        } catch (error) { await vscode.window.showErrorMessage('TestTR MCP: ' + (error instanceof Error ? error.message : String(error))); }
        finally { if (id) active.delete(id); }
    }));
    context.subscriptions.push(vscode.commands.registerCommand('testtr.mcp.disconnect', closeMcpConnections), { dispose: () => { void closeMcpConnections(); } });
}
