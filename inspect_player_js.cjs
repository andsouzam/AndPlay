const https = require('https');

async function inspectPlayerJs() {
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
    
    // Find all occurrences of fetch, XMLHttpRequest, pLoader, fLoader, customLoader, load, .src
    const html = h3.body;
    console.log('HTML len:', html.length);
    
    // Look for where hls is instantiated
    const hlsMatches = [...html.matchAll(/Hls\s*\([^)]*\)/gi)];
    console.log('Hls instantiations:', hlsMatches.map(m => m[0]));

    // Search for keywords
    const keywords = ['loader', 'xhr', 'fetch', 'proxy', 'transform', 'decrypt', 'woff', 'm3u8', 'base64', 'Worker'];
    for (const kw of keywords) {
        const count = (html.match(new RegExp(kw, 'gi')) || []).length;
        console.log(`Keyword '${kw}': ${count} occurrences`);
    }

    // Print script blocks
    const scripts = html.match(/<script[\s\S]*?<\/script>/gi) || [];
    for (let i = 0; i < scripts.length; i++) {
        console.log(`Script ${i} length: ${scripts[i].length}`);
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

inspectPlayerJs().catch(e => console.error(e));
