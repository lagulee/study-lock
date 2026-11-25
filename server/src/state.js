// 연동 상태(순수 함수). 서버의 Durable Object 와 테스트가 함께 쓴다. 시간은 인자로 받는다.

export const LIMITS = { TASK: 200, NAME: 40, METHOD: 40, NOTE: 300, WAIT_MIN: 1, WAIT_MAX: 240 };

export function initial() {
  return { rev: 0, active: false, task: '', startedAt: 0, waitMinutes: 20, waitUntil: 0, by: '', ended: null };
}

const str = (v, max) => (typeof v === 'string' ? v.trim().slice(0, max) : '');

/**
 * 기기가 보낸 동작 하나를 적용한다.
 * op: { type: 'start', task, waitMinutes, by } | { type: 'wait', by } | { type: 'end', method, note, by } | { type: 'tick' }
 * 돌려주는 값: { state, changed, error }
 */
export function apply(state, op, now) {
  const by = str(op?.by, LIMITS.NAME);
  const next = (patch) => ({ state: { ...state, ...patch, rev: state.rev + 1 }, changed: true, error: null });
  const same = (error = null) => ({ state, changed: false, error });

  switch (op?.type) {
    case 'start': {
      if (state.active) return same('already');
      const task = str(op.task, LIMITS.TASK);
      if (!task) return same('noTask');
      const w = Math.round(Number(op.waitMinutes));
      const waitMinutes = Number.isFinite(w) ? Math.min(LIMITS.WAIT_MAX, Math.max(LIMITS.WAIT_MIN, w)) : 20;
      return next({ active: true, task, startedAt: now, waitMinutes, waitUntil: 0, by, ended: null });
    }
    case 'wait': {
      if (!state.active) return same('notActive');
      if (state.waitUntil) return same();
      return next({ waitUntil: now + state.waitMinutes * 60_000, by });
    }
    case 'end': {
      if (!state.active) return same();
      return next(endPatch(state, str(op.method, LIMITS.METHOD) || '해제', by, str(op.note, LIMITS.NOTE), now));
    }
    case 'tick': {
      if (state.active && state.waitUntil && now >= state.waitUntil) {
        return next(endPatch(state, '대기 해제', '', '', now));
      }
      return same();
    }
    default:
      return same('badOp');
  }
}

function endPatch(state, method, by, note, now) {
  return {
    active: false, waitUntil: 0, by,
    ended: { method, by, note, at: now, task: state.task, startedAt: state.startedAt },
  };
}

// 연동 코드: 헷갈리는 글자(0 O 1 I L)를 뺀 31자 중 12자 (약 59비트)
export const CODE_ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
export const CODE_LENGTH = 12;

export function makeCode(randomBytes) {
  const bytes = randomBytes(CODE_LENGTH);
  let s = '';
  for (let i = 0; i < CODE_LENGTH; i++) s += CODE_ALPHABET[bytes[i] % CODE_ALPHABET.length];
  return s;
}

/** 사람이 친 코드(소문자·띄어쓰기·하이픈 포함)를 정리한다. 형식이 틀리면 null. */
export function normalizeCode(raw) {
  const s = String(raw ?? '').toUpperCase().replace(/[\s-]/g, '');
  if (s.length !== CODE_LENGTH) return null;
  for (const c of s) if (!CODE_ALPHABET.includes(c)) return null;
  return s;
}
