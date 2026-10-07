const { fetchUrl } = require('./test_channel_harness.cjs');

async function diag(slug, rdcSlug = null) {
    console.log(`\n================ DIAGNOSTIC: ${slug} ================`);
    
    // 1. Test RDCanais
    const rSlug = rdcSlug || slug;
    console.log(`[RDCanais]: https://rdcanais.org/${rSlug}`);
    try {
        const r1 = await fetchUrl(`https://rdcanais.org/${rSlug}`, { 'Referer': 'https://rdcanais.org/' });
        console.log(`  Hop 1: Status ${r1.status}, len ${r1.body.length}`);
        const m1 = r1.body.match(/<iframe\s+[^>]*src=["']([^"']+)["']/i);
        if (m1) {
            let ifr1 = m1[1].replace(/&amp;/g, '&');
            if (ifr1.startsWith('//')) ifr1 = 'https:' + ifr1;
            else if (ifr1.startsWith('/')) ifr1 = 'https://rdcanais.org' + ifr1;
            console.log(`  Hop 1 iframe: ${ifr1}`);
            const r2 = await fetchUrl(ifr1, { 'Referer': `https://rdcanais.org/${rSlug}` });
            console.log(`  Hop 2: Status ${r2.status}, len ${r2.body.length}`);
            // Check stream
            const sUrls = r2.body.match(/window\.STREAM_URLS\s*=\s*\[\s*"([^"]+)"/);
            const mpd = r2.body.match(/["']([^"']+\.mpd[^"']*)["']/);
            const m3u8 = r2.body.match(/["']([^"']+\.m3u8[^"']*)["']/);
            if (sUrls) console.log(`  RDCanais STREAM_URL: ${sUrls[1]}`);
            else if (mpd) console.log(`  RDCanais MPD: ${mpd[1]}`);
            else if (m3u8) console.log(`  RDCanais M3U8: ${m3u8[1]}`);
            else {
                const ifr2 = r2.body.match(/<iframe\s+[^>]*src=["']([^"']+)["']/i);
                if (ifr2) console.log(`  Hop 2 iframe: ${ifr2[1]}`);
                else console.log(`  Hop 2 body snippet: ${r2.body.slice(0, 300)}`);
            }
        }
    } catch (e) {
        console.log(`  RDCanais error: ${e.message}`);
    }

    // 2. Test RDEmbed
    console.log(`[RDEmbed]: https://v2.rdembed.sbs/${slug}`);
    try {
        const h1 = await fetchUrl(`https://v2.rdembed.sbs/${slug}`, { 'Referer': 'https://reidosembeds.online/' });
        console.log(`  Hop 1: Status ${h1.status}, len ${h1.body.length}`);
        const m1 = h1.body.match(/<iframe\s+[^>]*src=["']([^"']+)["']/i);
        if (m1) {
            let ifr1 = m1[1].replace(/&amp;/g, '&');
            const cookieHeader = h1.cookies ? h1.cookies.map(c => c.split(';')[0]).join('; ') : '';
            const h2 = await fetchUrl(ifr1, { 'Referer': `https://v2.rdembed.sbs/${slug}`, 'Cookie': cookieHeader });
            console.log(`  Hop 2: Status ${h2.status}, len ${h2.body.length}`);
            const m2 = h2.body.match(/<iframe\s+[^>]*src=["']([^"']+)["']/i);
            if (m2) {
                let ifr2 = m2[1].replace(/&amp;/g, '&');
                const u2 = new URL(ifr2);
                const h3 = await fetchUrl(ifr2, { 'Referer': ifr1 });
                console.log(`  Hop 3: Status ${h3.status}, len ${h3.body.length}`);
                const ref = h3.body.match(/"ref"\s*:\s*"([^"]+)"/);
                if (ref) {
                    const refPath = ref[1].replace(/\\\//g, '/');
                    const refUrl = refPath.startsWith('http') ? refPath : (u2.origin + (refPath.startsWith('/') ? '' : '/') + refPath);
                    const h4 = await fetchUrl(refUrl, { 'Referer': ifr2, 'Origin': u2.origin, 'Accept': 'application/json' }, 'POST');
                    console.log(`  Hop 4: Status ${h4.status}, body: ${h4.body}`);
                } else {
                    console.log(`  Hop 3 snippet: ${h3.body.slice(0, 300)}`);
                }
            }
        }
    } catch (e) {
        console.log(`  RDEmbed error: ${e.message}`);
    }
}

async function run() {
    await diag('sportv2');
    await diag('premiereclubes', 'premiere');
    await diag('globo', 'globosp');
    await diag('band', 'bandsp');
    await diag('hbo2');
    await diag('cinemax');
    await diag('amc');
    await diag('history2');
}
run();
