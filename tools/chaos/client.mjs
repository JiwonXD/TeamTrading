// 장애 테스트용 주문 클라이언트. 의존성 없이 Node 22 fetch만 쓴다.
//   node client.mjs load  <base> <users> <seconds> <log>   주문을 보내며 보낸 것·응답받은 것을 log에 한 줄씩 남긴다
//   node client.mjs retry <base> <log>                      응답을 못 받은 주문을 같은 clientOrderId로 다시 보낸다
//   node client.mjs check <base> <log>                      재시작 뒤 정합성을 확인한다 (MYSQL 환경 변수로 SQL 실행 명령)
import { appendFileSync, readFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';

const [mode, base, ...rest] = process.argv.slice(2);

class Session {
  constructor(nickname) { this.nickname = nickname; this.cookies = new Map(); }
  async req(method, path, body) {
    const headers = { cookie: [...this.cookies].map(([k, v]) => `${k}=${v}`).join('; ') };
    const xsrf = this.cookies.get('XSRF-TOKEN');
    if (xsrf) headers['x-xsrf-token'] = xsrf;
    if (body !== undefined) headers['content-type'] = 'application/json';
    const res = await fetch(base + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
    for (const c of res.headers.getSetCookie()) {
      const [kv] = c.split(';');
      const i = kv.indexOf('=');
      this.cookies.set(kv.slice(0, i), kv.slice(i + 1));
    }
    const text = await res.text();
    return { status: res.status, json: text ? safeJson(text) : null };
  }
  async login() {
    await this.req('GET', '/api/auth/options');
    const r = await this.req('POST', '/api/auth/dev-login', { nickname: this.nickname });
    if (r.status !== 204) throw new Error(`login ${this.nickname}: ${r.status}`);
    return this;
  }
}

function safeJson(t) { try { return JSON.parse(t); } catch { return t; } }
const log = (file, rec) => appendFileSync(file, JSON.stringify(rec) + '\n');
const records = file => readFileSync(file, 'utf8').split('\n').filter(Boolean).map(l => JSON.parse(l));
const tick = p => p < 2000 ? 1 : p < 5000 ? 5 : p < 20000 ? 10 : p < 50000 ? 50 : 100;
const rnd = n => Math.floor(Math.random() * n);

async function load(users, seconds, file) {
  const deadline = Date.now() + seconds * 1000;
  const run = Date.now().toString(36);
  const teams = (await new Session('x').req('GET', '/api/teams')).json.slice(0, 3);
  await Promise.all(Array.from({ length: users }, async (_, u) => {
    const s = await new Session(`chaos-${run}-${u}`).login();
    let holdings = new Map();
    for (let n = 0; Date.now() < deadline; n++) {
      try {
        if (n % 5 === 0) {
          const me = (await s.req('GET', '/api/accounts/me')).json;
          holdings = new Map(me.holdings.map(h => [h.teamId, h.available]));
          // 포인트가 대기 매수에 다 묶이면 오래된 주문부터 취소한다. 취소도 장애 중에 섞이게 한다
          if (me.availablePoints < 200_000) {
            const open = (await s.req('GET', '/api/accounts/me/orders')).json;
            for (const o of open.slice(0, 5)) await s.req('DELETE', `/api/orders/${o.id}`);
          }
        }
        const t = teams[rnd(teams.length)];
        const q = (await s.req('GET', `/api/teams/${t.id}`)).json;
        const have = holdings.get(t.id) ?? 0;
        const side = have > 0 && Math.random() < 0.5 ? 'SELL' : 'BUY';
        const qty = side === 'SELL' ? Math.min(have, 1 + rnd(3)) : 1 + rnd(3);
        const price = Math.min(q.upperLimit, Math.max(q.lowerLimit, q.lastPrice + (rnd(5) - 2) * tick(q.lastPrice)));
        const body = { clientOrderId: `${s.nickname}-${n}`, teamId: t.id, side, type: 'LIMIT', price, quantity: qty };
        log(file, { type: 'sent', user: s.nickname, body });
        const r = await s.req('POST', '/api/orders', body);
        if (r.status === 201) {
          log(file, { type: 'ack', cid: body.clientOrderId, orderId: r.json.orderId, filled: r.json.filledQuantity });
          if (side === 'SELL') holdings.set(t.id, have - qty);
        } else {
          log(file, { type: r.status >= 500 ? 'error' : 'rejected', cid: body.clientOrderId, status: r.status, message: r.json?.message });
        }
      } catch {
        return; // 서버가 죽었다
      }
    }
  }));
}

async function retry(file) {
  const all = records(file);
  const answered = new Set(all.filter(r => r.type !== 'sent').map(r => r.cid));
  const pending = all.filter(r => r.type === 'sent' && !answered.has(r.body.clientOrderId));
  const sessions = new Map();
  for (const p of pending) {
    if (!sessions.has(p.user)) sessions.set(p.user, await new Session(p.user).login());
    const r = await sessions.get(p.user).req('POST', '/api/orders', p.body);
    if (r.status >= 500) throw new Error(`retry ${p.body.clientOrderId}: ${r.status}`);
    log(file, r.status === 201
      ? { type: 'ack', cid: p.body.clientOrderId, orderId: r.json.orderId, filled: r.json.filledQuantity, retried: true }
      : { type: 'rejected', cid: p.body.clientOrderId, status: r.status, retried: true });
  }
  console.log(`retried ${pending.length} unanswered orders`);
}

function sql(q) {
  const [cmd, ...args] = process.env.MYSQL.split(' ');
  return execFileSync(cmd, args, { input: q, encoding: 'utf8', stdio: ['pipe', 'pipe', 'ignore'] }).trim();
}

async function check(file) {
  const fail = [];
  const all = records(file);
  const errors = all.filter(r => r.type === 'error');
  if (errors.length) fail.push(`5xx ${errors.length}건`);
  const acks = all.filter(r => r.type === 'ack');
  // 응답받은 주문은 모두 남아 있어야 하고, 응답 때 체결된 수량 이상이 기록돼 있어야 한다
  for (let i = 0; i < acks.length; i += 500) {
    const part = acks.slice(i, i + 500);
    const rows = sql(`SELECT id, filled_quantity FROM orders WHERE id IN (${part.map(a => a.orderId).join(',')});`);
    const found = new Map(rows ? rows.split('\n').map(l => l.split('\t').map(Number)) : []);
    for (const a of part) {
      if (!found.has(a.orderId)) fail.push(`응답받은 주문 ${a.orderId}(${a.cid})가 없음`);
      else if (found.get(a.orderId) < a.filled) fail.push(`주문 ${a.orderId} 체결 ${a.filled} → ${found.get(a.orderId)}`);
    }
  }
  // 같은 clientOrderId로 다시 보내도 주문이 하나여야 한다 (응답 orderId가 같아야 한다)
  const byCid = new Map();
  for (const a of acks) {
    if (byCid.has(a.cid) && byCid.get(a.cid) !== a.orderId) fail.push(`${a.cid}가 두 주문 ${byCid.get(a.cid)}, ${a.orderId}`);
    byCid.set(a.cid, a.orderId);
  }
  const pending = Number(sql(`SELECT COUNT(*) FROM orders WHERE status = 'PENDING';`));
  if (pending) fail.push(`PENDING 주문 ${pending}건`);
  const locked = sql(`SELECT (SELECT COALESCE(SUM(locked_points), 0) FROM accounts)
    - (SELECT COALESCE(SUM(reserve_per_share * remaining), 0) FROM orders WHERE status = 'OPEN' AND side = 'BUY')
    - (SELECT COALESCE(SUM(s.deposit), 0) FROM subscriptions s JOIN offerings o ON o.id = s.offering_id WHERE o.status = 'SUBSCRIBING');`);
  if (Number(locked) !== 0) fail.push(`묶인 포인트 차이 ${locked}`);
  const admin = await new Session('chaos-admin').login();
  const rec = await admin.req('GET', '/api/surveillance/reconciliation');
  if (rec.status !== 200 || !rec.json.balanced) fail.push(`대사 ${rec.status} ${JSON.stringify(rec.json).slice(0, 500)}`);
  const retried = all.filter(r => r.retried).length;
  console.log(`acked ${acks.length}, retried ${retried}, pending ${pending}, reconciliation ${rec.json?.balanced}`);
  if (fail.length) { console.error('FAIL\n' + fail.slice(0, 20).join('\n')); process.exit(1); }
  console.log('OK');
}

if (mode === 'load') await load(Number(rest[0]), Number(rest[1]), rest[2]);
else if (mode === 'retry') await retry(rest[0]);
else if (mode === 'check') await check(rest[0]);
else if (mode === 'whoami') console.log((await (await new Session(rest[0]).login()).req('GET', '/api/accounts/me')).json.id);
else { console.error('usage: client.mjs load|retry|check|whoami ...'); process.exit(2); }
