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

async function testDeobfuscate() {
    const r1 = await fetchUrl('https://bolodechocolate.fit/embed/sportv2.html', { 'Referer': 'https://rdcanais.org/sportv2' });
    const decoded = decodeBolodechocolateHtml(r1.body);
    console.log('Decoded full:\n', decoded);
}

testDeobfuscate();
