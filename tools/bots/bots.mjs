// 자동매매 봇 실행기(docs/bots.md). 봇 하나가 화면을 켜 둔 사용자 한 명처럼 움직인다. 의존성 없이 Node 22만 쓴다.
//   BASE_URL=http://localhost:8088 BOT_TOKEN=... BOTS=1000 node tools/bots/bots.mjs
//
// 봇 하나가 하는 일(화면 store.server.ts와 같은 요청):
//   - 3초마다 화면 새로 고침 요청 하나(/api/refresh, 보고 있는 구단 토론 포함)
//   - 보고 있는 구단의 실시간 시세 WebSocket 하나. 평균 SWITCH_EVERY초마다 다른 구단으로 옮긴다
//   - 평균 ORDER_EVERY초마다 지정가 주문 하나: 1호가 근처, 1~3주가 기본. 대기 주문이 5개를 넘으면 오래된 것부터 취소
//   - 봇마다 유형이 있다(docs/bots.md "봇 유형"): 가치투자·배당 캡처·추세 추종·개미·무작위.
//     판단 재료는 리그 일정(지난·앞으로 30일)으로 낸 Elo 실력 점수, 남은 일정의 상대별 이길 확률 × 승리 배당으로 낸 적정가,
//     다음 경기 기대 배당(경기 시작이 배당락), 경기 결과의 서프라이즈(결과 − 경기 전 이길 확률), 봇마다 다른 기간의 가격 흐름, 호가 잔량.
//     차트 모양은 정해 두지 않는다. 생각이 다른 봇들이 부딪혀 저절로 생기게 둔다
//   - 봇마다 성격이 다르다: 확신이 클 때 현재가에서 얼마나 쫓아가는지(0~4호가), 익절·손절 기준(평균 단가 대비 5~20%),
//     확신이 클 때 수량을 얼마나 늘리는지(0~5주), 한 구단에 넣는 상한(총자산의 5~25%),
//     늘 현금으로 남기는 몫(총자산의 30~70%), 적정가를 볼 때의 보유 기간(1~3시즌). 주문 가격은 실제 매도·매수 1호가 근처.
//     거래대금이 큰 구단을 더 자주 보는 정도도 봇마다 다르다
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

// ---------- 구단 가치 판단 재료(실행기 전체가 함께 쓴다) ----------

const DAY = 86_400_000;
const iso = t => new Date(t).toISOString();
// 리그별 한 시즌 경기 수(컵대회·포스트시즌을 대략 포함). 적정가 = 경기당 기대 배당 × 이 수 × 봇의 보유 기간
const SEASON_GAMES = { EPL: 38, LA_LIGA: 38, SERIE_A: 38, BUNDESLIGA: 34, NBA: 82, LCK: 30, KBO: 144, K_LEAGUE: 38 };
// Elo: 결과가 나오면 (실제 결과 - 이길 확률) × K만큼 고친다. 약팀을 이겨도 조금, 강팀을 이기면 많이 오른다
const ELO_K = 30, HOME_ELO = 50;
const pWin = (ra, rb, home) => 1 / (1 + 10 ** ((rb - ra - (home ? HOME_ELO : -HOME_ELO)) / 400));
const teamKey = (id, name) => id ?? `ext:${name}`;

const leagues = new Map(); // 리그 → { at, elo, last, upcoming, started }

/** 리그의 지난·앞으로 30일 일정으로 Elo, 구단별 마지막 결과의 서프라이즈, 남은 경기, 막 시작한 경기를 낸다. 10분 동안 함께 쓴다 */
async function league(name) {
  const cached = leagues.get(name);
  if (cached && Date.now() - cached.at < 10 * 60_000) return cached;
  const d = cached ?? { elo: new Map(), last: new Map(), upcoming: new Map(), started: new Map() };
  d.at = Date.now(); // 같은 리그를 여러 봇이 한꺼번에 부르지 않게(그동안은 예전 값)
  leagues.set(name, d);
  const now = Date.now();
  const get = (from, to) => fetch(`${BASE}/api/matches/schedule?league=${name}&from=${iso(from)}&to=${iso(to)}`)
    .then(r => (r.ok ? r.json() : []));
  const [past, future] = await Promise.all([get(now - 30 * DAY, now), get(now, now + 30 * DAY)]);
  const elo = new Map(), last = new Map(), upcoming = new Map(), started = new Map();
  for (const m of [...past].sort((a, b) => Date.parse(a.scheduledAt) - Date.parse(b.scheduledAt))) {
    const at = Date.parse(m.scheduledAt);
    // 경기 시작이 배당 기준 시각(배당락). 시작한 지 6시간 안이면 배당만 노린 봇이 판다
    if (now - at < 6 * 3_600_000) for (const id of [m.homeTeamId, m.awayTeamId]) if (id) started.set(id, at);
    if (m.status !== 'FINAL' || m.homeScore == null || m.awayScore == null) continue;
    const h = teamKey(m.homeTeamId, m.homeName), a = teamKey(m.awayTeamId, m.awayName);
    const rh = elo.get(h) ?? 1500, ra = elo.get(a) ?? 1500;
    const e = pWin(rh, ra, true);
    const result = m.homeScore > m.awayScore ? 1 : m.homeScore < m.awayScore ? 0 : 0.5;
    elo.set(h, rh + ELO_K * (result - e));
    elo.set(a, ra - ELO_K * (result - e));
    if (m.homeTeamId) last.set(m.homeTeamId, { at, surprise: result - e });
    if (m.awayTeamId) last.set(m.awayTeamId, { at, surprise: e - result });
  }
  for (const m of future) {
    if (m.status !== 'SCHEDULED') continue;
    const at = Date.parse(m.scheduledAt);
    const add = (id, opp, home) => { if (id) (upcoming.get(id) ?? upcoming.set(id, []).get(id)).push({ at, opp, home, competition: m.competition }); };
    add(m.homeTeamId, teamKey(m.awayTeamId, m.awayName), true);
    add(m.awayTeamId, teamKey(m.homeTeamId, m.homeName), false);
  }
  for (const list of upcoming.values()) list.sort((x, y) => x.at - y.at);
  Object.assign(d, { elo, last, upcoming, started });
  return d;
}

const infos = new Map(); // 구단 → { at, winDps, byCompetition }
/** 구단 정보 API의 대회별 승리 배당(주당). 1시간 동안 함께 쓴다 */
async function teamInfo(teamId) {
  const cached = infos.get(teamId);
  if (cached && Date.now() - cached.at < 3_600_000) return cached;
  const res = await fetch(`${BASE}/api/teams/${teamId}/overview`);
  if (!res.ok) return cached ?? { winDps: 0, byCompetition: new Map() };
  const o = await res.json();
  const info = { at: Date.now(), winDps: o.winDps ?? 0, byCompetition: new Map((o.competitions ?? []).map(c => [c.competition, c.winDps])) };
  infos.set(teamId, info);
  return info;
}

/**
 * 구단 전망. perSeason: 남은 일정(앞으로 30일, 없으면 평균 상대)의 상대별 이길 확률 × 승리 배당을 경기당으로 평균해 시즌 경기 수를 곱한 값.
 * next: 48시간 안 다음 경기의 기대 배당. started: 막 시작한 경기 시각. last: 마지막 결과의 서프라이즈
 */
async function outlook(teamId) {
  const q = quotes.get(teamId);
  const [lg, info] = await Promise.all([league(q.league), teamInfo(teamId)]);
  const r = lg.elo.get(teamId) ?? 1500;
  const ups = lg.upcoming.get(teamId) ?? [];
  const expect = m => pWin(r, lg.elo.get(m.opp) ?? 1500, m.home) * (info.byCompetition.get(m.competition) ?? info.winDps);
  const perMatch = ups.length ? ups.reduce((a, m) => a + expect(m), 0) / ups.length
    : (pWin(r, 1500, true) + pWin(r, 1500, false)) / 2 * info.winDps;
  const soon = ups.find(m => m.at - Date.now() < 2 * DAY);
  return {
    perSeason: perMatch * (SEASON_GAMES[q.league] ?? 38),
    next: soon ? expect(soon) : 0,
    started: lg.started.get(teamId),
    last: lg.last.get(teamId),
  };
}

const charts = new Map(); // 구단 → { at, candles }
/** 15분 봉 최근 48개(체결이 있던 구간만). 1분 동안 함께 쓴다 */
async function chart(teamId) {
  const cached = charts.get(teamId);
  if (cached && Date.now() - cached.at < 60_000) return cached.candles;
  charts.set(teamId, { at: Date.now(), candles: cached?.candles ?? [] });
  const res = await fetch(`${BASE}/api/teams/${teamId}/candles?unit=15m&limit=48`);
  const candles = res.ok ? (await res.json()).candles ?? [] : cached?.candles ?? [];
  charts.set(teamId, { at: Date.now(), candles });
  return candles;
}

/** 최근 lookback 시간 동안의 가격 흐름(-1~1). 그 사이 5% 오르면 +1. 사람이 차트를 보고 "오르는 중"·"빠지는 중"이라고 느끼는 정도 */
function trendOver(candles, lookbackMs, lastPrice) {
  const from = Date.now() - lookbackMs;
  const base = candles.find(k => Date.parse(k.time) >= from);
  return base ? clamp1((lastPrice / base.open - 1) / 0.05) : 0;
}

// 봇 유형. 20개마다 가치투자 6·배당 캡처 3·추세 추종 4·개미 5·무작위 2. 가중치는 봇마다 0.6~1.4배로 흔든다.
// 차트 모양(반등·돌파 등)을 정해 두지 않는다. 생각이 다른 봇들이 부딪혀 저절로 생기게 둔다
const TYPES = {
  가치투자: { value: 1, book: 0.2 },
  배당캡처: { capture: 1, value: 0.3 },
  추세추종: { trend: 1, book: 0.3 },
  개미: { surprise: 1, trend: 0.6, momentum: 0.5, book: 0.5 },
  무작위: {},
};
const TYPE_CYCLE = [
  ...Array(6).fill('가치투자'), ...Array(3).fill('배당캡처'), ...Array(4).fill('추세추종'),
  ...Array(5).fill('개미'), ...Array(2).fill('무작위'),
];
const typeOf = i => TYPE_CYCLE[i % TYPE_CYCLE.length];

// 사람은 고르게 보지 않는다. hot 확률로 오늘 거래대금(거래량 × 현재가)에 비례해 고르고,
// 아니면 경기가 가까운 구단(24시간 안 경기, 시작한 지 6시간 안, 끝난 지 12시간 안)을 4배 자주 본다
function pickTeam(hot = 0) {
  const pick = value => {
    const sum = value.reduce((a, b) => a + b, 0);
    if (sum <= 0) return null;
    let r = Math.random() * sum;
    for (let i = 0; i < tradable.length; i++) if ((r -= value[i]) < 0) return tradable[i];
    return null;
  };
  if (Math.random() < hot) {
    const id = pick(tradable.map(id => { const q = quotes.get(id); return q ? q.volume * q.lastPrice : 0; }));
    if (id) return id;
  }
  return pick(tradable.map(id => (inPlay(id) ? 4 : 1))) ?? tradable[rnd(tradable.length)];
}

/** 경기가 가까운 구단인지. 리그 일정은 10분마다 새로 받아 둔 것을 쓴다 */
function inPlay(teamId) {
  const lg = leagues.get(quotes.get(teamId)?.league);
  if (!lg) return false;
  const now = Date.now();
  const next = lg.upcoming.get(teamId)?.[0];
  const last = lg.last.get(teamId);
  return (next && next.at - now < DAY) || lg.started.has(teamId) || (last && now - last.at < 12 * 3_600_000);
}

const clamp1 = x => Math.max(-1, Math.min(1, x));

// 점검시간(한국 시간 00:00~00:30)에는 서버가 주문을 받지 않으므로 주문만 건너뛴다. 새로 고침·시세 수신은 계속한다
const inMaintenance = () => { const d = new Date(Date.now() + 9 * 3_600_000); return d.getUTCHours() === 0 && d.getUTCMinutes() < 30; };

// ---------- 봇 ----------

class Bot {
  constructor(name, type) {
    this.name = name;
    this.type = type;
    this.weights = Object.fromEntries(Object.entries(TYPES[type]).map(([k, w]) => [k, w * (0.6 + Math.random() * 0.8)]));
    this.horizon = 1 + Math.random() * 2;      // 적정가를 볼 때 몇 시즌치 배당을 치는지
    this.lookback = (type === '개미' ? 0.5 + Math.random() * 2.5 : 2 + Math.random() * 10) * 3_600_000; // 흐름을 보는 기간(개미는 짧다)
    // 봇마다 다른 성격(생성할 때 한 번 정한다)
    this.chase = type === '개미' ? 2 + Math.random() * 3 : Math.random() * 4; // 확신 1일 때 쫓아가는 호가 수(개미는 성급하다)
    this.takeProfit = 0.05 + Math.random() * 0.15;
    this.stopLoss = 0.05 + Math.random() * 0.15;
    this.sizeBoost = Math.random() * 5;        // 확신 1일 때 더 사고파는 주식 수
    this.maxPosition = 0.05 + Math.random() * 0.2; // 한 구단 보유 평가액 상한(총자산 대비)
    this.cashReserve = 0.3 + Math.random() * 0.4;  // 늘 현금으로 남겨 두는 몫(총자산 대비). 이 위로만 산다
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
    outlook(teamId).catch(() => {});
    chart(teamId).catch(() => {});
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

  /** 이 봇이 보기에 오를 쪽(+)인지 내릴 쪽(-)인지, -1~1. 유형별 가중치로 신호를 평균한다. 무작위형은 0 */
  async direction(q, book) {
    const w = this.weights;
    if (!Object.keys(w).length) return 0;
    const [o, candles] = await Promise.all([outlook(this.team), chart(this.team)]);
    const now = Date.now();
    const sum = levels => (levels ?? []).reduce((a, l) => a + l.quantity, 0);
    const bids = sum(book?.bids), asks = sum(book?.asks);
    const age = o.last ? (now - o.last.at) / 3_600_000 : Infinity; // 마지막 경기 뒤 몇 시간
    const signals = {
      // 적정가 대비 싸면 +. 적정가의 절반만큼 차이 나면 꽉 찬다
      value: o.perSeason > 0 ? clamp1((o.perSeason * this.horizon / q.lastPrice - 1) / 0.5) : 0,
      // 경기 전에는 기대 배당이 가격의 2%면 꽉 찬 매수, 시작하고 6시간 동안은 배당락이라 판다
      capture: o.started ? -0.8 : clamp1(o.next / q.lastPrice / 0.02),
      // 결과가 예상 밖이면 그쪽으로 몰린다. 시간이 지날수록 약해져 12시간 뒤에는 안 본다
      surprise: age < 12 ? clamp1(o.last.surprise * 2) * Math.exp(-age / 6) : 0,
      momentum: clamp1((q.changeRate ?? 0) / 0.1),
      book: bids + asks > 0 ? (bids - asks) / (bids + asks) : 0,
      trend: trendOver(candles, this.lookback, q.lastPrice),
    };
    let num = 0, den = 0;
    for (const [k, wk] of Object.entries(w)) { num += wk * (signals[k] ?? 0); den += wk; }
    return clamp1(num / den);
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
    const dir = await this.direction(q, book).catch(() => 0);
    const conviction = Math.abs(dir);
    let side = null, qty = 1 + rnd(3), shift = 0;
    const gain = have > 0 && holding.averagePrice > 0 ? q.lastPrice / holding.averagePrice - 1 : 0;
    if (have > 0 && gain >= this.takeProfit) {
      side = 'SELL'; qty = Math.ceil(have / 2);          // 익절: 절반
    } else if (have > 0 && gain <= -this.stopLoss) {
      side = 'SELL'; qty = have;                          // 손절: 전부
    } else {
      // 살 확률 25%~75%. 주식이 없는데 팔 차례면 주문하지 않는다
      side = Math.random() < 0.5 + 0.25 * dir ? 'BUY' : have > 0 ? 'SELL' : null;
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
    const price = priceFor(side);
    if (side === 'BUY') {
      // 한 구단 상한과, 현금 몫을 남긴 주문 가능 포인트 안에서만 산다. 못 사면 이번에는 주문하지 않는다
      // (예전에는 대신 팔았는데, 돈이 떨어진 봇이 팔기만 해 가격을 끌어내렸다)
      const room = Math.floor((this.maxPosition * this.account.totalValue - ((holding?.available ?? 0) + (holding?.locked ?? 0)) * q.lastPrice) / price);
      const spendable = this.account.availablePoints - this.cashReserve * this.account.totalValue;
      qty = Math.min(qty, room, Math.floor(spendable / price));
      if (qty <= 0) return;
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
// 경기가 가까운 구단을 고르려고 거래 대상 리그 일정을 미리 받아 두고 10분마다 새로 받는다
const loadLeagues = () => new Set(tradable.map(id => quotes.get(id).league)).forEach(n => league(n).catch(() => {}));
loadLeagues();
setInterval(loadLeagues, 10 * 60_000).unref();
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
  const bot = new Bot(`${PREFIX}-${String(i + 1).padStart(4, '0')}`, typeOf(i));
  bot.run().catch(e => console.error(e.message));
  await sleep((RAMP * 1000) / BOTS);
}
