const fs = require('fs');

let html = fs.readFileSync('hop3.html', 'utf8');

// Transformations matching handleEmbedInterception
html = html.replace(/window\.__PLAY_AUTOPLAY_ENABLED\s*=\s*false;/g, 'window.__PLAY_AUTOPLAY_ENABLED = true;');
html = html.replace(/window\.__PLAY_VAST_ACTIVE\s*=\s*true;/g, 'window.__PLAY_VAST_ACTIVE = false;');

console.log('Autoplay line:', html.match(/window\.__PLAY_AUTOPLAY_ENABLED\s*=\s*[^;]+;/));
console.log('VAST line:', html.match(/window\.__PLAY_VAST_ACTIVE\s*=\s*[^;]+;/));
