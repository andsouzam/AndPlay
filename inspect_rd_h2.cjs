const { fetchUrl } = require('./test_channel_harness.cjs');

async function inspect() {
    const r = await fetchUrl('https://rdcanais.org/history2');
    console.log('Status:', r.status, 'Len:', r.body.length);
    const iframes = r.body.match(/<iframe[^>]+src=["']([^"']+)["']/gi) || [];
    console.log('Iframes:', iframes);
    const scripts = r.body.match(/<script[\s\S]*?<\/script>/gi) || [];
    console.log('Scripts count:', scripts.length);
    for (const sc of scripts) {
        if (sc.includes('player') || sc.includes('source') || sc.includes('stream') || sc.includes('bolodechocolate') || sc.includes('f8umt')) {
            console.log('Script snippet:', sc.slice(0, 300));
        }
    }
}
inspect().catch(console.error);
