const https = require('https');

async function checkApiChannels() {
    const slugs = [
        'tnt', 'space', 'megapix', 'universal', 'universaltv', 'usa', 'sony', 'sonychannel',
        'amc', 'cinemax', 'hbo', 'hbo2', 'hboplus', 'hbofamily', 'hbosignature',
        'band', 'bandsp', 'bandsports', 'cultura', 'tvcultura', 'history', 'history2',
        'natgeo', 'nationalgeographic', 'cnnbrasil', 'bandnews', 'jovempannews', 'recordnews',
        'nickelodeon', 'disneychannel', 'gnt', 'viva', 'bis', 'canalbrasil'
    ];

    const opt = {
        host: '104.21.4.193',
        servername: 'api.reidoscanais.st',
        path: '/channels',
        headers: {
            'Host': 'api.reidoscanais.st',
            'User-Agent': 'Mozilla/5.0'
        }
    };

    https.get(opt, res => {
        let body = '';
        res.on('data', c => body += c);
        res.on('end', () => {
            const json = JSON.parse(body);
            console.log(`Total channels in API: ${json.data.length}`);
            for (const item of json.data) {
                const idLower = item.id.toLowerCase();
                const nameLower = item.name.toLowerCase();
                for (const s of slugs) {
                    if (idLower === s || nameLower.includes(s)) {
                        console.log(`API [${item.id}] "${item.name}": Embeds =`, item.embeds.map(e => `${e.provider} (${e.quality}): ${e.embed_url}`));
                        break;
                    }
                }
            }
        });
    }).on('error', console.error);
}

checkApiChannels();
