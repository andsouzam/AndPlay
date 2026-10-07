const https = require('https');

async function testRdCanais(slug) {
    try {
        console.log(`\nTesting RDCanais: https://rdcanais.org/${slug}`);
        const r1 = await request('104.21.4.193', 'rdcanais.org', '/' + slug, { 'Referer': 'https://rdcanais.org/' });
        const m1 = r1.body.match(/<iframe[^>]+src=["']([^"']+)["']/i);
        if (!m1) return console.log(`${slug}: Hop 1 no iframe! Body len: ${r1.body.length}`);
        
        let iframe1 = m1[1].replace(/&amp;/g, '&');
        if (iframe1.startsWith('//')) iframe1 = 'https:' + iframe1;
        else if (iframe1.startsWith('/')) iframe1 = 'https://rdcanais.org' + iframe1;
        const u1 = new URL(iframe1);
        console.log(`${slug}: Hop 1 iframe =`, iframe1);

        // Fetch Hop 2
        // Use Cloudflare IP for bolodechocolate.fit: 172.67.194.241 or system
        const hostIp = u1.hostname.includes('bolodechocolate') ? '172.67.194.241' : u1.hostname;
        const r2 = await request(hostIp, u1.hostname, u1.pathname + u1.search, { 'Referer': 'https://rdcanais.org/' + slug });
        console.log(`${slug}: Hop 2 status = ${r2.status}, len = ${r2.body.length}`);

        // Search for STREAM_URLS or iframe or m3u8
        const streamM = r2.body.match(/window\.STREAM_URLS\s*=\s*\[\s*"([^"]+)"/);
        if (streamM) {
            const streamUrl = streamM[1].replace(/\\\//g, '/');
            console.log(`${slug}: Found STREAM_URL =`, streamUrl);
            const uS = new URL(streamUrl);
            const sIp = uS.hostname.endsWith('.sbs') ? '172.67.203.192' : uS.hostname;
            const m3u8Res = await request(sIp, uS.hostname, uS.pathname + uS.search, {
                'Referer': iframe1,
                'Origin': u1.origin
            });
            console.log(`${slug}: M3U8 status = ${m3u8Res.status}, lines = ${m3u8Res.body.split('\n').length}`);
            console.log(m3u8Res.body.slice(0, 300));
        } else {
            const iframeM2 = r2.body.match(/<iframe[^>]+src=["']([^"']+)["']/i);
            if (iframeM2) console.log(`${slug}: Hop 2 iframe =`, iframeM2[1]);
            else console.log(`${slug}: Hop 2 snippet =`, r2.body.slice(0, 300));
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

async function run() {
    await testRdCanais('history');
    await testRdCanais('discovery');
    await testRdCanais('warner');
    await testRdCanais('combate');
}
run();
