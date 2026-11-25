import test from 'node:test';
import assert from 'node:assert/strict';
import { Group } from '../src/group.js';

// 가짜 Durable Object 환경
function fakeCtx() {
  const store = new Map();
  const sockets = [];
  let alarm = null;
  return {
    sockets,
    get alarm() { return alarm; },
    storage: {
      get: async (k) => store.get(k),
      put: async (k, v) => { store.set(k, structuredClone(v)); },
      setAlarm: async (t) => { alarm = t; },
      deleteAlarm: async () => { alarm = null; },
    },
    blockConcurrencyWhile: (fn) => fn(),
    getWebSockets: () => sockets,
  };
}
function fakeSocket(name) {
  return { sent: [], send(t) { this.sent.push(t); }, close() {}, deserializeAttachment: () => ({ name }) };
}
const req = (path, body) => new Request(`https://x/g/CODE/${path}`, body ? { method: 'POST', body: JSON.stringify(body) } : {});

test('만들지 않은 코드는 404', async () => {
  const g = new Group(fakeCtx());
  const res = await g.fetch(req('state'));
  assert.equal(res.status, 404);
});

test('한 기기의 동작이 다른 기기에 방송되고, 대기 알람으로 풀린다', async () => {
  let t = 1_000;
  const ctx = fakeCtx();
  const g = new Group(ctx, { CLOCK: { now: () => t } });
  await new Promise((r) => setTimeout(r, 0));
  await g.fetch(req('init', {}));
  const pc = fakeSocket('컴퓨터');
  const tab = fakeSocket('탭');
  ctx.sockets.push(pc, tab);

  const res = await g.fetch(req('op', { type: 'start', task: '수학', waitMinutes: 10, by: '폰' }));
  const body = await res.json();
  assert.equal(body.state.active, true);
  assert.deepEqual(body.devices, ['컴퓨터', '탭']);
  assert.equal(JSON.parse(pc.sent.at(-1)).state.task, '수학');

  // 컴퓨터가 WebSocket으로 "기다려서 풀기"
  await g.webSocketMessage(pc, JSON.stringify({ type: 'wait', by: '컴퓨터' }));
  assert.equal(ctx.alarm, 1_000 + 600_000);
  assert.equal(JSON.parse(tab.sent.at(-1)).state.waitUntil, 601_000);

  t = 601_000;
  await g.alarm();
  const last = JSON.parse(tab.sent.at(-1));
  assert.equal(last.state.active, false);
  assert.equal(last.state.ended.method, '대기 해제');
  assert.equal(ctx.alarm, null);

  // 저장소에서 되살려도 같은 상태
  const g2 = new Group(ctx, { CLOCK: { now: () => t } });
  await new Promise((r) => setTimeout(r, 0));
  assert.equal(g2.state.rev, last.state.rev);
  assert.equal((await g2.fetch(req('state'))).status, 200);
});

test('잘못된 동작은 오류만 돌려준다', async () => {
  const ctx = fakeCtx();
  const g = new Group(ctx);
  await new Promise((r) => setTimeout(r, 0));
  await g.fetch(req('init', {}));
  const ws = fakeSocket('폰');
  await g.webSocketMessage(ws, JSON.stringify({ type: 'wait' }));
  assert.equal(JSON.parse(ws.sent.at(-1)).error, 'notActive');
  await g.webSocketMessage(ws, 'not json');
  await g.webSocketMessage(ws, JSON.stringify({ type: 'hello' }));
  assert.equal(JSON.parse(ws.sent.at(-1)).type, 'state');
});
