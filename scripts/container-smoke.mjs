// Node 18+ / Docker CLI. 반드시 compose.verify.yaml의 격리 환경에서만 실행한다.
// 운영 서버 URL/프로젝트를 인자로 받지 않으며 검증 DB에만 소량의 fixture를 추가한다.
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';

const base = 'http://127.0.0.1:18080';
const project = 'orderpage-readiness';
const results = [];
const docker = args => execFileSync('docker', args, { encoding: 'utf8', timeout: 15000 }).trim();
const sql = query => docker(['exec', '-e', 'MYSQL_PWD=1234', `${project}-mariadb-1`,
    'mariadb', '-uroot', '-N', '-B', 'ordersystem', '-e', query]);

async function check(name, action) {
    await action();
    results.push({ name, status: 'PASS' });
    console.log(`PASS ${name}`);
}

async function request(path, { method = 'GET', body, token, status = 200, headers = {} } = {}) {
    const response = await fetch(base + path, {
        method, signal: AbortSignal.timeout(15000),
        headers: { ...(body ? { 'Content-Type': 'application/json' } : {}),
            ...(token ? { Authorization: `Bearer ${token}` } : {}), ...headers },
        body: body ? JSON.stringify(body) : undefined
    });
    // 토큰/비밀번호/응답 body를 로그에 출력하지 않는다.
    assert.equal(response.status, status, `${method} ${path}: unexpected HTTP status`);
    const text = await response.text();
    return { headers: response.headers, data: text ? JSON.parse(text) : null };
}

try {
    for (const service of ['app', 'mariadb', 'redis', 'rabbitmq']) {
        assert.equal(docker(['inspect', `${project}-${service}-1`, '--format',
            '{{index .Config.Labels "com.docker.compose.project"}}']), project);
    }
    assert.match(docker(['port', `${project}-app-1`, '8080/tcp']), /^127\.0\.0\.1:18080$/);

    await check('anonymous health is UP', async () => {
        assert.equal((await request('/actuator/health')).data.status, 'UP');
    });
    await check('public product list is readable', () => request('/product/list'));
    await check('known default administrator does not exist', async () => {
        assert.equal(sql("SELECT COUNT(*) FROM member WHERE email='admin@test.com';"), '0');
    });

    const email = `smoke-${randomUUID()}@example.test`;
    const password = randomUUID();
    await check('signup rejects client-supplied ADMIN privilege', async () => {
        await request('/member/create', { method: 'POST', status: 201,
            body: { name: 'container-smoke', email, password, role: 'ADMIN',
                address: { city: 'test', street: 'test', zipcode: '00000' } } });
        assert.equal(sql(`SELECT role FROM member WHERE email='${email}';`), 'USER');
    });
    let token;
    let refreshToken;
    await check('login persists refresh token in Redis', async () => {
        const login = (await request('/doLogin', { method: 'POST', body: { email, password } })).data.result;
        token = login.token;
        refreshToken = login.refreshToken;
        assert.equal(JSON.parse(Buffer.from(token.split('.')[1], 'base64url')).role, 'USER');
        const stored = docker(['exec', `${project}-redis-1`, 'redis-cli', '-n', '1', 'GET', email]);
        assert.ok(JSON.parse(stored) === refreshToken, 'Redis refresh token mismatch');
    });
    await check('authenticated member API succeeds', () => request('/member/myInfo', { token }));
    await check('ordinary member cannot list all members', () => request('/member/list', { token, status: 403 }));
    await check('valid refresh token issues access token', async () => {
        assert.ok((await request('/refresh-token', { method: 'POST', body: { refreshToken } })).data.result.token);
    });
    await check('invalid refresh token returns 401', () => request('/refresh-token', {
        method: 'POST', body: { refreshToken: 'invalid' }, status: 401
    }));
    await check('Prometheus remains protected', () => request('/actuator/prometheus', { status: 403 }));
    await check('allowed origin passes CORS preflight', async () => {
        const response = await request('/member/myInfo', { method: 'OPTIONS', headers: {
            Origin: 'http://localhost:8081', 'Access-Control-Request-Method': 'GET',
            'Access-Control-Request-Headers': 'authorization'
        } });
        assert.equal(response.headers.get('access-control-allow-origin'), 'http://localhost:8081');
    });
    await check('SSE subscription receives connect event', async () => {
        const abort = new AbortController();
        const timer = setTimeout(() => abort.abort(), 10000);
        try {
            const response = await fetch(base + '/subscribe', {
                headers: { Authorization: `Bearer ${token}` }, signal: abort.signal
            });
            assert.equal(response.status, 200);
            const reader = response.body.getReader();
            let received = '';
            while (!received.includes('data:connected')) {
                const { value, done } = await reader.read();
                if (done) break;
                received += new TextDecoder().decode(value);
            }
            assert.match(received, /event:connect/);
            assert.match(received, /data:connected/);
            await reader.cancel();
        } finally {
            clearTimeout(timer);
            abort.abort();
        }
    });

    await check('order propagates Redis stock through RabbitMQ to MariaDB', async () => {
        // S3 업로드와 분리된 검증: 이 테스트 전용 DB에 상품 한 건만 삽입한다.
        const productId = Number(sql("INSERT INTO product (name,category,price,stock_quantity,image_path) VALUES ('container-smoke','test',1000,10,''); SELECT LAST_INSERT_ID();"));
        assert.ok(Number.isSafeInteger(productId) && productId > 0);
        const order = await request('/order/create', { method: 'POST', token, status: 201,
            body: [{ productId, productCount: 2 }] });
        assert.ok(order.data.result > 0);
        const deadline = Date.now() + 15000;
        let stock;
        do {
            stock = Number(sql(`SELECT stock_quantity FROM product WHERE id=${productId};`));
            if (stock === 8) break;
            await new Promise(resolve => setTimeout(resolve, 500));
        } while (Date.now() < deadline);
        assert.equal(stock, 8, 'RabbitMQ consumer did not update DB stock');
        assert.equal(docker(['exec', `${project}-redis-1`, 'redis-cli', '-n', '2', 'GET', String(productId)]), '8');
        const orders = (await request('/order/myorders', { token })).data.result;
        assert.ok(orders.some(saved => saved.id === order.data.result));
    });

    console.log(JSON.stringify({ checkedAt: new Date().toISOString(), project,
        passed: results.length, results, limitations: ['No AWS/TLS/S3 validation', 'No load/concurrency test', 'No SSE reconnect validation'] }, null, 2));
} catch (error) {
    console.error(`FAIL after ${results.length} checks: ${error.message}`);
    process.exitCode = 1;
}
