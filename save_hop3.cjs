const https = require('https');
const fs = require('fs');

async function saveHop3() {
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
    fs.writeFileSync('hop3.html', h3.body);
    console.log('Saved hop3.html, length:', h3.body.length);
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

saveHop3().catch(e => console.error(e));
