const https = require('https');
const http = require('http');
const fs = require('fs');
const { testDashPlayback5s } = require('./test_dash_harness.cjs');

// Static DNS mapping matching StreamDns.java
const DNS_CACHE = {
    'svd.cazetv.shop': ['172.67.135.64', '104.21.6.203'],
    'cdn1.s22-cloudfront-net.lat': ['104.21.96.54', '172.67.173.73'],
    'static.s23-cloudfront-net.lat': ['104.21.49.33', '172.67.158.120'],
    'api.reidoscanais.st': ['104.21.4.193', '172.67.154.45'],
    'rdcanais.org': ['104.21.4.193', '172.67.154.45'],
    'pescaplay.store': ['104.21.4.193', '172.67.154.45'],
    'esportesembed.net': ['172.67.162.24', '104.21.15.95'],
    'v2.rdembed.sbs': ['104.21.28.94', '172.67.145.79'],
    'reidosembeds.online': ['104.21.62.77', '172.67.221.205'],
    'comeumamao.monster': ['45.81.21.12'],
    'seraquevaiter.xyz': ['104.21.50.242', '172.67.215.2'],
    'goldorayanhoje.shop': ['172.67.154.113', '104.21.32.196'],
    'pamonha.shop': ['104.21.81.203', '172.67.164.74'],
    'ourlawyermadeuschangethenameofthissongsowewouldntgetsued.sbs': ['172.67.131.68', '104.21.10.88'],
    'ourlawyermadeuschangethenameofthissongsowewouldntgetsued.cfd': ['188.114.96.5', '188.114.97.5', '104.21.33.37', '172.67.158.131'],
    'ourlawyermadeuschangethenameofthissongsowewouldntgetsued.monster': ['172.67.131.68', '104.21.10.88'],
    'ourlawyermadeuschangethenameofthissongsowewouldntgetsued.lat': ['104.21.88.182', '172.67.151.194'],
    'rdcanais.net': ['104.21.82.94', '172.67.199.224'],
    'streamverde.net': ['104.21.28.81', '172.67.170.106'],
    'bolodechocolate.fit': ['172.67.194.241', '104.21.90.44'],
    'f8umt2oop68t.sbs': ['172.67.203.192', '104.21.14.153']
};

function resolveHost(host) {
    if (DNS_CACHE[host]) return DNS_CACHE[host][0];
    for (const [k, ips] of Object.entries(DNS_CACHE)) {
        if (host === k || host.endsWith('.' + k)) {
            return ips[0];
        }
    }
    // Generic suffix rules
    if (host.includes('ourlawyermadeuschangethenameofthissongsowewouldntgetsued')) {
        if (host.endsWith('.lat')) return '104.21.88.182';
        if (host.endsWith('.cfd')) return '188.114.96.5';
        if (host.endsWith('.sbs')) return '172.67.131.68';
        if (host.endsWith('.monster')) return '172.67.131.68';
    }
    if (host.endsWith('.cfd')) return '188.114.96.5';
    if (host.endsWith('.sbs')) return '104.21.28.94';
    if (host.endsWith('.monster')) return '104.21.28.94';
    if (host.endsWith('.shop')) return '104.21.81.203';
    if (host.endsWith('.xyz')) return '104.21.50.242';
    if (host.endsWith('.fit')) return '172.67.194.241';
    return host;
}

function fetchUrl(targetUrl, headers = {}, method = 'GET', postData = null) {
    return new Promise((resolve, reject) => {
        const u = new URL(targetUrl);
        const isHttps = u.protocol === 'https:';
        const client = isHttps ? https : http;
        const resolvedIp = resolveHost(u.hostname);

        const opt = {
            hostname: resolvedIp,
            port: u.port || (isHttps ? 443 : 80),
            path: u.pathname + u.search,
            method: method,
            headers: Object.assign({
                'Host': u.hostname,
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
                'Accept': '*/*'
            }, headers),
            timeout: 8000
        };
        if (isHttps) opt.servername = u.hostname;

        const req = client.request(opt, (res) => {
            const chunks = [];
            res.on('data', c => chunks.push(c));
            res.on('end', () => {
                const buf = Buffer.concat(chunks);
                resolve({
                    status: res.statusCode,
                    headers: res.headers,
                    cookies: res.headers['set-cookie'],
                    body: buf.toString('utf8'),
                    rawBuf: buf
                });
            });
        });
        req.on('timeout', () => { req.destroy(); reject(new Error('Timeout')); });
        req.on('error', reject);
        if (postData) req.write(postData);
        req.end();
    });
}

function decodeBolodechocolateHtml(html) {
    if (!html || (!html.includes('String.fromCharCode') && !html.includes('atob'))) {
        return html;
    }
    // Only decode if it's the specific bolodechocolate obfuscation and doesn't already contain streams
    if (html.includes('window.STREAM_URLS') || html.includes('"ref":') || html.includes('window.url')) {
        return html;
    }
    try {
        const offsetMatch = html.match(/-\s*(\d+)\s*\)/);
        if (!offsetMatch) return html;
        const offset = parseInt(offsetMatch[1]);

        const arrayMatch = html.match(/var\s+\w+\s*=\s*\[(.*?)\];/s);
        if (!arrayMatch) return html;

        const tokens = arrayMatch[1].split(',');
        let decoded = '';
        for (const token of tokens) {
            const clean = token.replace(/"/g, '').replace(/'/g, '').trim();
            if (clean) {
                const b64 = Buffer.from(clean, 'base64').toString('utf8');
                const digits = b64.replace(/\D/g, '');
                if (digits) {
                    const code = parseInt(digits) - offset;
                    decoded += String.fromCharCode(code);
                }
            }
        }
        if (decoded.includes('<html') || decoded.includes('<body') || decoded.includes('window.url') || decoded.includes('bitmovin')) {
            return decoded;
        }
        return html;
    } catch (e) {
        return html;
    }
}

// Emulate RdCanaisResolver.java step by step
async function resolveStream(initialUrl) {
    let targetUrl = initialUrl.trim();
    let parentUrl = targetUrl.includes('rdembed') ? 'https://reidosembeds.online/' : (targetUrl.includes('rdcanais.org') ? 'https://rdcanais.org/' : null);
    let cookies = '';

    for (let hop = 0; hop < 5; hop++) {
        const hHeaders = {};
        if (parentUrl) hHeaders['Referer'] = parentUrl;
        if (cookies) hHeaders['Cookie'] = cookies;

        const res = await fetchUrl(targetUrl, hHeaders);
        if (res.status !== 200 || !res.body) break;

        if (res.cookies) {
            const parts = res.cookies.map(c => c.split(';')[0].trim());
            cookies = cookies ? (cookies + '; ' + parts.join('; ')) : parts.join('; ');
        }
        const html = decodeBolodechocolateHtml(res.body);

        // Check Case 1: RDEmbed ref
        const refMatch = html.match(/"ref"\s*:\s*"([^"]+)"/);
        if (refMatch) {
            const refPath = refMatch[1].replace(/\\\//g, '/').trim();
            const u = new URL(targetUrl);
            const refUrl = refPath.startsWith('http') ? refPath : (u.origin + (refPath.startsWith('/') ? '' : '/') + refPath);
            const postRes = await fetchUrl(refUrl, {
                'Referer': targetUrl,
                'Origin': u.origin,
                'Accept': 'application/json',
                'Cookie': cookies
            }, 'POST');
            if (postRes.status === 200 && postRes.body) {
                const json = JSON.parse(postRes.body);
                if (json.src) {
                    return { type: 'HLS', url: json.src.replace(/\\\//g, '/'), headers: { 'Referer': targetUrl, 'Origin': u.origin } };
                }
            }
        }

        // Check Case 2: MPEG-DASH ClearKey
        const mpdMatch = html.match(/["']([^"']+\.mpd[^"']*)["']/);
        if (mpdMatch) {
            const ckIdMatch = html.match(/window\.ck_id\s*=\s*["']([a-fA-F0-9]+)["']/);
            const ckKeyMatch = html.match(/window\.ck_key\s*=\s*["']([a-fA-F0-9]+)["']/);
            const u = new URL(targetUrl);
            return {
                type: 'DASH',
                url: mpdMatch[1].replace(/\\\//g, '/'),
                clearKeyId: ckIdMatch ? ckIdMatch[1] : null,
                clearKey: ckKeyMatch ? ckKeyMatch[1] : null,
                headers: { 'Referer': targetUrl, 'Origin': u.origin }
            };
        }

        // Check Case 3: window.STREAM_URLS
        const streamUrlsMatch = html.match(/window\.STREAM_URLS\s*=\s*\[\s*"([^"]+)"/);
        if (streamUrlsMatch) {
            const sUrl = streamUrlsMatch[1].replace(/\\\//g, '/');
            const u = new URL(targetUrl);
            return { type: 'HLS', url: sUrl, headers: { 'Referer': targetUrl, 'Origin': u.origin } };
        }

        // Check Case 4: inline HLS (jwplayer, streamUrl)
        const inlineMatch = html.match(/(?:streamUrl\s*=\s*|file:\s*|source:\s*)["']([^"']+\.m3u8[^"']*)["']/);
        if (inlineMatch) {
            const sUrl = inlineMatch[1].replace(/\\\//g, '/');
            const u = new URL(targetUrl);
            return { type: 'HLS', url: sUrl, headers: { 'Referer': targetUrl, 'Origin': u.origin } };
        }

        // Hop to next iframe
        const iframeMatch = html.match(/<iframe\s+[^>]*src=["']([^"']+)["']/i);
        if (iframeMatch) {
            let nextSrc = iframeMatch[1].replace(/&amp;/g, '&').trim();
            if (nextSrc.startsWith('//')) nextSrc = 'https:' + nextSrc;
            else if (nextSrc.startsWith('/')) {
                const u = new URL(targetUrl);
                nextSrc = u.origin + nextSrc;
            }
            parentUrl = targetUrl;
            targetUrl = nextSrc;
        } else {
            break;
        }
    }
    return null;
}

// Test continuous playback (download manifest and consecutive segments totaling >= 5 seconds)
async function testPlayback5s(resolved) {
    if (!resolved || !resolved.url) return { ok: false, reason: 'No resolved stream URL' };
    
    if (resolved.type === 'DASH') {
        return testDashPlayback5s(resolved);
    }

    // HLS
    const mRes = await fetchUrl(resolved.url, resolved.headers);
    if (mRes.status !== 200) {
        return { ok: false, reason: `Manifest returned HTTP ${mRes.status}` };
    }

    const lines = mRes.body.split('\n');
    let totalSec = 0;
    const segsToDownload = [];
    let curDuration = 0;
    const uBase = new URL(resolved.url);

    for (let i = 0; i < lines.length; i++) {
        const l = lines[i].trim();
        if (l.startsWith('#EXTINF:')) {
            const dStr = l.replace('#EXTINF:', '').split(',')[0].trim();
            curDuration = parseFloat(dStr) || 0;
        } else if (l && !l.startsWith('#')) {
            let sUrl = l;
            if (!sUrl.startsWith('http')) {
                const basePath = uBase.pathname.substring(0, uBase.pathname.lastIndexOf('/') + 1);
                sUrl = uBase.origin + basePath + sUrl;
            }
            segsToDownload.push({ url: sUrl, duration: curDuration });
            totalSec += curDuration;
            if (totalSec >= 5.0 && segsToDownload.length >= 2) break;
        }
    }

    if (segsToDownload.length === 0) {
        return { ok: false, reason: 'No segments found in playlist' };
    }

    // Download segments consecutively
    let downloadedBytes = 0;
    for (let idx = 0; idx < segsToDownload.length; idx++) {
        const item = segsToDownload[idx];
        const segRes = await fetchUrl(item.url, resolved.headers);
        if (segRes.status !== 200 || !segRes.rawBuf || segRes.rawBuf.length < 1000) {
            return { ok: false, reason: `Segment #${idx + 1} failed: HTTP ${segRes.status}, size=${segRes.rawBuf ? segRes.rawBuf.length : 0}` };
        }
        downloadedBytes += segRes.rawBuf.length;
    }

    return { ok: true, segments: segsToDownload.length, totalSec: totalSec.toFixed(1), totalBytes: downloadedBytes };
}

// Test embed contingency when native stream is unavailable
async function testEmbedContingency(embedUrl) {
    try {
        const h1 = await fetchUrl(embedUrl, { 'Referer': 'https://reidosembeds.online/' });
        if (h1.status !== 200 || !h1.body) return { ok: false, reason: 'Hop 1 HTTP ' + h1.status };
        const m1 = h1.body.match(/<iframe[^>]+src=["']([^"']+)["']/i);
        if (!m1) return { ok: false, reason: 'No iframe in Hop 1' };
        const u1 = m1[1].replace(/&amp;/g, '&');
        const h2 = await fetchUrl(u1, { 'Referer': embedUrl });
        if (h2.status !== 200 || !h2.body) return { ok: false, reason: 'Hop 2 HTTP ' + h2.status };
        const m2 = h2.body.match(/<iframe[^>]+src=["']([^"']+)["']/i);
        if (!m2) return { ok: false, reason: 'No iframe in Hop 2' };
        const u2 = m2[1].replace(/&amp;/g, '&');
        const h3 = await fetchUrl(u2, { 'Referer': u1 });
        if (h3.status === 200 && h3.body && (h3.body.includes('ref') || h3.body.includes('player') || h3.body.includes('video') || h3.body.includes('bitmovin'))) {
            return { ok: true, details: 'Iframe Hop 3 verificado (Player Embed 200 OK)' };
        }
        return { ok: false, reason: 'Hop 3 did not contain player' };
    } catch (e) {
        return { ok: false, reason: e.message };
    }
}

// Test channel fallbacks like Channel.java
async function testChannel(channel) {
    const cleanSlug = channel.id ? channel.id.replace(/^canal\//, '').replace(/\.html$/, '') : '';
    let rdCleanSlug = cleanSlug;
    if (cleanSlug === 'premiere' || cleanSlug === 'premiereclubes') rdCleanSlug = 'premiereclubes';
    if (cleanSlug === 'universal') rdCleanSlug = 'universaltv';
    if (cleanSlug === 'warnerchannel') rdCleanSlug = 'warner';
    if (cleanSlug === 'globo') rdCleanSlug = 'globosp';
    if (cleanSlug === 'band') rdCleanSlug = 'bandsp';
    if (cleanSlug === 'record') rdCleanSlug = 'recordsp';
    if (cleanSlug === 'cultura') rdCleanSlug = 'tvcultura';
    rdCleanSlug = rdCleanSlug.replace(/-/g, '');

    const candidates = [];

    // Direct CDN streams like Channel.java
    if (cleanSlug === 'recordnews') {
        candidates.push({ name: 'HLS Direto', directStream: { type: 'HLS', url: 'https://rnw-rn-samsungtvplus.otteravision.com/rnw/rn/rnw_rn.m3u8', headers: {} } });
    } else if (cleanSlug === 'sbt') {
        candidates.push({ name: 'MaisSBT Direto', directStream: { type: 'HLS', url: 'https://aovivo.maissbt.com/indexMobile.m3u8', headers: {} } });
    } else if (cleanSlug === 'sbtnews') {
        candidates.push({ name: 'SBT News Direto', directStream: { type: 'HLS', url: 'https://sbtnews.maissbt.com/index.m3u8', headers: {} } });
    } else if (cleanSlug === 'cultura' || cleanSlug === 'tvcultura') {
        candidates.push({ name: 'Cultura Direto', directStream: { type: 'HLS', url: 'https://player-tvcultura.stream.uol.com.br/live/tvcultura.m3u8', headers: {} } });
    } else if (cleanSlug === 'tvbrasil') {
        candidates.push({ name: 'TV Brasil Direto', directStream: { type: 'HLS', url: 'https://tvbrasil-stream.ebc.com.br/index.m3u8', headers: {} } });
    } else if (cleanSlug === 'avatar') {
        candidates.push({ name: 'Avatar 1080p', directStream: { type: 'HLS', url: 'https://jmp2.uk/plu-6759eeb1bd523200083b4f29.m3u8', headers: {} } });
    } else if (cleanSlug === 'bobesponja') {
        candidates.push({ name: 'Bob Esponja 1080p', directStream: { type: 'HLS', url: 'https://jmp2.uk/plu-62545c0b002f4b0007688b61.m3u8', headers: {} } });
    }

    // Provedor 1: RDCanais
    candidates.push({ name: 'RDCanais', url: 'https://rdcanais.org/' + rdCleanSlug });
    // Provedor 2: RDEmbed
    candidates.push({ name: 'RDEmbed', url: channel.embed || ('https://v2.rdembed.sbs/' + cleanSlug) });

    for (const cand of candidates) {
        try {
            let resolved = cand.directStream;
            if (!resolved && cand.url) {
                resolved = await resolveStream(cand.url);
            }
            if (resolved) {
                const playRes = await testPlayback5s(resolved);
                if (playRes.ok) {
                    return { ok: true, provider: cand.name, details: `${playRes.totalSec}s (${playRes.segments} segs, ${(playRes.totalBytes/1024).toFixed(0)}KB)` };
                }
            }
        } catch (e) {}
    }

    // Contingência: se todos os fluxos nativos falharam, testa o player Embed (WebView)
    try {
        const embedUrl = channel.embed || ('https://v2.rdembed.sbs/' + cleanSlug);
        const embedCheck = await testEmbedContingency(embedUrl);
        if (embedCheck.ok) {
            return { ok: true, provider: 'Embed (WebView)', details: embedCheck.details };
        }
    } catch (e) {}

    return { ok: false, reason: 'All native stream fallbacks and embed failed' };
}

module.exports = { testChannel, testPlayback5s, resolveStream, fetchUrl, DNS_CACHE, decodeBolodechocolateHtml, testEmbedContingency };
