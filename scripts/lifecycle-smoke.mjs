// 검증 전용 프로젝트에 DB 장애/SIGTERM을 주입한다. 운영 URL/프로젝트 입력은 받지 않는다.
import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { randomUUID } from 'node:crypto';
const exec = promisify(execFile);
const project = 'orderpage-readiness';
const app = `${project}-app-1`;
const db = `${project}-mariadb-1`;
const base = 'http://127.0.0.1:18080';
const results = {};
async function docker(args, timeout = 20000) {
    return (await exec('docker', args, { timeout, maxBuffer: 1024 * 1024 })).stdout.trim();
}
async function request(path, status = 200, body) {
    const response = await fetch(base + path, { method: body ? 'POST' : 'GET',
        headers: body ? { 'Content-Type': 'application/json' } : {},
        body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(25000) });
    assert.equal(response.status, status, `${path} HTTP status`);
    return response.json();
}
async function ready() {
    const deadline = Date.now() + 60000;
    while (Date.now() < deadline) {
        try { if ((await request('/actuator/health/readiness')).status === 'UP') return; } catch { /* 기동/복구 대기 */ }
        await new Promise(resolve => setTimeout(resolve, 1000));
    }
    throw new Error('Readiness did not recover within 60 seconds');
}
async function stopAndVerify(label) {
    const since = String(Math.floor(Date.now() / 1000));
    const started = performance.now();
    await docker(['stop', app], 70000);
    const elapsedSeconds = Number(((performance.now() - started) / 1000).toFixed(3));
    const state = JSON.parse(await docker(['inspect', app, '--format', '{{json .State}}']));
    assert.equal(state.OOMKilled, false);
    assert.ok([0, 143].includes(state.ExitCode), `Unexpected exit code ${state.ExitCode}`);
    const eventText = await docker(['events', '--since', since, '--until', String(Math.floor(Date.now()/1000)+1),
        '--filter', `container=${app}`, '--format', '{{json .}}']);
    const events = eventText.split('\n').filter(Boolean).map(line => JSON.parse(line));
    const signals = events.filter(e => e.Action === 'kill').map(e => e.Actor.Attributes.signal);
    assert.ok(signals.includes('15'), 'SIGTERM was not observed');
    assert.ok(!signals.includes('9'), 'SIGKILL was observed');
    const logs = await docker(['logs', '--since', since, app]);
    assert.ok(logs.includes('Graceful shutdown complete'), 'HTTP graceful completion not logged');
    assert.ok(logs.includes('Shutdown completed.'), 'DB pool cleanup not completed');
    results[label] = { elapsedSeconds, exitCode: state.ExitCode, oomKilled: state.OOMKilled, signals };
    console.log(`PASS ${label}: exit=${state.ExitCode}, SIGTERM only, ${elapsedSeconds}s`);
}

try {
    for (const name of [app, db]) {
        assert.equal(await docker(['inspect', name, '--format', '{{index .Config.Labels "com.docker.compose.project"}}']), project);
    }
    assert.equal(await docker(['port', app, '8080/tcp']), '127.0.0.1:18080');
    assert.equal(Number(await docker(['inspect', app, '--format', '{{.Config.StopTimeout}}'])), 60);
    await ready();
    const restartBefore = await docker(['inspect', app, '--format', '{{.RestartCount}}']);
    try {
        await docker(['stop', db]);
        assert.equal((await request('/actuator/health/liveness')).status, 'UP');
        assert.equal((await request('/actuator/health/readiness', 503)).status, 'DOWN');
        results.databaseOutage = { livenessHTTP: 200, readinessHTTP: 503 };
        console.log('PASS database stopped: liveness=200, readiness=503');
    } finally {
        await docker(['start', db]);
    }
    await ready();
    assert.equal(await docker(['inspect', app, '--format', '{{.RestartCount}}']), restartBefore);
    results.databaseRecovery = { readinessHTTP: 200, appRestartCountUnchanged: true };
    console.log('PASS database recovered: readiness=200, no app restart');

    const email = `lifecycle-${randomUUID()}@example.test`;
    const password = randomUUID();
    await request('/member/create', 201, { name: 'lifecycle-smoke', email, password });
    const token = (await request('/doLogin', 200, { email, password })).result.token;
    const abort = new AbortController();
    const timer = setTimeout(() => abort.abort(), 70000);
    try {
        const response = await fetch(base + '/subscribe', { headers: { Authorization: `Bearer ${token}` }, signal: abort.signal });
        assert.equal(response.status, 200);
        const reader = response.body.getReader();
        let received = '';
        while (!received.includes('data:connected')) {
            const chunk = await reader.read();
            assert.equal(chunk.done, false, 'SSE ended before connect');
            received += new TextDecoder().decode(chunk.value);
        }
        // 앱 중지 전 열린 SSE 연결에서 서버가 보내는 정상 EOF를 기다린다.
        const end = (async () => {
            try { while (!(await reader.read()).done) { /* drain */ } return true; }
            catch { return false; }
        })();
        await stopAndVerify('activeSseShutdown');
        assert.equal(await end, true, 'SSE was aborted instead of ending cleanly');
        results.activeSseShutdown.sseEOF = true;
    } finally {
        clearTimeout(timer);
        abort.abort();
    }
    await docker(['start', app]);
    await ready();
    await stopAndVerify('idleShutdown');
    console.log(JSON.stringify({ checkedAt: new Date().toISOString(), project, results }, null, 2));
} catch (error) {
    console.error(`FAIL lifecycle verification: ${error.message}`);
    process.exitCode = 1;
}
