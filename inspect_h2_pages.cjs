const https = require('https');

async function inspectHistory2Pages() {
    // 1. Hop 1
    const h1 = await request('104.21.28.94', 'v2.rdembed.sbs', '/history2', { 'Referer': 'https://reidosembeds.online/' });
    console.log('--- HOP 1 ---');
    console.log(h1.body);

    const m1 = h1.body.match(/<iframe[^>]+src=["']([^"']+)["']/i);
    const iframe1 = m1[1].replace(/&amp;/g, '&');
    const u1 = new URL(iframe1);
    const cookieHeader = h1.cookies ? h1.cookies.map(c => c.split(';')[0]).join('; ') : '';

    // 2. Hop 2
    const h2 = await request('104.21.28.94', 'v2.rdembed.sbs', u1.pathname + u1.search, {
        'Referer': 'https://v2.rdembed.sbs/history2',
        'Cookie': cookieHeader
    });
    console.log('\n--- HOP 2 ---');
    console.log(h2.body);

    const m2 = h2.body.match(/<iframe[^>]+src=["']([^"']+)["']/i);
    const iframe2 = m2[1].replace(/&amp;/g, '&');
    const u2 = new URL(iframe2);

    // 3. Hop 3
    const h3 = await request(u2.hostname, u2.hostname, u2.pathname + u2.search, { 'Referer': iframe1 });
    console.log('\n--- HOP 3 --- (length: ' + h3.body.length + ')');
    // find scripts
    const scripts = h3.body.match(/<script[\s\S]*?<\/script>/gi) || [];
    for (const sc of scripts) {
        if (!sc.includes('google') && !sc.includes('clarity')) {
            console.log('SCRIPT:', sc.slice(0, 500));
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
        https.get(opt, (res) => {
            let body = '';
            res.on('data', c => body += c);
            res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, cookies: res.headers['set-cookie'], body }));
        }).on('error', reject);
    });
}

inspectHistory2Pages().catch(e => console.error(e));
