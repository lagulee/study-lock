import test from 'node:test';
import assert from 'node:assert/strict';
import { apply, initial, makeCode, normalizeCode, CODE_LENGTH } from '../src/state.js';

test('집중 시작 → 대기 → 시간이 지나면 풀림', () => {
  let s = initial();
  let r = apply(s, { type: 'start', task: ' 수학 24쪽 ', waitMinutes: 10, by: '폰' }, 1000);
  assert.equal(r.changed, true);
  s = r.state;
  assert.deepEqual([s.active, s.task, s.startedAt, s.waitMinutes, s.by], [true, '수학 24쪽', 1000, 10, '폰']);
  s = apply(s, { type: 'wait', by: '컴퓨터' }, 5000).state;
  assert.equal(s.waitUntil, 5000 + 600_000);
  // 대기 중에 다시 눌러도 시간이 늘거나 줄지 않음
  assert.equal(apply(s, { type: 'wait' }, 9000).changed, false);
  assert.equal(apply(s, { type: 'tick' }, 600_000).changed, false);
  s = apply(s, { type: 'tick' }, 605_000).state;
  assert.equal(s.active, false);
  assert.equal(s.ended.method, '대기 해제');
  assert.equal(s.ended.startedAt, 1000);
});

test('사진 인증으로 해제하면 모두에게 기록이 간다', () => {
  let s = apply(initial(), { type: 'start', task: '영어 단어', waitMinutes: 20, by: '탭' }, 0).state;
  const r = apply(s, { type: 'end', method: 'Gemini 인증', note: '잘했어요', by: '폰' }, 100);
  assert.equal(r.state.active, false);
  assert.deepEqual(r.state.ended, { method: 'Gemini 인증', by: '폰', note: '잘했어요', at: 100, task: '영어 단어', startedAt: 0 });
  assert.equal(r.state.rev, 2);
});

test('잘못된 동작은 상태를 바꾸지 않는다', () => {
  const s = initial();
  assert.equal(apply(s, { type: 'start', task: '   ' }, 0).error, 'noTask');
  assert.equal(apply(s, { type: 'wait' }, 0).error, 'notActive');
  assert.equal(apply(s, { type: 'end' }, 0).changed, false);
  assert.equal(apply(s, { type: 'hack' }, 0).error, 'badOp');
  assert.equal(apply(s, null, 0).error, 'badOp');
  const on = apply(s, { type: 'start', task: 'a', waitMinutes: 9999 }, 0).state;
  assert.equal(on.waitMinutes, 240);
  assert.equal(apply(on, { type: 'start', task: 'b' }, 1).error, 'already');
});

test('연동 코드 만들기·정리', () => {
  const code = makeCode((n) => Uint8Array.from({ length: n }, (_, i) => i * 7));
  assert.equal(code.length, CODE_LENGTH);
  assert.equal(normalizeCode(code.toLowerCase().replace(/(.{4})/g, '$1-')), code);
  assert.equal(normalizeCode('ABCD-EFGH-IJK0'), null); // I, 0 은 쓰지 않는 글자
  assert.equal(normalizeCode('short'), null);
});
