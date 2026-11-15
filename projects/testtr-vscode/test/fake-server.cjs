const readline = require('node:readline');
const mode = process.argv[2] || 'normal';
function send(value) {
    const line = JSON.stringify(value) + '\n';
    if (mode === 'chunks') { process.stdout.write(line.slice(0, 8)); setTimeout(() => process.stdout.write(line.slice(8)), 5); }
    else process.stdout.write(line);
}
let initialized = false;
readline.createInterface({ input: process.stdin }).on('line', line => {
    const request = JSON.parse(line);
    if (request.method === 'notifications/initialized') { initialized = true; return; }
    const response = result => send({ jsonrpc: '2.0', id: request.id, result });
    if (request.method === 'initialize') {
        if (mode === 'invalid') { process.stdout.write('not JSON\n'); return; }
        response({ protocolVersion: mode === 'unsupported' ? '2099-01-01' : '2025-11-25', capabilities: { tools: {} } });
    } else if (request.method === 'tools/list') {
        if (!initialized) { process.exit(3); return; }
        response({ tools: ['echo', 'tool_error', 'rpc_error', 'hang', 'exit', 'start', 'job_status', 'stop_job'].map(name => ({ name })) });
    } else {
        const { name, arguments: args } = request.params;
        if (name === 'hang') return;
        if (name === 'exit') { process.exit(0); return; }
        if (name === 'rpc_error') { send({ jsonrpc: '2.0', id: request.id, error: { code: -32602, message: 'invalid argument' } }); return; }
        if (name === 'tool_error') { response({ isError: true, content: [{ type: 'text', text: 'DSL: kaynak.testtr:3' }] }); return; }
        let value = args;
        if (name === 'start') value = { jobId: 'owned-job', status: 'running' };
        if (name === 'stop_job') { initialized = 'cancelled'; value = { jobId: args.jobId, status: 'running', cancelRequested: true }; }
        if (name === 'job_status') value = { jobId: args.jobId, status: initialized === 'cancelled' ? 'cancelled' : 'completed' };
        setTimeout(() => response({ isError: false, structuredContent: value }), args.delay || 0);
    }
});
