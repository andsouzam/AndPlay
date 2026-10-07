const https = require('https');

async function testWoffHeaders() {
    // 1. Hop 1
    const h1 = await request('104.21.28.94', 'v2.rdembed.sbs', '/hbo', { 'Referer': 'https://reidosembeds.online/' });
    const iframe1 = h1.body.match(/<iframe[^>]+src=["']([^"']+)["']/i)[1].replace(/&amp;/g, '&');
    const u1 = new URL(iframe1);
    const cookieHeader = h1.cookies ? h1.cookies.map(c => c.split(';')[0]).join('; ') : '';
    
    // 2. Hop 2
    const h2 = await request('104.21.28.94', 'v2.rdembed.sbs', u1.pathname + u1.search, {
        'Referer': 'https://v2.rdembed.sbs/hbo',
        'Cookie': cookieHeader
    });
    const iframe2 = h2.body.match(/<iframe[^>]+src=["']([^"']+)["']/i)[1].replace(/&amp;/g, '&');
    const u2 = new URL(iframe2);

    // 3. Hop 3
    const h3 = await request(u2.hostname, u2.hostname, u2.pathname + u2.search, { 'Referer': iframe1 });
    const refMatch = h3.body.match(/"ref"\s*:\s*"([^"]+)"/)[1].replace(/\\\//g, '/');

    // 4. Hop 4: POST ref
    const h4 = await post(u2.hostname, refMatch, {
        'Referer': iframe2,
        'Origin': u2.origin,
        'Accept': 'application/json'
    });
    const streamData = JSON.parse(h4.body);
    console.log('Stream src:', streamData.src);

    // 5. Get manifest
    const uStream = new URL(streamData.src);
    const manifest = await request(uStream.hostname, uStream.hostname, uStream.pathname + uStream.search, {
        'Referer': iframe2,
        'Origin': u2.origin
    });
    console.log('Manifest status:', manifest.status);
    const woffMatch = manifest.body.match(/https?:\/\/[^\s\r\n]+\.woff/);
    if (!woffMatch) {
        console.log('No woff found in manifest:\n', manifest.body.slice(0, 500));
        return;
    }
    const woffUrl = woffMatch[0];
    console.log('Target WOFF URL:', woffUrl);
    const uWoff = new URL(woffUrl);

    // Test different header combinations for woff
    const tests = [
        { name: 'Full Referer + Origin', headers: { 'Referer': iframe2, 'Origin': u2.origin } },
        { name: 'Only Referer (iframe2)', headers: { 'Referer': iframe2 } },
        { name: 'Only Origin', headers: { 'Origin': u2.origin } },
        { name: 'Referer = Origin + /', headers: { 'Referer': u2.origin + '/' } },
        { name: 'Manifest URL as Referer', headers: { 'Referer': streamData.src } },
        { name: 'No Referer / No Origin', headers: {} },
        { name: 'Sec-Fetch headers (Chrome)', headers: {
            'Referer': iframe2,
            'Origin': u2.origin,
            'sec-ch-ua': '"Not_A Brand";v="8", "Chromium";v="120"',
            'sec-ch-ua-mobile': '?0',
            'sec-ch-ua-platform': '"Windows"',
            'Sec-Fetch-Site': 'cross-site',
            'Sec-Fetch-Mode': 'cors',
            'Sec-Fetch-Dest': 'empty'
        }}
    ];

    for (const t of tests) {
        const res = await request(uWoff.hostname, uWoff.hostname, uWoff.pathname, t.headers);
        console.log(`Test [${t.name}]: Status = ${res.status}, Len = ${res.rawBuf.length}`);
        console.log('Offsets 0, 188, 376, 564:', res.rawBuf[0].toString(16), res.rawBuf[188].toString(16), res.rawBuf[376].toString(16), res.rawBuf[564].toString(16));
        console.log('Hex 0-32:', res.rawBuf.slice(0, 32).toString('hex'));
        break;
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

testWoffHeaders().catch(e => console.error(e));
