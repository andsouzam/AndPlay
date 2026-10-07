const fs = require('fs');

const html = fs.readFileSync('hop3.html', 'utf8');

const idx = html.indexOf('function activate');
if (idx !== -1) {
    console.log(html.slice(idx, idx + 1500));
} else {
    // Search for activate() definition
    const m = html.match(/activate\s*=\s*function/);
    if (m) console.log(html.slice(m.index, m.index + 1500));
}
