const { test, beforeEach, afterEach } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const Module = require('node:module');
const { waitForJob } = require('../out/mcpClient');
let root, commands, calls, launches, config, ui, nextCall, document;
const uri = file => ({ fsPath: file, scheme: 'file', toString: () => file });
let folder;
const vscode = {
    Uri: { file: uri }, ProgressLocation: { Notification: 1 },
    RelativePattern: class {},
    workspace: {
        isTrusted: true,
        get workspaceFolders() { return [folder]; },
        getWorkspaceFolder: () => folder,
        getConfiguration: () => ({ get: (key, fallback) => config[key] ?? fallback }),
        saveAll: async () => true,
        openTextDocument: async value => { ui.opened.push(value); return typeof value === 'object' && value.fsPath ? { uri: value } : { ...value, uri: { scheme: 'untitled' } }; }
    },
    window: {
        get activeTextEditor() { return { document }; },
        showTextDocument: async value => { ui.shown.push(value); },
        showErrorMessage: async message => { ui.errors.push(message); },
        showWarningMessage: async message => { ui.warnings.push(message); },
        showInformationMessage: async message => { ui.info.push(message); },
        showQuickPick: async (items, options) => { ui.picks.push(items); return options?.canPickMany ? [items[0]] : items[0]; },
        showOpenDialog: async () => [uri(path.join(root, 'proposal.json'))],
        showSaveDialog: async () => uri(path.join(root, 'snapshot.json')),
        withProgress: async (_options, work) => work({ report() {} }, { isCancellationRequested: ui.cancel })
    },
    commands: {
        registerCommand: (name, callback) => { commands.set(name, callback); return { dispose() {} }; },
        executeCommand: async (...args) => { ui.executed.push(args); }
    }
};
const loader = Module._load;
Module._load = function (name, parent, ...rest) {
    if (parent?.filename.endsWith('mcpCommands.js')) {
        if (name === 'vscode') return vscode;
        if (name === './mcpClient') return { waitForJob, McpClient: { connect: async launch => {
            launches.push(launch);
            return { alive: true, close: async () => {}, call: async (name, args) => { calls.push({ name, args }); return nextCall(name, args); } };
        } } };
    }
    return loader.call(this, name, parent, ...rest);
};
const { registerMcpCommands, closeMcpConnections, hasMcpOperation } = require('../out/mcpCommands');
Module._load = loader;
beforeEach(async () => {
    root = await fs.mkdtemp(path.join(os.tmpdir(), 'testtr-editor-'));
    folder = { uri: uri(root), name: 'test' };
    await fs.mkdir(path.join(root, 'src/test/testtr/nested'), { recursive: true });
    await fs.writeFile(path.join(root, 'pom.xml'), '<project/>');
    await fs.writeFile(path.join(root, 'server.jar'), '');
    config = { 'mcp.testtrJar': 'server.jar', 'mcp.discoveryJar': 'server.jar', 'mcp.mavenHome': root, profile: 'playwright' };
    document = { uri: uri(path.join(root, 'src/test/testtr/nested/a.testtr')), languageId: 'testtr' };
    commands = new Map(); calls = []; launches = [];
    ui = { opened: [], shown: [], errors: [], warnings: [], info: [], picks: [], executed: [], cancel: false };
    nextCall = () => { throw new Error('Unexpected tool call'); };
    registerMcpCommands({ subscriptions: [] });
});
afterEach(async () => {
    await closeMcpConnections();
    assert.equal(path.dirname(root), path.resolve(os.tmpdir()));
    assert.ok(path.basename(root).startsWith('testtr-editor-'));
    await fs.rm(root, { recursive: true, force: true });
});
test('preview passes source identity and module inputs, then opens Java', async () => {
    nextCall = () => ({ scenarios: [{ name: 'Örnek', java: 'class Preview {}' }] });
    await commands.get('testtr.mcp.preview')();
    assert.deepEqual(ui.errors, []);
    assert.equal(calls[0].name, 'generate_java');
    assert.equal(calls[0].args.relativeSource, 'nested/a.testtr');
    assert.equal(calls[0].args.framework, 'PLAYWRIGHT');
    assert.equal(calls[0].args.helpers, path.join(root, 'src/test/helpers.json'));
    assert.equal(ui.opened[0].content, 'class Preview {}');
    assert.ok(!launches[0].args.includes('mcp')); // TestTR and Discovery have different entry points.
});
test('review uses Discovery and opens native before/after diff', async () => {
    nextCall = () => ({ report: { candidates: [{ name: 'Kaydet', pageName: 'Form', status: 'locator-change', validation: 'passed', before: { css: '#old' }, after: { css: '#new' }, reasons: [] }] } });
    await commands.get('testtr.mcp.reviewRegistry')();
    assert.deepEqual(ui.errors, []);
    assert.ok(launches[0].args.includes('mcp'));
    assert.equal(ui.executed[0][0], 'vscode.diff');
    assert.match(ui.opened[1].content, /#old/); assert.match(ui.opened[2].content, /#new/);
    assert.equal(calls.length, 1);
});
test('apply sends only explicitly selected eligible IDs to separate snapshot', async () => {
    nextCall = name => name === 'read_report' ? { report: { candidates: [
        { id: 'accepted', name: 'Kaydet', pageName: 'Form', status: 'new', validation: 'passed', after: { css: '#save' } },
        { id: 'review', name: 'Belirsiz', status: 'review', validation: 'passed', after: { css: '#ambiguous' } }
    ] } } : {};
    await commands.get('testtr.mcp.applyRegistry')();
    assert.deepEqual(ui.errors, []);
    assert.deepEqual(calls[1].args.accept, ['accepted']);
    assert.equal(calls[1].args.output, path.join(root, 'snapshot.json'));
    assert.notEqual(calls[1].args.registry, calls[1].args.output);
});
test('test cancellation waits for cleanup and results retain source navigation', async () => {
    ui.cancel = true;
    nextCall = name => {
        if (name === 'run_tests') return { jobId: 'job', status: 'running' };
        if (name === 'stop_job') return { jobId: 'job', status: 'running', cancelRequested: true };
        if (name === 'job_status') return { jobId: 'job', status: 'cancelled' };
        if (name === 'read_results') return { status: 'cancelled', results: { impact: { selected: [{ name: 'Örnek', scenarioId: 'Scenario_xTest', source: 'nested/a.testtr' }] } } };
        throw new Error(name);
    };
    await commands.get('testtr.mcp.test')();
    assert.deepEqual(ui.errors, []);
    assert.deepEqual(calls.map(c => c.name), ['run_tests', 'stop_job', 'job_status', 'read_results']);
    assert.equal(calls[0].args.selection, true);
    await commands.get('testtr.mcp.openResultSource')();
    assert.equal(ui.opened.at(-1).fsPath, document.uri.fsPath);
});
test('rejecting an overlapping command does not release the first operation lock', async () => {
    let finish, entered;
    const started = new Promise(resolve => entered = resolve);
    nextCall = () => { entered(); return new Promise(resolve => { finish = resolve; }); };
    const first = commands.get('testtr.mcp.validate')();
    await started;
    await commands.get('testtr.mcp.validate')();
    assert.equal(hasMcpOperation(), true); assert.equal(calls.length, 1);
    finish({ scenarios: [{}] }); await first;
    assert.equal(hasMcpOperation(), false);
});
test('untrusted workspaces never spawn a server', async () => {
    vscode.workspace.isTrusted = false;
    try { await commands.get('testtr.mcp.preview')(); assert.equal(launches.length, 0); assert.equal(ui.errors.length, 1); }
    finally { vscode.workspace.isTrusted = true; }
});
