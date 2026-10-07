const fs = require('fs');

const html = fs.readFileSync('hop3.html', 'utf8');

// Find getHlsRuntimeConfig
const idx = html.indexOf('getHlsRuntimeConfig');
if (idx !== -1) {
    console.log('--- FOUND getHlsRuntimeConfig ---');
    console.log(html.slice(Math.max(0, idx - 200), idx + 800));
}

// Find pLoader or fLoader
const loaderIdx = html.indexOf('pLoader');
if (loaderIdx !== -1) {
    console.log('--- FOUND pLoader ---');
    console.log(html.slice(Math.max(0, loaderIdx - 200), loaderIdx + 800));
}

// Search for any mention of URL transformations
const transformMatches = [...html.matchAll(/(?:pLoader|fLoader|loader|transformResponse|xhrSetup|beforeLoad)[^;,\n]+/gi)];
console.log('Loader properties:', transformMatches.map(m => m[0]));
