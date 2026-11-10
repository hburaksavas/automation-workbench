import * as vscode from 'vscode';
import { access, readFile, readdir, stat } from 'node:fs/promises';
import * as path from 'node:path';

const selector = { language: 'testtr', scheme: 'file' };
const windows = process.platform === 'win32';
const wrapper = windows ? 'mvnw.cmd' : 'mvnw';

async function ancestor(start: string, name: string): Promise<string | undefined> {
    for (let directory = start; ; directory = path.dirname(directory)) {
        try {
            await access(path.join(directory, name));
            return directory;
        } catch {
            if (path.dirname(directory) === directory) return undefined;
        }
    }
}

function config(document: vscode.TextDocument) {
    return vscode.workspace.getConfiguration('testtr', document.uri);
}

async function complete(document: vscode.TextDocument, position: vscode.Position) {
    const before = document.lineAt(position.line).text.slice(0, position.character);
    const prefix = /^\s*"((?:\\.|[^"\\])*)$/.exec(before);
    if (!prefix) return;
    const range = new vscode.Range(position.translate(0, -prefix[1].length), position);
    const names = new Map<string, vscode.CompletionItem>();
    const add = (name: string, detail: string, kind: vscode.CompletionItemKind) => {
        const item = new vscode.CompletionItem(name, kind);
        item.range = range;
        item.insertText = JSON.stringify(name).slice(1, -1);
        item.detail = detail;
        names.set(name + detail, item);
    };

    const module = await ancestor(path.dirname(document.uri.fsPath), 'pom.xml');
    if (module) {
        try {
            const setting = config(document).get<string>('registry');
            let registryPath = path.resolve(module, setting || 'src/test/registry');
            if (!setting) {
                try {
                    await access(registryPath);
                } catch {
                    registryPath = path.resolve(module, 'src/test/registry.json');
                }
            }
            const files = (await stat(registryPath)).isDirectory()
                ? (await readdir(registryPath, { withFileTypes: true }))
                    .filter(entry => entry.isFile() && entry.name.endsWith('.json'))
                    .map(entry => path.join(registryPath, entry.name)).sort()
                : [registryPath];
            const ordered = [
                ...files.filter(file => !file.endsWith('.override.json')),
                ...files.filter(file => file.endsWith('.override.json'))
            ];
            const pages = new Map<string, Set<string>>();
            for (const file of ordered) {
                const registry = JSON.parse(await readFile(file, 'utf8'));
                for (const [page, entry] of Object.entries<{ objects?: Record<string, unknown> | null } | null>(registry.pages ?? {})) {
                    if (!entry || typeof entry !== 'object' || Array.isArray(entry)) {
                        pages.delete(page);
                        continue;
                    }
                    const objects = entry.objects === null ? new Set<string>() : pages.get(page) ?? new Set<string>();
                    for (const name of Object.keys(entry.objects ?? {})) objects.add(name);
                    pages.set(page, objects);
                }
            }
            for (const [page, objects] of pages) {
                add(page, 'Sayfa', vscode.CompletionItemKind.Module);
                for (const object of objects) add(object, page, vscode.CompletionItemKind.Field);
            }
        } catch {
            // Maven reports missing or invalid registries during validation.
        }
    }

    const visited = new Set<string>();
    const scan = async (file: string, source: string) => {
        if (visited.has(file)) return;
        visited.add(file);
        for (const line of source.split(/\r?\n/)) {
            const definition = /^\s*Ortak adım: ("(?:\\.|[^"\\])*")\.\s*$/.exec(line);
            const imported = /^\s*("(?:\\.|[^"\\])*") dosyası kullanılır\.\s*$/.exec(line);
            try {
                if (definition) add(JSON.parse(definition[1]), 'Ortak adım', vscode.CompletionItemKind.Method);
                if (imported) {
                    const dependency = path.resolve(path.dirname(file), JSON.parse(imported[1]));
                    if (!visited.has(dependency)) await scan(dependency, await readFile(dependency, 'utf8'));
                }
            } catch {
                // Incomplete lines and imports are diagnosed by the Java compiler.
            }
        }
    };
    await scan(document.uri.fsPath, document.getText());
    return [...names.values()];
}

async function showGenerated(document: vscode.TextDocument, module: string) {
    const settings = config(document);
    const source = path.resolve(module, settings.get('sourceDirectory', 'src/test/testtr'));
    const relative = path.relative(source, document.uri.fsPath).split(path.sep).join('/');
    const marker = 'context.failure(' + JSON.stringify(relative).slice(0, -1) + ':';
    const generated = path.resolve(module, settings.get(
        'generatedDirectory', 'target/generated-test-sources/testtr/dev/testtr/generated'
    ));
    const files: { label: string; description: string }[] = [];
    for (const name of await readdir(generated)) {
        if (!/^Scenario_[a-f0-9]{64}Test\.java$/.test(name)) continue;
        const file = path.join(generated, name);
        const java = await readFile(file, 'utf8');
        if (java.includes(marker)) {
            const displayName = /@org\.junit\.jupiter\.api\.DisplayName\(("(?:\\.|[^"\\])*")\)/.exec(java);
            files.push({ label: displayName ? JSON.parse(displayName[1]) : name, description: file });
        }
    }
    if (!files.length) {
        await vscode.window.showInformationMessage('Bu dosya için üretilmiş Java senaryosu bulunamadı.');
        return;
    }
    const selected = files.length === 1 ? files[0] : await vscode.window.showQuickPick(files);
    if (selected) await vscode.window.showTextDocument(vscode.Uri.file(selected.description), { preview: true });
}

export function activate(context: vscode.ExtensionContext) {
    let running = false;
    async function run(action: 'validate' | 'preview' | 'test') {
        if (running) {
            await vscode.window.showInformationMessage('Bir TestTR Maven görevi zaten çalışıyor.');
            return;
        }
        const document = vscode.window.activeTextEditor?.document;
        if (!document || document.languageId !== 'testtr' || document.uri.scheme !== 'file') {
            await vscode.window.showInformationMessage('Önce bir .testtr dosyası açın.');
            return;
        }
        const folder = vscode.workspace.getWorkspaceFolder(document.uri);
        running = true;
        try {
            const start = path.dirname(document.uri.fsPath);
            const module = await ancestor(start, 'pom.xml');
            const root = await ancestor(start, wrapper);
            if (!module || !root || !folder) throw new Error('Maven projesini çalışma alanı olarak açın. Maven Wrapper bulunmalıdır.');
            if (!await vscode.workspace.saveAll()) return;
            const profile = config(document).get('profile', 'default');
            const args = ['--batch-mode', '-Dstyle.color=never', action === 'test' ? 'test' : 'test-compile'];
            if (['selenide', 'selenium', 'playwright'].includes(profile)) args.push('-P' + profile);
            const execution = windows
                ? new vscode.ProcessExecution('cmd.exe', ['/d', '/c', 'mvnw.cmd', ...args], { cwd: root })
                : new vscode.ProcessExecution('sh', ['./mvnw', ...args], { cwd: root });
            const task = new vscode.Task({ type: 'testtr', action }, folder, action, 'TestTR', execution, '$testtr-dsl');
            const exitCode = await new Promise<number | undefined>((resolve, reject) => {
                const matches = (execution: vscode.TaskExecution) =>
                    execution.task.source === task.source && execution.task.name === task.name;
                let code: number | undefined;
                const processEnd = vscode.tasks.onDidEndTaskProcess(event => {
                    if (matches(event.execution)) code = event.exitCode;
                });
                const taskEnd = vscode.tasks.onDidEndTask(event => {
                    if (!matches(event.execution)) return;
                    dispose();
                    resolve(code);
                });
                const dispose = () => {
                    processEnd.dispose();
                    taskEnd.dispose();
                };
                void Promise.resolve(vscode.tasks.executeTask(task)).catch(error => {
                    dispose();
                    reject(error);
                });
            });
            if (action === 'preview' && exitCode === 0) await showGenerated(document, module);
        } catch (error) {
            await vscode.window.showErrorMessage('TestTR: ' + (error instanceof Error ? error.message : String(error)));
        } finally {
            running = false;
        }
    }

    context.subscriptions.push(
        vscode.languages.registerCompletionItemProvider(selector, { provideCompletionItems: complete }, '"'),
        vscode.commands.registerCommand('testtr.validate', () => run('validate')),
        vscode.commands.registerCommand('testtr.preview', () => run('preview')),
        vscode.commands.registerCommand('testtr.test', () => run('test'))
    );
}
