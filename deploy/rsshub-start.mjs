import { spawn } from 'node:child_process';
import { isIP } from 'node:net';
import { pathToFileURL } from 'node:url';

const protocols = new Set(['http:', 'https:', 'socks:', 'socks5:', 'socks5h:']);
const splitProxyKeys = ['PROXY_HOST', 'PROXY_PORT', 'PROXY_PROTOCOL', 'PROXY_AUTH', 'PROXY_USERNAME', 'PROXY_PASSWORD'];

function proxyUrl(value) {
    let url;
    try {
        url = new URL(value.includes('://') ? value : `http://${value}`);
    } catch {
        throw new Error('RSSHub proxy address is invalid; check the configured host, port and URI.');
    }
    if (!protocols.has(url.protocol) || !url.hostname || url.search || url.hash || (url.pathname && url.pathname !== '/')) {
        throw new Error('RSSHub proxy must use HTTP(S) or SOCKS5 and contain no path, query or fragment.');
    }
    return url;
}

function authCredentials(value) {
    let text = value;
    if (!text.includes(':')) {
        // RSSHub's native PROXY_AUTH is a Base64 token; earlier app docs accepted user:password.
        // Buffer.from is permissive, so reject malformed tokens rather than silently changing credentials.
        try {
            if (!/^[A-Za-z0-9+/]+={0,2}$/.test(text)) throw new Error();
            const bytes = Buffer.from(text, 'base64');
            const canonical = bytes.toString('base64');
            if (text.includes('=') ? canonical !== text : canonical.replace(/=+$/, '') !== text) throw new Error();
            text = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
        } catch {
            throw new Error('RSSHub proxy auth must be username:password or its standard Base64 encoding.');
        }
    }
    const separator = text.indexOf(':');
    if (separator < 0) throw new Error('RSSHub proxy auth must contain a username and password separator.');
    return [text.slice(0, separator), text.slice(separator + 1)];
}

/** Build one authenticated URI for both HTTP and SOCKS without logging credentials. */
export function buildRssHubEnvironment(source) {
    const environment = { ...source };
    for (const key of splitProxyKeys) delete environment[key];
    delete environment.PROXY_URI;

    // An explicitly supplied URI is a complete override: never send inherited credentials to another host.
    if (source.PROXY_URI?.trim()) {
        environment.PROXY_URI = proxyUrl(source.PROXY_URI.trim()).href.replace(/\/$/, '');
        return environment;
    }
    if (!source.PROXY_HOST?.trim()) return environment;

    let host = source.PROXY_HOST.trim();
    if (isIP(host) === 6) host = `[${host}]`;
    const url = proxyUrl(host.includes('://') ? host : `${source.PROXY_PROTOCOL?.trim() || 'http'}://${host}`);
    if (!url.port && source.PROXY_PORT?.trim()) {
        const port = source.PROXY_PORT.trim();
        if (!/^\d+$/.test(port) || Number(port) < 1 || Number(port) > 65535) {
            throw new Error('RSSHub proxy port must be between 1 and 65535.');
        }
        url.port = port;
    }
    let credentials;
    if (source.PROXY_AUTH) {
        credentials = authCredentials(source.PROXY_AUTH);
    } else if (source.PROXY_USERNAME || source.PROXY_PASSWORD) {
        credentials = [source.PROXY_USERNAME || '', source.PROXY_PASSWORD || ''];
    }
    if (credentials) {
        // URL setters preserve literal percent escapes; encode the raw credential exactly once first.
        url.username = encodeURIComponent(credentials[0]);
        url.password = encodeURIComponent(credentials[1]);
    }
    environment.PROXY_URI = url.href.replace(/\/$/, '');
    return environment;
}

function start() {
    let environment;
    try {
        environment = buildRssHubEnvironment(process.env);
    } catch (error) {
        // All validation messages are static and contain no user-supplied values.
        console.error(error.message);
        process.exitCode = 1;
        return;
    }
    const child = spawn('npm', ['run', 'start'], { env: environment, stdio: 'inherit' });
    for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => child.kill(signal));
    child.on('error', () => {
        console.error('RSSHub startup failed; check that npm is available in the selected image.');
        process.exitCode = 1;
    });
    child.on('exit', (code, signal) => {
        process.exitCode = code ?? (signal === 'SIGINT' ? 130 : signal === 'SIGTERM' ? 143 : 1);
    });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) start();
