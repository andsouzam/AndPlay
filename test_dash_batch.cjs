const { testChannel } = require('./test_channel_harness.cjs');

async function test() {
    for (const id of ['sportv2', 'premiereclubes', 'premiere2', 'premiere3', 'premiere4']) {
        const res = await testChannel({ id, embed: 'https://v2.rdembed.sbs/' + id });
        console.log(`[${id}]:`, res);
    }
}
test();
