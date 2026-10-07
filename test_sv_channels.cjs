const { fetchUrl, testPlayback5s } = require('./test_channel_harness.cjs');

const svChannels = [
    { slug: 'sportv2', url: 'https://svd.cazetv.shop/streamverde/sportv2.m3u8' },
    { slug: 'premiere', url: 'https://svd.cazetv.shop/streamverde/premiere.m3u8' },
    { slug: 'globo', url: 'https://svd.cazetv.shop/streamverde/globosp.m3u8' },
    { slug: 'band', url: 'https://svd.cazetv.shop/streamverde/bandsp.m3u8' },
    { slug: 'tnt', url: 'https://svd.cazetv.shop/streamverde/tnt.m3u8' },
    { slug: 'space', url: 'https://svd.cazetv.shop/streamverde/space.m3u8' },
    { slug: 'cultura', url: 'https://player-tvcultura.stream.uol.com.br/live/tvcultura.m3u8' },
    { slug: 'recordnews', url: 'https://rnw-rn-samsungtvplus.otteravision.com/rnw/rn/rnw_rn.m3u8' }
];

async function run() {
    for (const c of svChannels) {
        try {
            const playRes = await testPlayback5s({
                type: 'HLS',
                url: c.url,
                headers: { 'Referer': 'https://streamverde.net/', 'Origin': 'https://streamverde.net' }
            });
            if (playRes.ok) {
                console.log(`✅ [${c.slug}]: OK -> ${playRes.totalSec}s (${playRes.segments} segs, ${(playRes.totalBytes/1024).toFixed(0)}KB)`);
            } else {
                console.log(`❌ [${c.slug}]: FAIL -> ${playRes.reason}`);
            }
        } catch (e) {
            console.log(`❌ [${c.slug}]: ERROR -> ${e.message}`);
        }
    }
}
run();
