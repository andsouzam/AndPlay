const https = require('https');

const segUrl = "https://xn---4822--um-m3b-224laag1ai4jtlka1cb78a91aka7b6r.ourlawyermadeuschangethenameofthissongsowewouldntgetsued.lat/docs/estelar42/pspofQFq_e.woff";
const u = new URL(segUrl);

const req = https.request({
    host: '104.21.88.182',
    servername: u.hostname,
    path: u.pathname,
    headers: {
        'Host': u.hostname,
        'User-Agent': 'Mozilla/5.0'
    }
}, res => {
    console.log('Status with CORRECT IP:', res.statusCode);
    const chunks = [];
    res.on('data', c => chunks.push(c));
    res.on('end', () => {
        const buf = Buffer.concat(chunks);
        console.log('Size:', buf.length, 'first byte 0x47:', buf[0] === 0x47);
    });
});
req.on('error', console.error);
req.end();
