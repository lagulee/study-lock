// Durable Object: 연동 묶음 하나(같은 코드를 넣은 기기들). 상태는 state.js 순수 함수로 바꾸고,
// 여기서는 저장소·알람·WebSocket(잠자기 API)만 연결한다. cloudflare:workers 를 import 하지 않아 Node 테스트에서 가짜 ctx로 만든다.
import { apply, initial } from './state.js';

const KEY = 'state';
const json = (obj, status = 200) => new Response(JSON.stringify(obj), {
  status, headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' },
});

export class Group {
  // env.CLOCK: 테스트용 가짜 시계 { now() }
  constructor(ctx, env = {}) {
    this.ctx = ctx;
    this.clock = env.CLOCK ?? Date;
    this.state = null;
    this.created = false;
    ctx.blockConcurrencyWhile(async () => {
      this.created = (await ctx.storage.get('created')) === true;
      this.state = (await ctx.storage.get(KEY)) ?? initial();
    });
    // 기기가 30초마다 보내는 "ping"에는 깨어나지 않고 런타임이 바로 "pong"으로 답한다(무료 한도 절약)
    try {
      ctx.setWebSocketAutoResponse?.(new WebSocketRequestResponsePair('ping', 'pong'));
    } catch { /* Node 테스트 환경 */ }
  }

  now() { return this.clock.now(); }

  /** 이 묶음에 지금 붙어 있는 기기 이름들 */
  devices() {
    return this.ctx.getWebSockets().map((ws) => ws.deserializeAttachment()?.name ?? '?');
  }

  message() {
    return { type: 'state', state: this.state, devices: this.devices(), now: this.now() };
  }

  broadcast() {
    const text = JSON.stringify(this.message());
    for (const ws of this.ctx.getWebSockets()) {
      try { ws.send(text); } catch { /* 닫히는 중 */ }
    }
  }

  /** 동작 하나를 적용하고, 바뀌었으면 저장·알람·방송한다. */
  async run(op) {
    const r = apply(this.state, op, this.now());
    if (r.changed) {
      this.state = r.state;
      await this.ctx.storage.put(KEY, this.state);
      if (this.state.active && this.state.waitUntil) await this.ctx.storage.setAlarm(this.state.waitUntil);
      else await this.ctx.storage.deleteAlarm();
      this.broadcast();
    }
    return r;
  }

  async fetch(request) {
    const url = new URL(request.url);
    const action = url.pathname.split('/').pop();
    if (action === 'init') {
      this.created = true;
      await this.ctx.storage.put('created', true);
      await this.ctx.storage.put(KEY, this.state);
      return json({ ok: true });
    }
    if (!this.created) return json({ error: 'noGroup' }, 404);
    // 대기 시간이 이미 지났으면 먼저 정리한다(알람이 늦을 때 대비)
    await this.run({ type: 'tick' });

    if (action === 'ws') {
      if (request.headers.get('Upgrade') !== 'websocket') return json({ error: 'upgrade' }, 426);
      const name = (url.searchParams.get('name') ?? '').trim().slice(0, 40) || '이름 없는 기기';
      const pair = new WebSocketPair();
      const [client, server] = Object.values(pair);
      this.ctx.acceptWebSocket(server);
      server.serializeAttachment({ name });
      this.broadcast(); // 새 기기를 포함한 기기 목록을 모두에게
      return new Response(null, { status: 101, webSocket: client });
    }
    if (action === 'state' && request.method === 'GET') return json(this.message());
    if (action === 'op' && request.method === 'POST') {
      let op;
      try { op = await request.json(); } catch { return json({ error: 'badJson' }, 400); }
      const r = await this.run(op);
      return json({ ...this.message(), error: r.error });
    }
    return json({ error: 'notFound' }, 404);
  }

  async webSocketMessage(ws, data) {
    if (data === 'ping') { ws.send('pong'); return; }
    let op;
    try { op = JSON.parse(data); } catch { return; }
    if (op?.type === 'hello') { ws.send(JSON.stringify(this.message())); return; }
    const r = await this.run(op);
    if (r.error) ws.send(JSON.stringify({ type: 'error', error: r.error }));
  }

  async webSocketClose(ws) {
    try { ws.close(); } catch { /* 이미 닫힘 */ }
    // 닫힌 소켓은 목록에서 빠지기 전일 수 있어 빼고 보낸다
    const text = JSON.stringify({ ...this.message(), devices: this.ctx.getWebSockets().filter((s) => s !== ws).map((s) => s.deserializeAttachment()?.name ?? '?') });
    for (const s of this.ctx.getWebSockets()) if (s !== ws) { try { s.send(text); } catch { /* 무시 */ } }
  }

  async webSocketError(ws) { return this.webSocketClose(ws); }

  async alarm() {
    await this.run({ type: 'tick' });
  }
}
