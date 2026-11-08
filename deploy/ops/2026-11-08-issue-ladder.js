// 2026-11-08 운영자 매도를 계단식으로 나눈다(ADR 0031 후속).
// 운영자 계정으로 로그인한 fanchart.kr 탭의 개발자 도구(F12) 콘솔에 이 파일 내용을 통째로 붙여 넣는다.
// 점검시간(00:00~00:30 KST)에는 주문이 막히므로 그 밖에 실행한다.
//
// 지금은 구단마다 운영자 매도가 10,000P에 한 덩어리로 걸려 있어, 누가 사도 그 물량이 다 팔릴 때까지 가격이 오르지 않는다.
// 이 스크립트는 그 덩어리 주문을 취소하고, 남은 물량을 처음 가격부터 50P 간격으로 50주씩 나눠 다시 건다.
// (1,000주가 그대로 남아 있으면 10,000P ~ 10,950P 20단계)
//
// 새로 발행하는 주식은 없다. 취소하면 운영자 계정의 판매 가능 수량으로 돌아오고, 다시 거는 수량은 그 판매 가능 수량을 넘지 않는다.
// 그래서 중간에 끊겼거나 두 번 실행해도 구단별 총 주식 수는 늘지 않는다. 이미 계단으로 나뉜 구단은 덩어리 주문이 없으니 건너뛴다.
(async () => {
  const STEP_PRICE = 50
  const STEP_QTY = 50

  const m = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/)
  const xsrf = m ? decodeURIComponent(m[1]) : ''
  const call = async (method, path, body) => {
    const r = await fetch(path, {
      method,
      headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': xsrf },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
    return { status: r.status, text: await r.text() }
  }
  const json = async (path) => {
    const r = await call('GET', path)
    if (r.status !== 200) throw new Error(`${path} ${r.status} ${r.text}`)
    return JSON.parse(r.text)
  }

  const me = await json('/api/accounts/me')
  if (!me.admin) { document.body.innerText = '운영자 계정으로 로그인한 뒤 실행하세요'; return }

  const quotes = new Map((await json('/api/teams')).map((q) => [q.id, q]))
  const sells = (await json('/api/accounts/me/orders')).filter((o) => o.side === 'SELL' && o.type === 'LIMIT')
  const run = Date.now().toString(36)
  const problems = []
  let laddered = 0
  let skipped = 0

  // 1) 덩어리 주문(한 번에 STEP_QTY보다 많이 건 운영자 매도)을 취소한다
  const starts = new Map()
  for (const o of sells) {
    if (o.quantity <= STEP_QTY) continue
    const r = await call('DELETE', `/api/orders/${o.id}`)
    if (r.status === 204) starts.set(o.teamId, Math.min(starts.get(o.teamId) ?? Infinity, o.price))
    else problems.push(`${o.teamId} 취소 ${r.status} ${r.text}`)
  }
  // 이전 실행이 취소만 하고 끊긴 구단은 덩어리 주문이 없고 판매 가능 수량만 남아 있다. 그 구단은 기준가부터 건다
  const holdings = (await json('/api/accounts/me')).holdings.filter((h) => h.available > 0)
  for (const h of holdings) if (!starts.has(h.teamId)) starts.set(h.teamId, quotes.get(h.teamId)?.basePrice)

  // 2) 판매 가능 수량을 계단으로 나눠 건다. 이미 걸린 계단 가격은 피한다
  const taken = new Set(sells.filter((o) => o.quantity <= STEP_QTY).map((o) => `${o.teamId}:${o.price}`))
  for (const h of holdings) {
    const q = quotes.get(h.teamId)
    const start = starts.get(h.teamId)
    if (!q || !start) { problems.push(`${h.teamId} 시세 없음, 판매 가능 ${h.available}주 남김`); continue }
    let left = h.available
    let price = start
    while (left > 0 && price <= q.upperLimit) {
      if (!taken.has(`${h.teamId}:${price}`)) {
        const qty = Math.min(STEP_QTY, left)
        const r = await call('POST', '/api/orders', {
          clientOrderId: `ladder-${run}-${h.teamId}-${price}`, teamId: h.teamId, side: 'SELL', type: 'LIMIT', price, quantity: qty,
        })
        if (r.status >= 200 && r.status < 300) left -= qty
        else { problems.push(`${h.teamId} ${price}P 매도 ${r.status} ${r.text}`); break }
      }
      price += STEP_PRICE
    }
    if (left > 0) problems.push(`${h.teamId} ${left}주는 상한가(${q.upperLimit}P) 위라 못 걸고 남김`)
    if (left < h.available) laddered++
  }
  skipped = quotes.size - laddered

  document.body.innerText = `구단 ${quotes.size}개 중 계단 매도 ${laddered}개 완료, 건너뜀 ${skipped}개\n`
    + (problems.length ? '문제:\n' + problems.join('\n') : '문제 없음')
})()
