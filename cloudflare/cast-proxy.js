// Cloudflare Worker: proxy de vídeo para Chromecast.
// O servidor IPTV responde com 302 para um endereço http:// sem CORS, o que o receptor do
// Chromecast (página https) bloqueia. Este proxy segue o redirecionamento no servidor e devolve
// o vídeo por https, com CORS e suporte a Range (seek).
//
// Uso: https://SEU-WORKER.workers.dev/?u=<URL codificada do vídeo>

const ALLOWED_HOSTS = ['2kbrfonte.space'];

const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, HEAD, OPTIONS',
  'Access-Control-Allow-Headers': 'Range, Content-Type',
  'Access-Control-Expose-Headers': 'Content-Length, Content-Range, Accept-Ranges, Content-Type'
};

export default {
  async fetch(request) {
    if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: CORS });
    if (request.method !== 'GET' && request.method !== 'HEAD') {
      return new Response('Method not allowed', { status: 405, headers: CORS });
    }

    const target = new URL(request.url).searchParams.get('u');
    let url;
    try { url = new URL(target); } catch { return new Response('URL inválida', { status: 400, headers: CORS }); }
    if (!['http:', 'https:'].includes(url.protocol) || !ALLOWED_HOSTS.includes(url.hostname)) {
      return new Response('Host não permitido', { status: 403, headers: CORS });
    }

    const headers = new Headers();
    headers.set('User-Agent', 'Mozilla/5.0');
    const range = request.headers.get('Range');
    if (range) headers.set('Range', range);

    const upstream = await fetch(url.toString(), { method: request.method, headers, redirect: 'follow' });

    const out = new Headers(upstream.headers);
    for (const [k, v] of Object.entries(CORS)) out.set(k, v);
    out.set('Cache-Control', 'no-store');
    if (!out.get('Content-Type')) out.set('Content-Type', 'video/mp4');
    return new Response(upstream.body, { status: upstream.status, headers: out });
  }
};
