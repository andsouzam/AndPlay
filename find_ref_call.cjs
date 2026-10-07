const fs = require('fs');

const html = fs.readFileSync('hop3.html', 'utf8');

const matches = [...html.matchAll(/"ref"\s*:\s*"[^"]+"/gi)];
console.log('Ref matches:', matches.map(m => m[0]));

// Search for where this json is loaded
const idx = html.indexOf('"ref":');
if (idx !== -1) {
    console.log(html.slice(Math.max(0, idx - 200), idx + 400));
}

// Find fetch or XMLHttpRequest that requests the ref
const fetchMatches = [...html.matchAll(/(?:fetch|XMLHttpRequest|\.ajax|\.post)\s*\([^)]*\)/gi)];
console.log('Network calls:', fetchMatches.map(m => m[0]));
