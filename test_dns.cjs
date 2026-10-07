const https = require('https');

const host = 'xn--tucruh---795-704k3a1acb7a41awad61ad91dri.ourlawyermadeuschangethenameofthissongsowewouldntgetsued.cfd';

const req = https.request({
    host: '104.21.33.37',
    servername: host,
    path: '/',
    family: 4,
    headers: {
        'Host': host,
        'User-Agent': 'Mozilla/5.0'
    }
}, res => {
    console.log('Status over IPv4:', res.statusCode);
});
req.on('error', console.error);
req.end();
