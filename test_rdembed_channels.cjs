const https = require('https');

const channelsToTest = [
    'sportv', 'sportv2', 'sportv3', 'premiereclubes', 'espn', 'espn2',
    'telecinepremium', 'telecineaction', 'warner', 'tnt', 'megapix',
    'cartoonnetwork', 'discoverychannel', 'history', 'multishow', 'globo'
];

async function testChannel(slug) {
    try {
        // Hop 1
        const h1 = await request('104.21.28.94', 'v2.rdembed.sbs', '/' + slug, { 'Referer': 'https://reidosembeds.online/' });
        if (h1.status !== 200) return { slug, success: false, reason: 'Hop 1 status ' + h1.status };
        const m1 = h1.body.match(/<iframe[^>]+src=["']([^"']+)["']/i);
        if (!m1) return { slug, success: false, reason: 'Hop 1 no iframe' };
        
        const iframe1 = m1[1].replace(/&amp;/g, '&');
        const u1 = new URL(iframe1);
        const cookieHeader = h1.cookies ? h1.cookies.map(c => c.split(';')[0]).join('; ') : '';

        // Hop 2
        const h2 = await request('104.21.28.94', 'v2.rdembed.sbs', u1.pathname + u1.search, {
            'Referer': 'https://v2.rdembed.sbs/' + slug,
            'Cookie': cookieHeader
        });
        if (h2.status !== 200) return { slug, success: false, reason: 'Hop 2 status ' + h2.status };
        const m2 = h2.body.match(/<iframe[^>]+src=["']([^"']+)["']/i);
        if (!m2) return { slug, success: false, reason: 'Hop 2 no iframe' };

        const iframe2 = m2[1].replace(/&amp;/g, '&');
        const u2 = new URL(iframe2);

        // Hop 3
        const h3 = await request(u2.hostname, u2.hostname, u2.pathname + u2.search, { 'Referer': iframe1 });
        if (h3.status !== 200) return { slug, success: false, reason: 'Hop 3 status ' + h3.status };
        
        const refMatch = h3.body.match(/"ref"\s*:\s*"([^"]+)"/);
        if (!refMatch) {
            // Check if it's dash or direct hls or blob or clappr
            const isDash = h3.body.includes('.mpd');
            const isHls = h3.body.includes('.m3u8');
            return { slug, success: false, reason: 'Hop 3 no ref (isDash=' + isDash + ', isHls=' + isHls + ')' };
        }

        const refPath = refMatch[1].replace(/\\\//g, '/');

        // Hop 4
        const h4 = await post(u2.hostname, refPath, {
            'Referer': iframe2,
            'Origin': u2.origin,
            'Accept': 'application/json'
        });
        if (h4.status !== 200) return { slug, success: false, reason: 'Hop 4 status ' + h4.status };
        const json = JSON.parse(h4.body);
        return { slug, success: true, src: json.src ? json.src.slice(0, 60) + '...' : 'empty' };
    } catch (e) {
        return { slug, success: false, reason: e.message };
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
    for (const ch of channelsToTest) {
        const res = await testChannel(ch);
        console.log(`[${ch}]: ${res.success ? 'OK -> ' + res.src : 'FAILED -> ' + res.reason}`);
    }
}

run();
