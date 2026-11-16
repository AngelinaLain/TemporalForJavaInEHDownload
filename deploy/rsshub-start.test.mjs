import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { createServer } from 'node:http';
import test from 'node:test';
import { buildRssHubEnvironment } from './rsshub-start.mjs';

const base = { PROXY_HOST: 'proxy.example', PROXY_PORT: '7893', PROXY_PROTOCOL: 'http' };
const credentials = (environment) => {
    const url = new URL(environment.PROXY_URI);
    return [decodeURIComponent(url.username), decodeURIComponent(url.password)];
};

test('direct mode discards otherwise unused proxy defaults and credentials', () => {
    const output = buildRssHubEnvironment({ PROXY_PORT: '7893', PROXY_PROTOCOL: 'http', PROXY_AUTH: 'bad', NODE_ENV: 'production' });
    assert.deepEqual(output, { NODE_ENV: 'production' });
});

test('unauthenticated proxy and unrelated environment are preserved', () => {
    const source = { ...base, EH_SK: 'fixture', CACHE_TYPE: 'redis' };
    const output = buildRssHubEnvironment(source);
    assert.equal(output.PROXY_URI, 'http://proxy.example:7893');
    assert.equal(output.EH_SK, 'fixture');
    assert.equal(output.CACHE_TYPE, 'redis');
    assert.equal(source.PROXY_HOST, 'proxy.example');
    for (const key of Object.keys(base)) assert.equal(output[key], undefined);
});

for (const protocol of ['http', 'https', 'socks', 'socks5', 'socks5h']) {
    test(`${protocol} preserves raw credentials containing reserved characters and Unicode`, () => {
        const username = 'user@% /中文';
        const password = 'pa:ss@/% #?+中文';
        const output = buildRssHubEnvironment({ ...base, PROXY_PROTOCOL: protocol, PROXY_USERNAME: username, PROXY_PASSWORD: password });
        assert.deepEqual(credentials(output), [username, password]);
        assert.equal(new URL(output.PROXY_URI).protocol, `${protocol}:`);
        assert.equal(new URL(output.PROXY_URI).hostname, 'proxy.example');
        assert.equal(output.PROXY_AUTH, undefined);
        assert.equal(output.PROXY_USERNAME, undefined);
        assert.equal(output.PROXY_PASSWORD, undefined);
    });
}

test('explicit encoded URI completely overrides host and inherited credentials', () => {
    const uri = 'socks5://custom%40name:p%25%3Ass@other.example:1080';
    const output = buildRssHubEnvironment({ ...base, PROXY_URI: uri, PROXY_AUTH: 'bad', PROXY_USERNAME: 'inherited', PROXY_PASSWORD: 'unused' });
    assert.equal(output.PROXY_URI, uri);
    assert.deepEqual(credentials(output), ['custom@name', 'p%:ss']);
    assert.equal(output.PROXY_AUTH, undefined);
});

test('explicit URI without credentials does not inherit a different proxy account', () => {
    const output = buildRssHubEnvironment({ ...base, PROXY_URI: 'http://other.example:8080/', PROXY_AUTH: 'explicit:ignored', PROXY_USERNAME: 'inherited', PROXY_PASSWORD: 'unused' });
    assert.equal(output.PROXY_URI, 'http://other.example:8080');
    assert.deepEqual(credentials(output), ['', '']);
});

test('raw auth override splits at the first colon and overrides inherited credentials', () => {
    const output = buildRssHubEnvironment({ ...base, PROXY_AUTH: 'custom:pa:ss@word', PROXY_USERNAME: 'inherited', PROXY_PASSWORD: 'unused' });
    assert.deepEqual(credentials(output), ['custom', 'pa:ss@word']);
});

test('native standard Base64 auth is supported for HTTP and SOCKS', () => {
    for (const protocol of ['http', 'socks5']) {
        const output = buildRssHubEnvironment({ ...base, PROXY_PROTOCOL: protocol, PROXY_AUTH: 'dXNlcjpwYXNz' });
        assert.deepEqual(credentials(output), ['user', 'pass']);
    }
    assert.deepEqual(credentials(buildRssHubEnvironment({ ...base, PROXY_AUTH: 'dTpwYQ==' })), ['u', 'pa']);
    assert.deepEqual(credentials(buildRssHubEnvironment({ ...base, PROXY_AUTH: 'dTpwYQ' })), ['u', 'pa']);
});

test('empty passwords and percent sequences remain literal credentials', () => {
    assert.deepEqual(credentials(buildRssHubEnvironment({ ...base, PROXY_USERNAME: 'user', PROXY_PASSWORD: '' })), ['user', '']);
    assert.deepEqual(credentials(buildRssHubEnvironment({ ...base, PROXY_AUTH: 'user:' })), ['user', '']);
    assert.deepEqual(credentials(buildRssHubEnvironment({ ...base, PROXY_PASSWORD: 'only-password' })), ['', 'only-password']);
    assert.deepEqual(credentials(buildRssHubEnvironment({ ...base, PROXY_AUTH: 'user:p%40ss' })), ['user', 'p%40ss']);
});

test('invalid auth fails without including supplied credentials', () => {
    for (const value of ['not-base64!', 'Basic dXNlcjpwYXNz', 'dXNlcg==', '/zp4', 'dTpwYQ=']) {
        assert.throws(() => buildRssHubEnvironment({ ...base, PROXY_AUTH: value }), (error) => {
            assert.match(error.message, /RSSHub proxy auth/);
            assert.equal(error.message.includes(value), false);
            return true;
        });
    }
});

test('IPv6 and host URLs keep the intended protocol and port', () => {
    assert.equal(buildRssHubEnvironment({ ...base, PROXY_HOST: '::1' }).PROXY_URI, 'http://[::1]:7893');
    assert.equal(buildRssHubEnvironment({ ...base, PROXY_HOST: 'socks5://proxy.example:1080' }).PROXY_URI, 'socks5://proxy.example:1080');
});

test('invalid proxy addresses and ports fail without exposing input', () => {
    for (const uri of ['http://fixture-user:fixture-secret@', 'ftp://proxy.example', 'http://proxy.example/path', 'http://proxy.example?secret=value']) {
        assert.throws(() => buildRssHubEnvironment({ PROXY_URI: uri }), (error) => {
            assert.equal(error.message.includes(uri), false);
            assert.equal(error.message.includes('fixture-secret'), false);
            return true;
        });
    }
    for (const port of ['0', '65536', 'abc']) {
        assert.throws(() => buildRssHubEnvironment({ ...base, PROXY_PORT: port }), /port must be between/);
    }
});

test('real CONNECT request sends Basic authentication derived from encoded URI', { timeout: 15000 }, async (t) => {
    const proxy = createServer();
    let resolveHandshake;
    const handshake = new Promise((resolve) => { resolveHandshake = resolve; });
    proxy.on('connect', (request, socket) => {
        resolveHandshake({ target: request.url, authorization: request.headers['proxy-authorization'] });
        // No upstream connection is made; inspecting CONNECT alone verifies proxy authentication.
        socket.end('HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n');
    });
    proxy.listen(0, '127.0.0.1');
    await once(proxy, 'listening');
    t.after(() => proxy.close());
    const username = 'local-fixture';
    const password = 'p:@/% #+中文';
    const output = buildRssHubEnvironment({ PROXY_HOST: '127.0.0.1', PROXY_PORT: String(proxy.address().port), PROXY_USERNAME: username, PROXY_PASSWORD: password });
    const child = spawn(process.execPath, ['--use-env-proxy', '-e', "fetch('https://rsshub-proxy-test.invalid/').catch(() => {});"], {
        env: { ...process.env, NODE_OPTIONS: '', HTTP_PROXY: output.PROXY_URI, HTTPS_PROXY: output.PROXY_URI, http_proxy: output.PROXY_URI, https_proxy: output.PROXY_URI, ALL_PROXY: '', all_proxy: '', NO_PROXY: '', no_proxy: '' },
        stdio: ['ignore', 'ignore', 'ignore'],
    });
    t.after(() => child.kill());
    const [request, [code]] = await Promise.all([handshake, once(child, 'exit')]);
    assert.equal(code, 0);
    assert.equal(request.target, 'rsshub-proxy-test.invalid:443');
    assert.equal(request.authorization, `Basic ${Buffer.from(`${username}:${password}`).toString('base64')}`);
});
