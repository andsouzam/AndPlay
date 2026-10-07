const https = require('https');

async function testRewriteDomain() {
    // History 2 woff URL that returned 522 on .cfd:
    // Let's get a fresh one:
    const h1 = await request('104.21.28.94', 'v2.rdembed.sbs', '/history2', { 'Referer': 'https://reidosembeds.online/' });
    const iframe1 = h1.body.match(/<iframe[^>]+src=["']([^"']+)["']/i)[1].replace(/&amp;/g, '&');
    const u1 = new URL(iframe1);
    const cookieHeader = h1.cookies ? h1.cookies.map(c => c.split(';')[0]).join('; ') : '';
    const h2 = await request('104.21.28.94', 'v2.rdembed.sbs', u1.pathname + u1.search, {
        'Referer': 'https://v2.rdembed.sbs/history2',
        'Cookie': cookieHeader
    });
    const iframe2 = h2.body.match(/<iframe[^>]+src=["']([^"']+)["']/i)[1].replace(/&amp;/g, '&');
    const u2 = new URL(iframe2);
    const h3 = await request(u2.hostname, u2.hostname, u2.pathname + u2.search, { 'Referer': iframe1 });
    const refM = h3.body.match(/"ref"\s*:\s*"([^"]+)"/)[1].replace(/\\\//g, '/');
    const h4 = await post(u2.hostname, refM, {
        'Referer': iframe2,
        'Origin': u2.origin,
        'Accept': 'application/json'
    });
    const resJson = JSON.parse(h4.body);
    const uSrc = new URL(resJson.src);
    const mRes = await request(uSrc.hostname, uSrc.hostname, uSrc.pathname + uSrc.search, {
        'Referer': iframe2,
        'Origin': u2.origin
    });
    const lines = mRes.body.split('\n');
    const woffs = lines.filter(l => l.trim().endsWith('.woff'));
    const originalWoff = woffs[0].trim();
    console.log('Original woff URL:', originalWoff);

    const extensionsToTry = ['.cfd', '.sbs', '.monster', '.xyz', '.online'];
    for (const ext of extensionsToTry) {
        const rewritten = originalWoff.replace(/\.cfd\b/, ext);
        const u = new URL(rewritten);
        console.log(`\nTesting extension ${ext} -> Host: ${u.hostname}`);
        try {
            const res = await request(u.hostname, u.hostname, u.pathname, {
                'Referer': iframe2,
                'Origin': u2.origin
            });
            console.log(`Ext ${ext}: Status = ${res.status}, Len = ${res.rawBuf ? res.rawBuf.length : 0}`);
            if (res.status === 200 && res.rawBuf.length > 1000) {
                console.log(`>>> SUCCESS WITH ${ext}!! Sync byte 0x47:`, res.rawBuf[0] === 0x47);
            }
        } catch (e) {
            console.log(`Ext ${ext} error:`, e.message);
        }
    }
}

function request(host, servername, path, headers) {
    return new Promise((resolve, reject) => {
        const opt = {
            host,
            servername,
            path,
            headers: Object.assign({
                'Host': servername,
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
            }, headers)
        };
        const chunks = [];
        https.get(opt, (res) => {
            res.on('data', c => chunks.push(c));
            res.on('end', () => {
                const buf = Buffer.concat(chunks);
                resolve({ status: res.statusCode, headers: res.headers, cookies: res.headers['set-cookie'], body: buf.toString('utf8'), rawBuf: buf });
            });
        }).on('error', reject);
    });
}

function post(host, path, headers) {
    return new Promise((resolve, reject) => {
        const opt = {
            host,
            path,
            method: 'POST',
            headers: Object.assign({
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
                'Content-Length': '0'
            }, headers)
        };
        const req = https.request(opt, (res) => {
            let body = '';
            res.on('data', c => body += c);
            res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body }));
        });
        req.on('error', reject);
        req.end();
    });
}

testRewriteDomain().catch(e => console.error(e));
