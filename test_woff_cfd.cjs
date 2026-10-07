const https = require('https');

async function testWoffCfd() {
    // 1. Hop 1
    const h1 = await request('104.21.28.94', 'v2.rdembed.sbs', '/history2', { 'Referer': 'https://reidosembeds.online/' });
    const iframe1 = h1.body.match(/<iframe[^>]+src=["']([^"']+)["']/i)[1].replace(/&amp;/g, '&');
    const u1 = new URL(iframe1);
    const cookieHeader = h1.cookies ? h1.cookies.map(c => c.split(';')[0]).join('; ') : '';
    
    // 2. Hop 2
    const h2 = await request('104.21.28.94', 'v2.rdembed.sbs', u1.pathname + u1.search, {
        'Referer': 'https://v2.rdembed.sbs/history2',
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

    // 5. Fetch Manifest (__index.txt)
    const uStream = new URL(streamData.src);
    const manifest = await request('104.21.81.203', uStream.hostname, uStream.pathname + uStream.search, {
        'Referer': iframe2,
        'Origin': u2.origin
    });
    console.log('Manifest status:', manifest.status);

    const lines = manifest.body.split('\n');
    const woffs = lines.filter(l => l.trim().endsWith('.woff'));
    console.log('Woff count:', woffs.length);
    if (woffs.length > 0) {
        const wUrl = woffs[0].trim();
        console.log('Testing woff URL:', wUrl);
        const uW = new URL(wUrl);

        // Try both Cloudflare IPs for .cfd: 104.21.33.37, 172.67.158.131
        for (const ip of ['104.21.33.37', '172.67.158.131']) {
            console.log('\nTrying IP:', ip);
            const wRes = await request(ip, uW.hostname, uW.pathname, {
                'Referer': iframe2,
                'Origin': u2.origin,
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
            });
            console.log('Status:', wRes.status, 'Len:', wRes.rawBuf.length);
            if (wRes.status === 200) {
                console.log('SUCCESS! First 10 bytes hex:', wRes.rawBuf.slice(0, 10).toString('hex'));
                console.log('Sync byte at 0:', wRes.rawBuf[0] === 0x47, 'at 188:', wRes.rawBuf[188] === 0x47);
            } else {
                console.log('Body:', wRes.body.slice(0, 300));
            }
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

testWoffCfd().catch(e => console.error(e));
