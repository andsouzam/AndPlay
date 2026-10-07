const { fetchUrl } = require('./test_channel_harness.cjs');

async function testTntHeaders() {
    const streamUrl = 'https://natubone.goldorayanhoje.shop/docs/estelar42/__index.txt?token=eyJ2IjoyLCJzdCI6MSwicyI6ImVzdGVsYXI0MiIsInAiOiJyZXBvc2l0b3JhdGFjYWRhby5jeW91IiwicGF0aCI6Ii9lc3RlbGFyNDIvaW5kZXgudHh0IiwiZSI6MTc5MTM3ODk1MiwiaiI6IjE1MGRjZDk3LTc1N2ItNGYxNC1iY2FlLTcxZGEyZWE2NmVlZSIsImlhdCI6MTc5MTM3ODY1MiwicnMiOiJ0bnQiLCJuYiI6ImI4NjY2ZDMyMGIwZmRjMDIwYjcwMjMzMTQzMTNmMzA3NjkxMWM1ZDEzNDhmOWE2NzY5OTQ5M2IxNWVlZjQ4M2IiLCJuZiI6NH0.ecf70d5b2421942242e03c10c0eb634e43637b1e625b73f7b29f6b2028e6c28a';
    const iframe2 = 'https://repositoratacadao.cyou/?M5vrEJoL7NspmXUVZu93OFQ5SPtE7Gz8F0CTqKvfyxQIRJ9nd5lvpBCDyJf9JoCFzhXzPQGx9aXEhPQY0=1';
    
    // Fetch manifest
    const mRes = await fetchUrl(streamUrl, { 'Referer': iframe2, 'Origin': 'https://repositoratacadao.cyou' });
    console.log('Manifest status:', mRes.status);
    console.log('Manifest content:\n', mRes.body);

    const segUrl = mRes.body.split('\n').find(l => l.trim().endsWith('.woff')).trim();
    console.log('Seg URL:', segUrl);

    // Test different headers
    const tests = [
        { name: 'Referer = iframe2, Origin = iframe2 origin', headers: { 'Referer': iframe2, 'Origin': 'https://repositoratacadao.cyou' } },
        { name: 'Referer = manifest URL', headers: { 'Referer': streamUrl, 'Origin': 'https://repositoratacadao.cyou' } },
        { name: 'Referer = https://v2.rdembed.sbs/tnt', headers: { 'Referer': 'https://v2.rdembed.sbs/tnt', 'Origin': 'https://v2.rdembed.sbs' } },
        { name: 'No Referer, No Origin', headers: {} }
    ];

    for (const t of tests) {
        const res = await fetchUrl(segUrl, t.headers);
        console.log(`[${t.name}]: Status = ${res.status}, Len = ${res.rawBuf ? res.rawBuf.length : 0}`);
    }
}

testTntHeaders().catch(console.error);
