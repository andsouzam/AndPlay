const { fetchUrl } = require('./test_channel_harness.cjs');

function decodeBolodechocolateHtml(html) {
    try {
        const offsetMatch = html.match(/-\s*(\d+)\s*\)/);
        if (!offsetMatch) return html;
        const offset = parseInt(offsetMatch[1]);
        const arrayMatch = html.match(/var\s+\w+\s*=\s*\[(.*?)\];/s);
        if (!arrayMatch) return html;
        const tokens = arrayMatch[1].split(',');
        let decoded = '';
        for (const token of tokens) {
            const clean = token.replace(/"/g, '').replace(/'/g, '').trim();
            if (clean) {
                const b64 = Buffer.from(clean, 'base64').toString('utf8');
                const digits = b64.replace(/\D/g, '');
                if (digits) {
                    const code = parseInt(digits) - offset;
                    decoded += String.fromCharCode(code);
                }
            }
        }
        return decoded;
    } catch (e) {
        return html;
    }
}

async function testPremiere() {
    for (const p of ['premiereclubes', 'premiere2', 'premiere3', 'premiere4']) {
        const r1 = await fetchUrl(`https://bolodechocolate.fit/embed/${p}.html`, { 'Referer': `https://rdcanais.org/${p}` });
        const dec = decodeBolodechocolateHtml(r1.body);
        const u = dec.match(/window\.url\s*=\s*"([^"]+)"/);
        const kId = dec.match(/window\.ck_id\s*=\s*"([^"]+)"/);
        const kKey = dec.match(/window\.ck_key\s*=\s*"([^"]+)"/);
        console.log(`[${p}]: Status ${r1.status}, URL: ${u ? u[1] : 'NONE'}, KeyId: ${kId ? kId[1] : 'NONE'}, Key: ${kKey ? kKey[1] : 'NONE'}`);
    }
}

testPremiere();
