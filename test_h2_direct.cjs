const https = require('https');

async function testH2Stream() {
    // 1. Fetch bolodechocolate.fit
    const res = await request('172.67.194.241', 'bolodechocolate.fit', '/play/history2.html', {
        'Referer': 'https://rdcanais.org/'
    });
    const match = res.body.match(/window\.STREAM_URLS\s*=\s*\[\s*"([^"]+)"/);
    if (!match) {
        console.log('No stream url found');
        return;
    }
    const streamUrl = match[1].replace(/\\\//g, '/');
    console.log('Stream URL:', streamUrl);

    // 2. Fetch M3U8 using Cloudflare IP
    const u = new URL(streamUrl);
    console.log('Host:', u.hostname);
    const m3u8 = await request('172.67.203.192', u.hostname, u.pathname + u.search, {
        'Referer': 'https://bolodechocolate.fit/play/history2.html',
        'Origin': 'https://bolodechocolate.fit'
    });
    console.log('M3U8 status:', m3u8.status);
    console.log('M3U8 content:\n', m3u8.body);

    // 3. Download segments to simulate 5 seconds
    const lines = m3u8.body.split('\n');
    const segs = lines.filter(l => l.trim() && !l.startsWith('#'));
    console.log('Found', segs.length, 'segments');
    for (let i = 0; i < Math.min(2, segs.length); i++) {
        let segUrl = segs[i].trim();
        if (!segUrl.startsWith('http')) {
            const basePath = u.pathname.substring(0, u.pathname.lastIndexOf('/') + 1);
            segUrl = u.origin + basePath + segUrl;
        }
        console.log(`Downloading seg ${i}:`, segUrl);
        const uSeg = new URL(segUrl);
        const segRes = await request('172.67.203.192', uSeg.hostname, uSeg.pathname + uSeg.search, {
            'Referer': 'https://bolodechocolate.fit/play/history2.html',
            'Origin': 'https://bolodechocolate.fit'
        });
        console.log(`Seg ${i} status:`, segRes.status, 'size:', segRes.rawBuf.length);
        console.log(`Seg ${i} first 4 bytes hex (47 is TS sync byte):`, segRes.rawBuf.slice(0, 4).toString('hex'));
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

testH2Stream().catch(e => console.error(e));
