// 연동 서버 진입점.
//   POST /api/group          → 새 연동 코드 발급 { code }
//   GET  /g/:code/state      → 지금 상태
//   POST /g/:code/op         → 집중 시작·대기·해제 { type, ... }
//   GET  /g/:code/ws?name=   → WebSocket: 상태가 바뀔 때마다 받기
import { makeCode, normalizeCode } from './state.js';

export { Group } from './group.js';

const json = (obj, status = 200) => new Response(JSON.stringify(obj), {
  status, headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' },
});

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    try {
      if (url.pathname === '/api/group' && request.method === 'POST') {
        const code = makeCode((n) => crypto.getRandomValues(new Uint8Array(n)));
        await env.GROUP.get(env.GROUP.idFromName(code)).fetch(new Request(`${url.origin}/g/${code}/init`, { method: 'POST' }));
        return json({ code });
      }
      const m = url.pathname.match(/^\/g\/([^/]+)\/(state|op|ws)$/);
      if (m) {
        const code = normalizeCode(m[1]);
        if (!code) return json({ error: 'badCode' }, 400);
        const stub = env.GROUP.get(env.GROUP.idFromName(code));
        return await stub.fetch(new Request(`${url.origin}/g/${code}/${m[2]}${url.search}`, request));
      }
      if (url.pathname === '/') return new Response('공부 잠금 연동 서버가 켜져 있어요.', { headers: { 'content-type': 'text/plain; charset=utf-8' } });
      return json({ error: 'notFound' }, 404);
    } catch {
      // 무료 한도 초과 등
      return json({ error: 'limit' }, 503);
    }
  },
};
