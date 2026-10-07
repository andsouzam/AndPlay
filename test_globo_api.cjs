const https = require('https');

async function testGloboApi() {
    const payload = JSON.stringify({
        video_id: "6120663",
        token: "1089c2516f3c516d0f98f041b1afdbe8d6d4743666676775832684e7162616c4261764267655241723977516c364261683032776a2d3038356a57762d6454446355517870496e3778716e5f57635566566e316a6643483273794339424d3339566d79727854773d3d3a303a7575617876683638667264363137343134316272",
        lat: "-23.5505199",
        long: "-46.6333094"
    });

    const opt = {
        hostname: 'playback.video.globo.com',
        path: '/v5/video-session',
        method: 'POST',
        headers: {
            'Content-Type': 'application/json; charset=utf-8',
            'Content-Length': Buffer.byteLength(payload),
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
            'Referer': 'https://localhost.tattoo/'
        }
    };

    const req = https.request(opt, res => {
        let body = '';
        res.on('data', c => body += c);
        res.on('end', () => {
            console.log('Globo API Status:', res.statusCode);
            console.log('Globo API Body:\n', body);
        });
    });
    req.on('error', console.error);
    req.write(payload);
    req.end();
}

testGloboApi();
