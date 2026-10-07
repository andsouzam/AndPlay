const { fetchUrl, resolveStream, testPlayback5s } = require('./test_channel_harness.cjs');

async function testTnt() {
    console.log('--- Testing RDCanais tnt ---');
    try {
        const res1 = await resolveStream('https://rdcanais.org/tnt');
        console.log('RDCanais resolve:', res1);
        if (res1) {
            const p1 = await testPlayback5s(res1);
            console.log('RDCanais playback:', p1);
        }
    } catch (e) { console.log('RDCanais err:', e.message); }

    console.log('\n--- Testing RDEmbed tnt ---');
    try {
        const res2 = await resolveStream('https://v2.rdembed.sbs/tnt');
        console.log('RDEmbed resolve:', res2);
        if (res2) {
            const p2 = await testPlayback5s(res2);
            console.log('RDEmbed playback:', p2);
        }
    } catch (e) { console.log('RDEmbed err:', e.message); }
}

testTnt();
