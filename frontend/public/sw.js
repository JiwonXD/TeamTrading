// 앱 껍데기(화면 파일, 아이콘)만 캐시한다. 시세·잔고·주문(/api, /ws)과 로그인 경로는 건드리지 않는다.
// 오래된 시세를 보여주는 일이 없게 하려는 것이다. 화면 파일은 네트워크를 먼저 보고, 안 되면 캐시를 쓴다.
const CACHE = 'fanchart-shell-v1'
const SHELL = ['./', './manifest.webmanifest', './icons/icon-192.png', './icons/icon-512.png']
const LIVE = ['/api/', '/ws/', '/oauth2/', '/login/']

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(SHELL)))
  self.skipWaiting()
})

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))),
  )
  self.clients.claim()
})

self.addEventListener('fetch', (event) => {
  const req = event.request
  const url = new URL(req.url)
  if (req.method !== 'GET' || url.origin !== self.location.origin) return
  if (LIVE.some((p) => url.pathname.startsWith(p))) return

  if (req.mode === 'navigate') {
    event.respondWith(
      fetch(req)
        .then((res) => {
          const copy = res.clone()
          caches.open(CACHE).then((cache) => cache.put('./', copy))
          return res
        })
        .catch(() => caches.match('./')),
    )
    return
  }
  event.respondWith(caches.match(req).then((hit) => hit ?? fetch(req)))
})
