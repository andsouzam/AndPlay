const { testChannel } = require('./test_channel_harness.cjs');

async function test() {
    for (const id of ['espn', 'espn2', 'espn3', 'espn4', 'espn5', 'espn6', 'combate']) {
        const res = await testChannel({ id, embed: 'https://v2.rdembed.sbs/' + id });
        console.log(`[${id}]:`, res);
    }
}
test();
