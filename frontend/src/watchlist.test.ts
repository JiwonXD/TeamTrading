import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

// 서버 모드 관심 구단 토글: 빠르게 여러 번 눌러도 요청이 누른 순서대로 하나씩 가고, 화면이 되돌아가지 않는다

/** 요청을 받아 두었다가 테스트가 하나씩 응답하게 하는 가짜 서버 */
function fakeServer() {
  const list: string[] = []
  const pending: { method: string; path: string; respond: () => void; fail: () => void }[] = []
  const fetch = vi.fn((path: string, init: { method: string }) => new Promise<Response>((resolve) => {
    const json = (status: number, body: unknown) => resolve(new Response(JSON.stringify(body), { status }))
    pending.push({
      method: init.method,
      path,
      respond: () => {
        const id = decodeURIComponent(path.split('/').pop()!)
        if (init.method === 'PUT' && !list.includes(id)) list.push(id)
        if (init.method === 'DELETE') list.splice(0, list.length, ...list.filter((x) => x !== id))
        json(200, { teamIds: [...list] })
      },
      fail: () => json(500, { message: '서버 오류' }),
    })
  }))
  return { list, pending, fetch }
}

const flush = () => new Promise((r) => setTimeout(r, 0))

describe('toggleWatch (server)', () => {
  let server: ReturnType<typeof fakeServer>

  beforeEach(() => {
    vi.resetModules()
    server = fakeServer()
    vi.stubGlobal('fetch', server.fetch)
    vi.stubGlobal('document', { cookie: '' })
  })
  afterEach(() => vi.unstubAllGlobals())

  it('sends rapid toggles one at a time in click order and keeps the screen on the last click', async () => {
    const store = await import('./store.server')
    const a = store.toggleWatch('t1') // 담기
    const b = store.toggleWatch('t1') // 빼기
    const c = store.toggleWatch('t1') // 다시 담기
    expect(store.getWatchlist()).toEqual(['t1'])
    await flush()
    // 첫 요청이 끝나기 전에는 다음 요청을 보내지 않는다
    expect(server.pending.map((p) => p.method)).toEqual(['PUT'])

    server.pending[0].respond()
    await a
    // 아직 보낼 누름이 남아 있으니 서버의 중간 목록으로 화면을 되돌리지 않는다
    expect(store.getWatchlist()).toEqual(['t1'])
    await flush()
    expect(server.pending.map((p) => p.method)).toEqual(['PUT', 'DELETE'])

    server.pending[1].respond()
    await b
    expect(store.getWatchlist()).toEqual(['t1'])
    await flush()
    server.pending[2].respond()
    expect((await c).ok).toBe(true)
    expect(server.list).toEqual(['t1'])
    expect(store.getWatchlist()).toEqual(['t1'])
  })

  it('falls back to the server list when a toggle fails', async () => {
    const store = await import('./store.server')
    const done = store.toggleWatch('geng')
    expect(store.getWatchlist()).toEqual(['geng'])
    await flush()
    server.pending[0].fail()
    await flush()
    // 실패하면 서버 목록을 다시 받아 그대로 맞춘다
    expect(server.pending[1].method).toBe('GET')
    server.pending[1].respond()
    const r = await done
    expect(r).toEqual({ ok: false, message: '서버 오류' })
    expect(store.getWatchlist()).toEqual([])
  })
})
