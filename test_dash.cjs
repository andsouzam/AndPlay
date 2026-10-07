const https = require('https');

async function testDash() {
    const mpdUrl = "https://otte.live.fly.ww.aiv-cdn.net/gru-nitro/live/clients/dash/enc/dsa3hwuhd1/out/v1/631b48c8d9ea437e8309d1a4b55acef5/cenc.mpd";
    const u = new URL(mpdUrl);
    
    https.get(mpdUrl, res => {
        let body = '';
        res.on('data', c => body += c);
        res.on('end', () => {
            console.log('MPD status:', res.statusCode, 'len:', body.length);
            // Check for segment template or media segments
            const m = body.match(/media="([^"]+)"/);
            const init = body.match(/initialization="([^"]+)"/);
            console.log('Init segment template:', init ? init[1] : 'NONE');
            console.log('Media segment template:', m ? m[1] : 'NONE');
        });
    }).on('error', console.error);
}

testDash();
