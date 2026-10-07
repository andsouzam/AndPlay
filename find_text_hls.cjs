const fs = require('fs');

const html = fs.readFileSync('hop3.html', 'utf8');

const idx = html.indexOf('function isTextHlsUrl');
if (idx !== -1) {
    console.log(html.slice(idx, idx + 2500));
}
