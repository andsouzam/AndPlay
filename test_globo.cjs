const { fetchUrl } = require('./test_channel_harness.cjs');
const https = require('https');

async function testGlobo() {
    const r1 = await fetchUrl('https://localhost.tattoo/globo/player.php?id=6120663&lat=-23.5505199&long=-46.6333094', {
        'Referer': 'https://rdcanais.org/globosp'
    });
    console.log('Localhost.tattoo status:', r1.status, 'len:', r1.body.length);
    const mTok = r1.body.match(/['"]token['"]\s*:\s*['"]([^'"]+)['"]/);
    console.log('Token found:', mTok ? mTok[1].slice(0, 30) + '...' : 'NONE');
    
    // Find endpoint
    const mEnd = r1.body.match(/['"](https:\/\/playback\.video\.globo\.com\/[^'"]+)['"]/);
    console.log('Endpoint:', mEnd ? mEnd[1] : 'NONE');
    if (mTok && mEnd) {
        const bodyData = JSON.stringify({
            playerUrl: 'https://globoplay.globo.com',
            token: mTok[1],
            lat: -23.5505199,
            long: -46.6333094
        });
        const postRes = await fetchUrl(mEnd[1], {
            'Referer': 'https://localhost.tattoo/',
            'Content-Type': 'application/json'
        }, 'POST', bodyData);
        console.log('Globo session status:', postRes.status, 'body:\n', postRes.body);
    }
}

testGlobo();
