const https = require('https');

async function test(slug) {
    console.log('Testing slug:', slug);
    try {
        console.log('\n--- TEST RDEmbed: https://v2.rdembed.sbs/' + slug);
        const h1 = await request('104.21.28.94', 'v2.rdembed.sbs', '/' + slug, { 'Referer': 'https://reidosembeds.online/' });
        console.log('RDEmbed Hop 1 status:', h1.status);
        const m1 = h1.body.match(/<iframe[^>]+src=["']([^"']+)["']/i);
        if (!m1) return console.log('RDEmbed Hop 1: No iframe!');
        
        const iframe1 = m1[1].replace(/&amp;/g, '&');
        const u1 = new URL(iframe1);
        const cookieHeader = h1.cookies ? h1.cookies.map(c => c.split(';')[0]).join('; ') : '';
        const h2 = await request('104.21.28.94', 'v2.rdembed.sbs', u1.pathname + u1.search, {
            'Referer': 'https://v2.rdembed.sbs/' + slug,
            'Cookie': cookieHeader
        });
        console.log('RDEmbed Hop 2 status:', h2.status);
        const m2 = h2.body.match(/<iframe[^>]+src=["']([^"']+)["']/i);
        if (!m2) return console.log('RDEmbed Hop 2: No iframe!');

        const iframe2 = m2[1].replace(/&amp;/g, '&');
        const u2 = new URL(iframe2);
        console.log('RDEmbed Hop 3 host:', u2.hostname);
        const h3 = await request(u2.hostname, u2.hostname, u2.pathname + u2.search, { 'Referer': iframe1 });
        console.log('RDEmbed Hop 3 status:', h3.status);
        const refM = h3.body.match(/"ref"\s*:\s*"([^"]+)"/);
        if (!refM) return console.log('RDEmbed Hop 3 no ref match!');
        
        const refPath = refM[1].replace(/\\\//g, '/');
        const h4 = await post(u2.hostname, refPath, {
            'Referer': iframe2,
            'Origin': u2.origin,
            'Accept': 'application/json'
        });
        console.log('RDEmbed Hop 4 status:', h4.status, 'body:', h4.body);
        const resJson = JSON.parse(h4.body);
        if (resJson.src) {
            const uSrc = new URL(resJson.src);
            console.log('Fetching manifest:', uSrc.hostname, uSrc.pathname);
            const mRes = await request(uSrc.hostname, uSrc.hostname, uSrc.pathname + uSrc.search, {
                'Referer': iframe2,
                'Origin': u2.origin
            });
            console.log('Manifest status:', mRes.status);
            console.log('Manifest content:\n', mRes.body);

            // Try first segment
            const segMatches = mRes.body.split('\n').filter(l => l.trim() && !l.startsWith('#'));
            if (segMatches.length > 0) {
                const segUrl = segMatches[0].trim();
                console.log('First segment URL:', segUrl);
                const uSeg = new URL(segUrl);
                const segRes = await request(uSeg.hostname, uSeg.hostname, uSeg.pathname + uSeg.search, {
                    'Referer': iframe2,
                    'Origin': u2.origin
                });
                console.log('Seg status:', segRes.status, 'len:', segRes.rawBuf ? segRes.rawBuf.length : 0);
            }
        }
    } catch (e) {
        console.error('RDEmbed error:', e.message);
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

test('history2');
