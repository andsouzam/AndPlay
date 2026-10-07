const fs = require('fs');
const { testChannel } = require('./test_channel_harness.cjs');

const CORE_SLUGS = [
    // Esportes
    'sportv', 'sportv2', 'sportv3', 'premiere', 'premiere-2', 'premiere-3', 'premiere-4',
    'espn', 'espn2', 'espn3', 'espn4', 'espn5', 'espn6', 'combate', 'bandsports',
    // TV Aberta
    'globosp', 'sbt', 'recordsp', 'bandsp', 'redetv', 'tvcultura',
    // Filmes / Séries
    'telecinepremium', 'telecineaction', 'telecinetouch', 'telecinepipoca', 'telecinecult', 'telecinefun',
    'hbo', 'hbo2', 'hboplus', 'hbofamily', 'hbosignature',
    'cinemax', 'amc', 'warner', 'tnt', 'tntseries', 'space', 'megapix', 'universaltv', 'usa', 'axn', 'sony',
    // Variedades / Documentários
    'history', 'history2', 'discoverychannel', 'discoveryturbo', 'discoveryworld', 'discoveryscience', 'animalplanet',
    // Notícias
    'globonews', 'cnnbrasil', 'bandnews', 'jovempannews', 'recordnews',
    // Infantil
    'cartoonnetwork', 'gloob', 'gloobinho', 'disneyplus1',
    // Variedades
    'multishow', 'gnt', 'terraviva', 'bis', 'canalbrasil'
];

async function run() {
    const raw = fs.readFileSync('android/app/src/main/assets/channels.json', 'utf8').replace(/^\ufeff/, '');
    const data = JSON.parse(raw);
    const channels = data.value || data;

    const coreChannels = CORE_SLUGS.map(s => {
        const found = channels.find(c => c.id.toLowerCase() === s.toLowerCase() || c.id.toLowerCase() === 'canal/' + s.toLowerCase());
        return found || { id: s, name: s };
    });

    console.log(`Testing ${coreChannels.length} core major channels...`);
    const results = [];

    for (let i = 0; i < coreChannels.length; i++) {
        const ch = coreChannels[i];
        process.stdout.write(`[${i + 1}/${coreChannels.length}] '${ch.name}' (${ch.id})... `);
        const res = await testChannel(ch);
        if (res.ok) {
            console.log(`✅ OK (${res.provider}: ${res.details})`);
            results.push({ slug: ch.id, ok: true, provider: res.provider });
        } else {
            console.log(`❌ FAIL (${res.reason})`);
            results.push({ slug: ch.id, ok: false, reason: res.reason });
        }
    }

    const passed = results.filter(r => r.ok).length;
    console.log(`\n================================`);
    console.log(`Result: ${passed}/${results.length} PASSED`);
    const failedList = results.filter(r => !r.ok);
    if (failedList.length > 0) {
        console.log('Failed channels:', failedList.map(f => f.slug));
    }
}

run().catch(console.error);
