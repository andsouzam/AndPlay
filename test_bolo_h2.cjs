const https = require('https');

async function testBoloHistory2() {
    console.log('Testing bolodechocolate.fit/play/history2.html');
    const res = await request('172.67.194.241', 'bolodechocolate.fit', '/play/history2.html', {
        'Referer': 'https://rdcanais.org/'
    });
    console.log('Status:', res.status, 'Len:', res.body.length);
    console.log('Snippet:\n', res.body.slice(0, 1500));
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

testBoloHistory2().catch(e => console.error(e));
