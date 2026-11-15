import { spawn, ChildProcessWithoutNullStreams } from 'node:child_process';

export type JsonObject = Record<string, any>;
export interface ServerLaunch { command: string; args: string[]; cwd: string }

/** Narrow stdio MCP client for the two local TestTR servers. No shell or automatic retries. */
export class McpClient {
    private readonly child: ChildProcessWithoutNullStreams;
    private readonly pending = new Map<number, { resolve: (value: any) => void; reject: (error: Error) => void; timer: NodeJS.Timeout }>();
    private nextId = 0;
    private buffer = '';
    private closing = false;
    private exited = false;
    private readonly exit: Promise<void>;
    private tools = new Set<string>();
    private constructor(launch: ServerLaunch, private readonly timeoutMs: number) {
        this.child = spawn(launch.command, launch.args, { cwd: launch.cwd, shell: false, windowsHide: true, stdio: 'pipe' });
        this.child.stdout.setEncoding('utf8');
        this.child.stdout.on('data', (chunk: string) => this.receive(chunk));
        // Drain stderr; project output must not be copied into MCP results or the editor log.
        this.child.stderr.resume();
        this.exit = new Promise(resolve => {
            this.child.once('error', () => { this.fail(new Error('MCP server başlatılamadı. Java ve JAR yollarını kontrol edin.')); resolve(); });
            this.child.once('close', () => {
                this.exited = true;
                this.fail(new Error('MCP server bağlantısı kapandı. Komutu yeniden çalıştırarak bağlanabilirsiniz.'));
                resolve();
            });
        });
        this.child.stdin.on('error', () => this.fail(new Error('MCP server giriş bağlantısı kapandı.')));
    }
    static async connect(launch: ServerLaunch, timeoutMs = 30_000): Promise<McpClient> {
        const client = new McpClient(launch, timeoutMs);
        try {
            const init = await client.request('initialize', {
                protocolVersion: '2025-11-25', capabilities: {}, clientInfo: { name: 'testtr-vscode', version: '0.1.0' }
            });
            if (init?.protocolVersion !== '2025-11-25' || !init?.capabilities?.tools) throw new Error('MCP server sürümü veya araç desteği uyumsuz.');
            client.send({ jsonrpc: '2.0', method: 'notifications/initialized' });
            const list = await client.request('tools/list', {});
            if (!Array.isArray(list?.tools)) throw new Error('MCP araç listesi geçersiz.');
            client.tools = new Set(list.tools.map((tool: any) => tool.name));
            return client;
        } catch (error) {
            await client.close();
            throw error;
        }
    }
    get alive() { return !this.closing && !this.exited; }
    private send(message: JsonObject) {
        if (this.closing || this.exited) throw new Error('MCP bağlantısı kapalı.');
        this.child.stdin.write(JSON.stringify(message) + '\n');
    }
    private request(method: string, params: JsonObject): Promise<any> {
        return new Promise((resolve, reject) => {
            const id = ++this.nextId;
            const timer = setTimeout(() => {
                this.fail(new Error('MCP isteği zaman aşımına uğradı; bağlantı kapatılıyor. İşlem otomatik yinelenmez.'));
            }, this.timeoutMs);
            this.pending.set(id, { resolve, reject, timer });
            try { this.send({ jsonrpc: '2.0', id, method, params }); }
            catch (error) {
                clearTimeout(timer); this.pending.delete(id); reject(error);
            }
        });
    }
    private receive(chunk: string) {
        if (this.closing) return;
        this.buffer += chunk;
        if (this.buffer.length > 16 * 1024 * 1024) { this.fail(new Error('MCP yanıt boyutu sınırı aşıldı.')); return; }
        let newline: number;
        while ((newline = this.buffer.indexOf('\n')) >= 0) {
            const line = this.buffer.slice(0, newline).trim(); this.buffer = this.buffer.slice(newline + 1);
            if (!line) continue;
            let message: JsonObject;
            try { message = JSON.parse(line); }
            catch { this.fail(new Error('MCP server stdout üzerinde geçersiz JSON döndürdü.')); return; }
            if (!message || message.jsonrpc !== '2.0') { this.fail(new Error('Geçersiz MCP yanıtı.')); return; }
            if (message.method) {
                // These servers require no client capabilities. Handle ping defensively.
                if (message.id !== undefined) this.send(message.method === 'ping'
                    ? { jsonrpc: '2.0', id: message.id, result: {} }
                    : { jsonrpc: '2.0', id: message.id, error: { code: -32601, message: 'Method not supported' } });
                continue;
            }
            const pending = this.pending.get(message.id);
            if (!pending) continue;
            this.pending.delete(message.id); clearTimeout(pending.timer);
            if (message.error) pending.reject(new Error(`MCP ${message.error.code}: ${message.error.message}`));
            else if (!('result' in message)) pending.reject(new Error('MCP yanıtında result eksik.'));
            else pending.resolve(message.result);
        }
    }
    private fail(error: Error) {
        for (const pending of this.pending.values()) { clearTimeout(pending.timer); pending.reject(error); }
        this.pending.clear();
        void this.close();
    }
    async call(name: string, args: JsonObject): Promise<JsonObject> {
        if (!this.tools.has(name)) throw new Error(`MCP aracı sunulmuyor: ${name}`);
        const result = await this.request('tools/call', { name, arguments: args });
        if (result?.isError) throw new Error((result.content ?? []).filter((c: any) => c.type === 'text').map((c: any) => c.text).join('\n') || 'MCP işlemi başarısız.');
        const value = result?.structuredContent;
        if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('MCP aracının yapılandırılmış sonucu eksik.');
        return value;
    }
    async close(): Promise<void> {
        if (!this.closing) {
            this.closing = true;
            for (const pending of this.pending.values()) { clearTimeout(pending.timer); pending.reject(new Error('MCP bağlantısı kapatılıyor.')); }
            this.pending.clear();
            this.child.stdin.end(); // EOF lets each server cancel its owned jobs and clean up.
        }
        const timer = setTimeout(() => { if (!this.exited) this.child.kill(); }, 40_000);
        timer.unref();
        await this.exit;
        clearTimeout(timer);
    }
}

export interface JobProgress {
    cancelled(): boolean;
    status(value: JsonObject): void;
}
export async function waitForJob(client: McpClient, job: JsonObject, progress: JobProgress): Promise<JsonObject> {
    if (typeof job.jobId !== 'string') throw new Error('MCP iş kimliği eksik.');
    let stopping = false;
    while (!['completed', 'failed', 'cancelled', 'timed-out'].includes(job.status)) {
        if (progress.cancelled() && !stopping) {
            await client.call('stop_job', { jobId: job.jobId }); stopping = true;
        }
        progress.status(job);
        await new Promise(resolve => setTimeout(resolve, 300));
        job = await client.call('job_status', { jobId: job.jobId });
    }
    progress.status(job);
    return job;
}
