// Supabase Edge Function: resolve-stream
// Segue o redirecionamento (302) do servidor IPTV e devolve apenas o link final em https.
// NÃO faz proxy do vídeo (só lê os cabeçalhos), então o consumo é mínimo.
//
// Deploy pelo painel: Supabase > Edge Functions > Deploy a new function > "Via Editor",
// nome "resolve-stream", cole este código, desative "Verify JWT" e faça o Deploy.

const ALLOWED_HOSTS = ['2kbrfonte.space'];

const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
  'Access-Control-Allow-Methods': 'GET, OPTIONS'
};

function json(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...CORS, 'Content-Type': 'application/json' }
  });
}

Deno.serve(async (req) => {
  if (req.method === 'OPTIONS') return new Response('ok', { headers: CORS });

  const target = new URL(req.url).searchParams.get('u');
  let current;
  try { current = new URL(target); } catch { return json({ error: 'URL inválida' }, 400); }
  if (!ALLOWED_HOSTS.includes(current.hostname)) return json({ error: 'Host não permitido' }, 403);

  try {
    for (let hop = 0; hop < 5; hop++) {
      const resp = await fetch(current.toString(), {
        method: 'GET',
        headers: { 'User-Agent': 'Mozilla/5.0', Range: 'bytes=0-0' },
        redirect: 'manual'
      });
      const location = resp.headers.get('location');
      try { await resp.body?.cancel(); } catch (_) { /* ignora */ }
      if (resp.status >= 300 && resp.status < 400 && location) {
        current = new URL(location, current);
        continue;
      }
      break;
    }
    // O destino final aceita https; o receptor do Chromecast bloqueia http.
    if (current.protocol === 'http:') current.protocol = 'https:';
    return json({ url: current.toString() });
  } catch (e) {
    return json({ error: String(e) }, 502);
  }
});
