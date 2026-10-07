const fs = require('fs');
const { testChannel } = require('./test_channel_harness.cjs');

async function run() {
    const raw = fs.readFileSync('android/app/src/main/assets/channels.json', 'utf8').replace(/^\ufeff/, '');
    const data = JSON.parse(raw);
    const channels = data.value || data;

    console.log(`Starting systematic channel playback verification. Total channels: ${channels.length}`);
    let passed = 0;
    let failed = 0;

    for (let i = 0; i < Math.min(10, channels.length); i++) {
        const ch = channels[i];
        process.stdout.write(`[${i + 1}/${channels.length}] Testing '${ch.name}' (${ch.id})... `);
        const res = await testChannel(ch);
        if (res.ok) {
            console.log(`✅ OK via ${res.provider} -> ${res.details}`);
            passed++;
        } else {
            console.log(`❌ FAIL -> ${res.reason}`);
            failed++;
        }
    }
    console.log(`\nSummary: ${passed} passed, ${failed} failed.`);
}

run().catch(console.error);
