const { fetchUrl } = require('./test_channel_harness.cjs');

async function testFresh() {
    // 1. Hop 1
    const h1 = await fetchUrl('https://v2.rdembed.sbs/history2', { 'Referer': 'https://reidosembeds.online/' });
    const m1 = h1.body.match(/<iframe[^>]+src=["']([^"']+)["']/i);
    const u1 = m1[1].replace(/&amp;/g, '&');
    
    // 2. Hop 2
    const h2 = await fetchUrl(u1, { 'Referer': 'https://v2.rdembed.sbs/history2' });
    const m2 = h2.body.match(/<iframe[^>]+src=["']([^"']+)["']/i);
    const u2 = m2[1].replace(/&amp;/g, '&');
    
    // 3. Hop 3
    const h3 = await fetchUrl(u2, { 'Referer': u1 });
    const refMatch = h3.body.match(/"ref"\s*:\s*"([^"]+)"/);
    if (!refMatch) return console.log('No ref match in Hop 3');
    const refUrl = new URL(refMatch[1].replace(/\\\//g, '/'), u2).toString();
    
    // 4. POST ref
    const h4 = await fetchUrl(refUrl, { 'Referer': u2, 'Origin': new URL(u2).origin, 'Accept': 'application/json' }, 'POST');
    const data = JSON.parse(h4.body);
    console.log('Manifest URL:', data.src);
    
    // 5. Fetch manifest
    const man = await fetchUrl(data.src, { 'Referer': u2, 'Origin': new URL(u2).origin });
    console.log('Manifest status:', man.status);
    const segs = man.body.split('\n').filter(l => l.trim() && !l.startsWith('#'));
    console.log('Segs found:', segs.length);
    if (segs.length > 0) {
        const lastSeg = segs[segs.length - 1].trim();
        console.log('Downloading newest segment:', lastSeg);
        const t0 = Date.now();
        const sRes = await fetchUrl(lastSeg, { 'Referer': u2, 'Origin': new URL(u2).origin });
        console.log('Seg status:', sRes.status, 'len:', sRes.rawBuf ? sRes.rawBuf.length : 0, 'time:', Date.now() - t0, 'ms');
        if (sRes.status === 200 && sRes.rawBuf) {
            console.log('Sync byte 0x47:', sRes.rawBuf[0] === 0x47);
        } else {
            console.log('Error body:', sRes.body ? sRes.body.slice(0, 300) : '');
        }
    }
}
testFresh().catch(console.error);
