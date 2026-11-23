// 자동매매 봇 실행기(docs/bots.md). 봇 하나가 화면을 켜 둔 사용자 한 명처럼 움직인다. 의존성 없이 Node 22만 쓴다.
//   BASE_URL=http://localhost:8088 BOT_TOKEN=... BOTS=1000 node tools/bots/bots.mjs
//
// 봇 하나가 하는 일(화면 store.server.ts와 같은 요청):
//   - 3초마다 화면 새로 고침 요청 하나(/api/refresh, 보고 있는 구단 토론 포함)
//   - 보고 있는 구단의 실시간 시세 WebSocket 하나. 평균 SWITCH_EVERY초마다 다른 구단으로 옮긴다
//   - 평균 ORDER_EVERY초마다 지정가 주문 하나: 1호가 근처, 1~3주가 기본. 대기 주문이 5개를 넘으면 오래된 것부터 취소
//   - 사고팔 방향은 구단의 최근 경기 성적과 봇 성향으로 정한다(추세형 60%, 역추세형 20%, 무작위형 20%).
//     살 확률은 25%~75% 안에서만 기울여 봇이 한 방향으로 몰리지 않게 한다
//   - 봇마다 성격이 다르다: 확신이 클 때 현재가에서 얼마나 쫓아가는지(0~4호가), 익절·손절 기준(평균 단가 대비 5~20%),
//     확신이 클 때 수량을 얼마나 늘리는지(0~5주), 한 구단에 넣는 상한(총자산의 5~25%),
//     늘 현금으로 남기는 몫(총자산의 30~70%). 돈을 다 써 버린 봇은 팔기만 해 사는 쪽이 사라진다
//   - 성적 말고도 오늘 등락률(이미 오른 값인지)과 호가 잔량(사려는 쪽·팔려는 쪽 어디가 많은지)을 섞어 판단하고,
//     얼마나 믿는지는 봇마다 다르다. 주문 가격은 실제 매도·매수 1호가 근처. 거래대금이 큰 구단을 더 자주 보는 정도도 봇마다 다르다
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

const fresh = () => ({ poll: new Stat(), order: new Stat(), wsMsgs: 0, loginFail: 0 });
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
    ` | 시세 수신 ${(w.wsMsgs / secs).toFixed(0)}/s | 로그인 실패 ${w.loginFail}`);
  if (!final) {
    total.poll.merge(window_.poll);
    total.order.merge(window_.order);
    total.wsMsgs += window_.wsMsgs;
    total.loginFail += window_.loginFail;
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

// ---------- 최근 경기 성적(실행기 전체가 함께 쓴다) ----------

const FORM_TTL_MS = 10 * 60_000;
const form = new Map(); // 구단 id → { score, at }

/**
 * 구단의 최근 성적 점수(-1~+1). 끝난 경기 최근 5개의 (승 - 패) / 경기 수에,
 * 24시간 안에 경기가 있으면 배당을 기대해 0.2를 더한다. 10분 동안 함께 쓴다
 */
async function teamForm(teamId) {
  const cached = form.get(teamId);
  if (cached && Date.now() - cached.at < FORM_TTL_MS) return cached.score;
  form.set(teamId, { score: cached?.score ?? 0, at: Date.now() }); // 같은 구단을 여러 봇이 한꺼번에 부르지 않게
  const res = await fetch(`${BASE}/api/teams/${teamId}/matches?limit=15`);
  if (!res.ok) return cached?.score ?? 0;
  const list = await res.json();
  const now = Date.now();
  const finals = list.filter(m => m.status === 'FINAL' && m.homeScore != null && m.awayScore != null).slice(0, 5);
  let net = 0;
  for (const m of finals) {
    const mine = m.homeTeamId === teamId ? m.homeScore : m.awayScore;
    const theirs = m.homeTeamId === teamId ? m.awayScore : m.homeScore;
    net += Math.sign(mine - theirs);
  }
  const soon = list.some(m => m.status === 'SCHEDULED' && Date.parse(m.scheduledAt) - now < 86_400_000
    && Date.parse(m.scheduledAt) > now);
  const score = Math.max(-1, Math.min(1, (finals.length ? net / finals.length : 0) + (soon ? 0.2 : 0)));
  form.set(teamId, { score, at: Date.now() });
  return score;
}

// 봇 성향: 1 추세형(잘하는 팀을 산다), -1 역추세형(잘하는 팀을 판다), 0 무작위형. 다섯 중 셋·하나·하나
const styleOf = i => [1, 1, 1, -1, 0][i % 5];

// 사람은 고르게 보지 않는다. hot 확률로 오늘 거래대금(거래량 × 현재가)에 비례해 고르고,
// 아니면 열 번 중 일곱 번은 앞쪽 20개 구단(인기 구단)을 본다
function pickTeam(hot = 0) {
  if (Math.random() < hot) {
    const value = tradable.map(id => { const q = quotes.get(id); return q ? q.volume * q.lastPrice : 0; });
    const sum = value.reduce((a, b) => a + b, 0);
    if (sum > 0) {
      let r = Math.random() * sum;
      for (let i = 0; i < tradable.length; i++) if ((r -= value[i]) < 0) return tradable[i];
    }
  }
  const top = Math.min(20, tradable.length);
  return tradable[Math.random() < 0.7 ? rnd(top) : rnd(tradable.length)];
}

const clamp1 = x => Math.max(-1, Math.min(1, x));

// 점검시간(한국 시간 00:00~00:30)에는 서버가 주문을 받지 않으므로 주문만 건너뛴다. 새로 고침·시세 수신은 계속한다
const inMaintenance = () => { const d = new Date(Date.now() + 9 * 3_600_000); return d.getUTCHours() === 0 && d.getUTCMinutes() < 30; };

// ---------- 봇 ----------

class Bot {
  constructor(name, style) {
    this.name = name;
    this.style = style;
    // 봇마다 다른 성격(생성할 때 한 번 정한다)
    this.chase = Math.random() * 4;            // 확신 1일 때 현재가에서 쫓아가는 호가 수
    this.takeProfit = 0.05 + Math.random() * 0.15;
    this.stopLoss = 0.05 + Math.random() * 0.15;
    this.sizeBoost = Math.random() * 5;        // 확신 1일 때 더 사고파는 주식 수
    this.maxPosition = 0.05 + Math.random() * 0.2; // 한 구단 보유 평가액 상한(총자산 대비)
    this.cashReserve = 0.3 + Math.random() * 0.4;  // 늘 현금으로 남겨 두는 몫(총자산 대비). 이 위로만 산다
    // 판단에 섞는 신호를 얼마나 믿는지
    this.wForm = 0.3 + Math.random() * 0.7;    // 최근 성적
    this.wPrice = Math.random() * 2 - 1;       // 오늘 등락률. +면 많이 오른 구단을 비싸다고 보고, -면 오르는 구단을 따라간다
    this.wBook = Math.random();                // 호가 잔량
    this.hot = Math.random();                  // 거래대금 큰 구단을 골라 볼 확률
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
    // 이 요청이 실패하면 CSRF 쿠키가 없어 로그인이 403으로 막힌다. 보내지 않고 실패로 센다
    if (!this.cookies.has('XSRF-TOKEN')) throw new Error('CSRF 쿠키를 받지 못함');
    const headers = { 'content-type': 'application/json', 'x-bot-token': TOKEN,
      'x-xsrf-token': this.cookies.get('XSRF-TOKEN') ?? '',
      cookie: [...this.cookies].map(([k, v]) => `${k}=${v}`).join('; ') };
    const res = await fetch(BASE + '/api/auth/bot-login', { method: 'POST', headers, body: JSON.stringify({ name: this.name }) });
    for (const c of res.headers.getSetCookie()) {
      const [kv] = c.split(';');
      const i = kv.indexOf('=');
      this.cookies.set(kv.slice(0, i), kv.slice(i + 1));
    }
    if (res.status !== 204) throw new Error(`로그인 응답 ${res.status}`);
  }

  // 화면 store.server.ts refresh()와 같은 요청. 새로 고침 한 번이 요청 한 번이다
  async poll() {
    const q = new URLSearchParams({ mine: 'true' });
    if (this.team) q.set('posts', this.team);
    const r = await this.req('GET', `/api/refresh?${q}`, undefined, window_.poll);
    // 서버가 다시 떠서 세션이 사라졌다. 다시 로그인하지 못하면 다음 새로 고침 때 다시 해 본다
    if (r.status === 401) return this.login().catch(() => { window_.loginFail++; });
    if (r.status !== 200) return;
    for (const t of r.json.teams) quotes.set(t.id, t);
    this.account = r.json.mine.account;
    this.open = r.json.mine.orders;
  }

  watch(teamId) {
    this.ws?.close();
    this.team = teamId;
    teamForm(teamId).catch(() => {});
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
    if (inMaintenance()) return;
    const q = quotes.get(this.team);
    if (!q || !q.listed || !this.account) return;
    if (this.open.length > 5) {
      const oldest = this.open.reduce((a, b) => (a.id < b.id ? a : b));
      await this.req('DELETE', `/api/orders/${oldest.id}`, undefined, window_.order);
      return;
    }
    const t = tick(q.lastPrice);
    const book = (await this.req('GET', `/api/teams/${this.team}/orderbook?depth=5`)).json;
    const bestAsk = book?.asks?.[0]?.price, bestBid = book?.bids?.[0]?.price;
    const holding = this.account.holdings.find(h => h.teamId === this.team);
    const have = holding?.available ?? 0;
    // dir: 이 봇이 보기에 오를 쪽(+)인지 내릴 쪽(-)인지, -1~1. 무작위형은 0
    //   성적 × 성향, 등락률(10%면 꽉 참), 호가 잔량 쏠림((매수 잔량 - 매도 잔량) / 합)을 봇의 가중치로 평균한다
    const sum = levels => (levels ?? []).reduce((a, l) => a + l.quantity, 0);
    const bids = sum(book?.bids), asks = sum(book?.asks);
    const signals = [
      [this.wForm, (form.get(this.team)?.score ?? 0) * this.style],
      [this.wPrice, -clamp1((q.changeRate ?? 0) / 0.1)],
      [this.wBook, bids + asks > 0 ? (bids - asks) / (bids + asks) : 0],
    ];
    const dir = this.style === 0 ? 0
      : clamp1(signals.reduce((a, [w, x]) => a + w * x, 0) / signals.reduce((a, [w]) => a + Math.abs(w), 0));
    const conviction = Math.abs(dir);
    let side = null, qty = 1 + rnd(3), shift = 0;
    const gain = have > 0 && holding.averagePrice > 0 ? q.lastPrice / holding.averagePrice - 1 : 0;
    if (have > 0 && gain >= this.takeProfit) {
      side = 'SELL'; qty = Math.ceil(have / 2);          // 익절: 절반
    } else if (have > 0 && gain <= -this.stopLoss) {
      side = 'SELL'; qty = have;                          // 손절: 전부
    } else {
      // 살 확률 = 50% + 25% × dir(25%~75%)
      // 주식이 없으면 사기만 한다
      side = have === 0 || Math.random() < 0.5 + 0.25 * dir ? 'BUY' : 'SELL';
      // 생각한 방향으로 주문할 때만 확신만큼 수량을 늘리고 가격을 쫓아간다(사면 위로, 팔면 아래로)
      if ((side === 'BUY' && dir > 0) || (side === 'SELL' && dir < 0)) {
        qty += Math.round(conviction * this.sizeBoost);
        shift = Math.round(conviction * this.chase);
      }
    }
    // 사는 쪽은 매도 1호가에서 0~3호가 아래(매도 1호가면 바로 체결), 파는 쪽은 매수 1호가에서 0~3호가 위.
    // 확신만큼 상대 호가 쪽으로 더 쫓아간다. 상대 호가가 없으면 현재가 기준
    const priceFor = s => {
      const p = s === 'BUY' ? (bestAsk ?? q.lastPrice) + (shift - rnd(4)) * t : (bestBid ?? q.lastPrice) + (rnd(4) - shift) * t;
      return Math.min(q.upperLimit, Math.max(q.lowerLimit, p));
    };
    let price = priceFor(side);
    if (side === 'BUY') {
      // 한 구단 상한과, 현금 몫을 남긴 주문 가능 포인트 안에서만 산다. 못 사면 가진 주식을 판다
      const room = Math.floor((this.maxPosition * this.account.totalValue - ((holding?.available ?? 0) + (holding?.locked ?? 0)) * q.lastPrice) / price);
      const spendable = this.account.availablePoints - this.cashReserve * this.account.totalValue;
      qty = Math.min(qty, room, Math.floor(spendable / price));
      if (qty <= 0) { side = have > 0 ? 'SELL' : null; qty = 1 + rnd(3); shift = 0; price = priceFor(side); }
    }
    if (side === 'SELL') qty = Math.min(qty, have);
    if (!side || qty <= 0) return;
    await this.req('POST', '/api/orders', {
      clientOrderId: `${this.name}-${Date.now().toString(36)}-${this.n++}`,
      teamId: this.team, side, type: 'LIMIT', price, quantity: qty,
    }, window_.order);
  }

  async run() {
    // 서버가 버거워 로그인에 실패해도 봇을 버리지 않고 5~10초 뒤 다시 한다
    while (!stopping) {
      try {
        await this.login();
        break;
      } catch {
        window_.loginFail++;
        await sleep(5000 + rnd(5000));
      }
    }
    if (stopping) return;
    online++;
    this.watch(pickTeam(this.hot));
    let nextOrder = Date.now() + exp(ORDER_EVERY) * 1000;
    let nextSwitch = Date.now() + exp(SWITCH_EVERY) * 1000;
    // 처음 접속한 봇이 한꺼번에 몰리지 않게 새로 고침 시점을 흩는다
    await sleep(rnd(POLL_MS));
    while (!stopping) {
      const t0 = Date.now();
      try {
        await this.poll();
        if (Date.now() >= nextSwitch) {
          this.watch(pickTeam(this.hot));
          nextSwitch = Date.now() + exp(SWITCH_EVERY) * 1000;
        }
        if (Date.now() >= nextOrder) {
          await this.order();
          nextOrder = Date.now() + exp(ORDER_EVERY) * 1000;
        }
      } catch (e) {
        console.error(e.message);
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
  const bot = new Bot(`${PREFIX}-${String(i + 1).padStart(4, '0')}`, styleOf(i));
  bot.run().catch(e => console.error(e.message));
  await sleep((RAMP * 1000) / BOTS);
}
