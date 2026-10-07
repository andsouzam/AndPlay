const https = require('https');

async function testChannelSegments(slug) {
    try {
        console.log(`\n================ Testing ${slug} ================`);
        const h1 = await request('104.21.28.94', 'v2.rdembed.sbs', '/' + slug, { 'Referer': 'https://reidosembeds.online/' });
        const m1 = h1.body.match(/<iframe[^>]+src=["']([^"']+)["']/i);
        if (!m1) return console.log(`${slug}: Hop 1 no iframe`);
        
        const iframe1 = m1[1].replace(/&amp;/g, '&');
        const u1 = new URL(iframe1);
        const cookieHeader = h1.cookies ? h1.cookies.map(c => c.split(';')[0]).join('; ') : '';
        const h2 = await request('104.21.28.94', 'v2.rdembed.sbs', u1.pathname + u1.search, {
            'Referer': 'https://v2.rdembed.sbs/' + slug,
            'Cookie': cookieHeader
        });
        const m2 = h2.body.match(/<iframe[^>]+src=["']([^"']+)["']/i);
        if (!m2) return console.log(`${slug}: Hop 2 no iframe`);

        const iframe2 = m2[1].replace(/&amp;/g, '&');
        const u2 = new URL(iframe2);
        const h3 = await request(u2.hostname, u2.hostname, u2.pathname + u2.search, { 'Referer': iframe1 });
        const refM = h3.body.match(/"ref"\s*:\s*"([^"]+)"/);
        if (!refM) return console.log(`${slug}: Hop 3 no ref`);

        const refPath = refM[1].replace(/\\\//g, '/');
        const h4 = await post(u2.hostname, refPath, {
            'Referer': iframe2,
            'Origin': u2.origin,
            'Accept': 'application/json'
        });
        const resJson = JSON.parse(h4.body);
        console.log(`${slug}: Stream src =`, resJson.src);
        if (!resJson.src) return;

        const uSrc = new URL(resJson.src);
        const mRes = await request(uSrc.hostname, uSrc.hostname, uSrc.pathname + uSrc.search, {
            'Referer': iframe2,
            'Origin': u2.origin
        });
        const lines = mRes.body.split('\n');
        const woffs = lines.filter(l => l.trim().endsWith('.woff'));
        console.log(`${slug}: Woff count = ${woffs.length}`);
        if (woffs.length > 0) {
            const firstWoff = woffs[0].trim();
            console.log(`${slug}: First woff =`, firstWoff);
            const uW = new URL(firstWoff);
            const wRes = await request(uW.hostname, uW.hostname, uW.pathname, {
                'Referer': iframe2,
                'Origin': u2.origin
            });
            console.log(`${slug}: Woff status = ${wRes.status}, size = ${wRes.rawBuf ? wRes.rawBuf.length : 0}`);
        }
    } catch (e) {
        console.log(`${slug} Error:`, e.message);
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

async function run() {
    await testChannelSegments('history');
    await testChannelSegments('discoverychannel');
    await testChannelSegments('warner');
    await testChannelSegments('telecinepremium');
}
run();
