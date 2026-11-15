const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { McpClient, waitForJob } = require('../out/mcpClient');
function launch(mode = 'normal') { return { command: process.execPath, args: [path.join(__dirname, 'fake-server.cjs'), mode], cwd: __dirname }; }

test('handshake precedes tools; concurrent responses are correlated by ID', async () => {
    const client = await McpClient.connect(launch());
    try {
        const [slow, fast] = await Promise.all([client.call('echo', { text: 'Türkçe \n "quoted"', delay: 50 }), client.call('echo', { text: 'fast' })]);
        assert.equal(slow.text, 'Türkçe \n "quoted"'); assert.equal(fast.text, 'fast');
        await assert.rejects(client.call('unknown', {}), /sunulmuyor/);
        await assert.rejects(client.call('tool_error', {}), /kaynak.testtr:3/);
        await assert.rejects(client.call('rpc_error', {}), /-32602/);
        assert.equal((await client.call('echo', { still: 'alive' })).still, 'alive');
    } finally { await client.close(); }
});
test('UTF-8 JSON lines can arrive split across stream chunks', async () => {
    const client = await McpClient.connect(launch('chunks'));
    try { assert.equal((await client.call('echo', { name: 'İşlem' })).name, 'İşlem'); }
    finally { await client.close(); }
});
test('invalid stdout and unsupported negotiation fail without hanging', async () => {
    await assert.rejects(McpClient.connect(launch('invalid')), /geçersiz JSON/);
    await assert.rejects(McpClient.connect(launch('unsupported')), /uyumsuz/);
    await assert.rejects(McpClient.connect({ command: path.join(__dirname, 'missing-command'), args: [], cwd: __dirname }), /başlatılamadı/);
});
test('request timeout closes ambiguous connection without retry', async () => {
    const client = await McpClient.connect(launch(), 500);
    try { await assert.rejects(client.call('hang', {}), /zaman aşımı/); assert.equal(client.alive, false); }
    finally { await client.close(); }
});
test('process exit rejects pending requests', async () => {
    const client = await McpClient.connect(launch());
    try { await assert.rejects(client.call('exit', {}), /kapandı/); }
    finally { await client.close(); }
});
test('progress cancellation stops the owned job and waits for terminal cleanup', async () => {
    const client = await McpClient.connect(launch());
    try {
        const states = [];
        const terminal = await waitForJob(client, await client.call('start', {}), { cancelled: () => true, status: job => states.push(job.status) });
        assert.equal(terminal.status, 'cancelled'); assert.deepEqual(states, ['running', 'cancelled']);
    } finally { await client.close(); }
});
