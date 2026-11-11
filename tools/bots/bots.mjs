// 자동매매 봇 실행기(docs/bots.md). 봇 하나가 화면을 켜 둔 사용자 한 명처럼 움직인다. 의존성 없이 Node 22만 쓴다.
//   BASE_URL=http://localhost:8088 BOT_TOKEN=... BOTS=1000 node tools/bots/bots.mjs
//
// 봇 하나가 하는 일(화면 store.server.ts와 같은 요청):
//   - 3초마다 화면 새로 고침 요청 10개 + 보고 있는 구단 토론 글
//   - 보고 있는 구단의 실시간 시세 WebSocket 하나. 평균 SWITCH_EVERY초마다 다른 구단으로 옮긴다
//   - 평균 ORDER_EVERY초마다 지정가 주문 하나: 현재가 ±3호가, 1~3주. 대기 주문이 5개를 넘으면 오래된 것부터 취소
// 같은 실행기 안의 봇끼리도 체결된다. 서버는 봇끼리의 체결을 이상거래 탐지에서 빼고, 봇을 순위에서 뺀다.
// REPORT초마다 처리량·지연·오류를 한 줄로 찍고, 끝날 때(Ctrl+C, DURATION) 전체 요약을 찍는다.

const env = (k, d) => process.env[k] ?? d;
const BASE = env('BASE_URL', 'http://localhost:8088').replace(/\/$/, '');
const TOKEN = env('BOT_TOKEN', '');
const BOTS = +env('BOTS', 100);
const RAMP = +env('RAMP_SECONDS', 60);
const POLL_MS = +env('POLL_MS', 3000);
const ORDER_EVERY = +env('ORDER_EVERY', 30);
const SWITCH_EVERY = +env('SWITCH_EVERY', 90);
const DURATION = +env('DURATION', 0);
const REPORT = +env('REPORT', 30);
const PREFIX = env('NAME_PREFIX', 'bot');

if (!TOKEN) {
  console.error('BOT_TOKEN이 없어요. 서버 .env의 BOT_TOKEN과 같은 값을 주세요');
  process.exit(1);
}

const sleep = ms => new Promise(r => setTimeout(r, ms));
const rnd = n => Math.floor(Math.random() * n);
const exp = mean => -Math.log(1 - Math.random()) * mean; // 사람 행동 간격(평균 mean)
const tick = p => p < 2000 ? 1 : p < 5000 ? 5 : p < 20000 ? 10 : p < 50000 ? 50 : 100;
let stopping = false;

// ---------- 측정 ----------

class Stat {
  constructor() { this.ms = []; this.ok = 0; this.c4 = 0; this.c5 = 0; this.net = 0; }
  add(ms, status) {
    this.ms.push(ms);
    if (status === 0) this.net++; else if (status >= 500) this.c5++; else if (status >= 400) this.c4++; else this.ok++;
  }
  merge(o) { this.ms.push(...o.ms); this.ok += o.ok; this.c4 += o.c4; this.c5 += o.c5; this.net += o.net; }
  count() { return this.ms.length; }
  pct(p) {
    if (!this.ms.length) return 0;
    const s = [...this.ms].sort((a, b) => a - b);
    return Math.round(s[Math.min(s.length - 1, Math.floor(s.length * p))]);
  }
  line(secs) {
    return `${(this.count() / secs).toFixed(1)}/s p50 ${this.pct(0.5)}ms p95 ${this.pct(0.95)}ms p99 ${this.pct(0.99)}ms` +
      ` 4xx ${this.c4} 5xx ${this.c5} 연결오류 ${this.net}`;
  }
}

const fresh = () => ({ poll: new Stat(), order: new Stat(), wsMsgs: 0 });
let window_ = fresh();
const total = fresh();
let online = 0;
let sockets = 0;
const started = Date.now();
let windowStart = started;

function report(final) {
  const now = Date.now();
  const w = final ? total : window_;
  const secs = (now - (final ? started : windowStart)) / 1000;
  const label = final ? '전체' : new Date(now).toISOString().slice(11, 19);
  console.log(`[${label}] 봇 ${online}/${BOTS} WS ${sockets} | 조회 ${w.poll.line(secs)} | 주문 ${w.order.line(secs)}` +
    ` | 시세 수신 ${(w.wsMsgs / secs).toFixed(0)}/s`);
  if (!final) {
    total.poll.merge(window_.poll);
    total.order.merge(window_.order);
    total.wsMsgs += window_.wsMsgs;
    window_ = fresh();
    windowStart = now;
  }
}

// ---------- 시장 정보(실행기 전체가 함께 쓴다) ----------

let quotes = new Map();
let tradable = [];

async function loadMarket() {
  const [teams, leagues] = await Promise.all([
    fetch(BASE + '/api/teams').then(r => r.json()),
    fetch(BASE + '/api/leagues').then(r => r.json()),
  ]);
  const hidden = new Set(leagues.hidden);
  quotes = new Map(teams.map(t => [t.id, t]));
  tradable = teams.filter(t => t.listed && !hidden.has(t.league)).map(t => t.id);
}

// 사람은 고르게 보지 않는다. 열 번 중 일곱 번은 앞쪽 20개 구단(인기 구단)을 본다
function pickTeam() {
  const hot = Math.min(20, tradable.length);
  return tradable[Math.random() < 0.7 ? rnd(hot) : rnd(tradable.length)];
}

// ---------- 봇 ----------

class Bot {
  constructor(name) {
    this.name = name;
    this.cookies = new Map();
    this.team = null;
    this.ws = null;
    this.account = null;
    this.open = [];
    this.n = 0;
  }

  async req(method, path, body, stat) {
    const headers = { cookie: [...this.cookies].map(([k, v]) => `${k}=${v}`).join('; ') };
    const xsrf = this.cookies.get('XSRF-TOKEN');
    if (xsrf) headers['x-xsrf-token'] = xsrf;
    if (body !== undefined) headers['content-type'] = 'application/json';
    const t0 = performance.now();
    let status = 0;
    let json = null;
    try {
      const res = await fetch(BASE + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
      status = res.status;
      for (const c of res.headers.getSetCookie()) {
        const [kv] = c.split(';');
        const i = kv.indexOf('=');
        this.cookies.set(kv.slice(0, i), kv.slice(i + 1));
      }
      const text = await res.text();
      if (text) try { json = JSON.parse(text); } catch { json = text; }
    } catch {
      status = 0;
    }
    stat?.add(performance.now() - t0, status);
    return { status, json };
  }

  async login() {
    this.cookies.clear();
    await this.req('GET', '/api/auth/options');
    const headers = { 'content-type': 'application/json', 'x-bot-token': TOKEN,
      'x-xsrf-token': this.cookies.get('XSRF-TOKEN') ?? '',
      cookie: [...this.cookies].map(([k, v]) => `${k}=${v}`).join('; ') };
    const res = await fetch(BASE + '/api/auth/bot-login', { method: 'POST', headers, body: JSON.stringify({ name: this.name }) });
    for (const c of res.headers.getSetCookie()) {
      const [kv] = c.split(';');
      const i = kv.indexOf('=');
      this.cookies.set(kv.slice(0, i), kv.slice(i + 1));
    }
    if (res.status !== 204) throw new Error(`${this.name} 로그인 실패: ${res.status}`);
  }

  // 화면 store.server.ts refresh()와 같은 요청
  async poll() {
    const s = window_.poll;
    const r = await Promise.all([
      this.req('GET', '/api/teams', undefined, s),
      this.req('GET', '/api/rankings', undefined, s),
      this.req('GET', '/api/matches?limit=9', undefined, s),
      this.req('GET', '/api/offerings?limit=10', undefined, s),
      this.req('GET', '/api/accounts/me', undefined, s),
      this.req('GET', '/api/accounts/me/orders', undefined, s),
      this.req('GET', '/api/accounts/me/trades?limit=50', undefined, s),
      this.req('GET', '/api/accounts/me/dividends?limit=30', undefined, s),
      this.req('GET', '/api/accounts/me/subscriptions?limit=20', undefined, s),
      this.req('GET', '/api/accounts/me/ads', undefined, s),
    ]);
    if (this.team) await this.req('GET', `/api/teams/${this.team}/posts?limit=30`, undefined, s);
    if (r[4].status === 401) return this.login(); // 서버가 다시 떠서 세션이 사라졌다
    if (Array.isArray(r[0].json)) for (const q of r[0].json) quotes.set(q.id, q);
    if (r[4].status === 200) this.account = r[4].json;
    if (Array.isArray(r[5].json)) this.open = r[5].json;
  }

  watch(teamId) {
    this.ws?.close();
    this.team = teamId;
    const ws = new WebSocket(BASE.replace(/^http/, 'ws') + `/ws/teams/${teamId}`);
    ws.onopen = () => { ws.opened = true; sockets++; };
    ws.onmessage = () => window_.wsMsgs++;
    ws.onclose = () => {
      if (ws.opened) sockets--;
      // 서버가 끊었으면 1초 뒤 다시 붙는다(화면과 같다)
      if (this.ws === ws && !stopping) setTimeout(() => this.ws === ws && this.watch(teamId), 1000);
    };
    this.ws = ws;
  }

  async order() {
    const q = quotes.get(this.team);
    if (!q || !q.listed || !this.account) return;
    if (this.open.length > 5) {
      const oldest = this.open.reduce((a, b) => (a.id < b.id ? a : b));
      await this.req('DELETE', `/api/orders/${oldest.id}`, undefined, window_.order);
      return;
    }
    const t = tick(q.lastPrice);
    const price = Math.min(q.upperLimit, Math.max(q.lowerLimit, q.lastPrice + (rnd(7) - 3) * t));
    const qty = 1 + rnd(3);
    const have = this.account.holdings.find(h => h.teamId === this.team)?.available ?? 0;
    const canBuy = this.account.availablePoints >= price * qty;
    const side = have > 0 && (!canBuy || Math.random() < 0.5) ? 'SELL' : canBuy ? 'BUY' : null;
    if (!side) return;
    await this.req('POST', '/api/orders', {
      clientOrderId: `${this.name}-${Date.now().toString(36)}-${this.n++}`,
      teamId: this.team, side, type: 'LIMIT', price, quantity: side === 'SELL' ? Math.min(qty, have) : qty,
    }, window_.order);
  }

  async run() {
    await this.login();
    online++;
    this.watch(pickTeam());
    let nextOrder = Date.now() + exp(ORDER_EVERY) * 1000;
    let nextSwitch = Date.now() + exp(SWITCH_EVERY) * 1000;
    // 처음 접속한 봇이 한꺼번에 몰리지 않게 새로 고침 시점을 흩는다
    await sleep(rnd(POLL_MS));
    while (!stopping) {
      const t0 = Date.now();
      try {
        await this.poll();
        if (Date.now() >= nextSwitch) {
          this.watch(pickTeam());
          nextSwitch = Date.now() + exp(SWITCH_EVERY) * 1000;
        }
        if (Date.now() >= nextOrder) {
          await this.order();
          nextOrder = Date.now() + exp(ORDER_EVERY) * 1000;
        }
      } catch (e) {
        console.error(e.message); // 다시 로그인하지 못했다. 다음 새로 고침 때 다시 해 본다
      }
      // 응답이 늦으면 다음 새로 고침을 겹쳐 보내지 않는다
      await sleep(Math.max(0, POLL_MS - (Date.now() - t0)));
    }
    this.ws?.close();
    online--;
  }
}

// ---------- 실행 ----------

await loadMarket();
if (!tradable.length) {
  console.error('거래할 수 있는 구단이 없어요');
  process.exit(1);
}
console.log(`${BASE}에 봇 ${BOTS}개를 ${RAMP}초에 걸쳐 띄웁니다. 거래 대상 구단 ${tradable.length}개`);
setInterval(() => loadMarket().catch(() => {}), 60_000).unref();
const reporter = setInterval(() => report(false), REPORT * 1000);

const stop = () => {
  if (stopping) return;
  stopping = true;
  clearInterval(reporter);
  report(false);
  report(true);
  setTimeout(() => process.exit(0), 500);
};
process.on('SIGINT', stop);
process.on('SIGTERM', stop);
if (DURATION > 0) setTimeout(stop, DURATION * 1000);

for (let i = 0; i < BOTS && !stopping; i++) {
  const bot = new Bot(`${PREFIX}-${String(i + 1).padStart(4, '0')}`);
  bot.run().catch(e => console.error(e.message));
  await sleep((RAMP * 1000) / BOTS);
}
