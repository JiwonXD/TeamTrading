// 2026-11-07 전 구단 등록과 운영자 매도(증자)(ADR 0031).
// 운영자 계정으로 로그인한 fanchart.kr 탭의 개발자 도구(F12) 콘솔에 이 파일 내용을 통째로 붙여 넣는다.
// 1) 아래 TEAMS를 등록한다(첫 기준가 10,000P). 이미 있으면 건너뛴다.
// 2) ESPN 팀 번호가 있는 구단은 경기 수집용으로 연결한다(유럽 4대 리그, NBA).
// 3) 모든 구단(기존 구단 포함)에 운영자 매도 1,000주 × 10,000P를 낸다. 주문 번호가 issue-<구단 id>라 여러 번 실행해도 한 번만 낸다.
(async () => {
  const PRICE = 10000
  const QTY = 1000
  // [id, 이름, 짧은 이름, 리그, 색, ESPN 팀 번호]
  const TEAMS = [
    // LCK(기존 t1·geng·hle·dk 외)
    ['kt-rolster', 'KT 롤스터', 'KT', 'LCK', '#e60012'],
    ['nongshim', '농심 레드포스', 'NS', 'LCK', '#de2027'],
    ['fearx', 'BNK 피어엑스', 'BFX', 'LCK', '#b38f00'],
    ['dn-soopers', 'DN 수퍼스', 'DNS', 'LCK', '#1e3a8a'],
    ['brion', '한진 브리온', 'BRO', 'LCK', '#00573f'],
    ['kiwoom-drx', '키움 DRX', 'DRX', 'LCK', '#1e5bc6'],
    // K리그1(기존 ulsan·jeonbuk·seoul·pohang 외)
    ['gangwon', '강원FC', '강원', 'K_LEAGUE', '#e85a1f'],
    ['gwangju', '광주FC', '광주', 'K_LEAGUE', '#c99700'],
    ['gimcheon', '김천 상무', '김천', 'K_LEAGUE', '#002f6c'],
    ['daejeon', '대전하나시티즌', '대전', 'K_LEAGUE', '#005a40'],
    ['bucheon', '부천FC1995', '부천', 'K_LEAGUE', '#c8102e'],
    ['anyang', 'FC안양', '안양', 'K_LEAGUE', '#5b2c86'],
    ['incheon', '인천 유나이티드', '인천', 'K_LEAGUE', '#004ea2'],
    ['jeju', '제주SK', '제주', 'K_LEAGUE', '#f37321'],
    // 프리미어리그
    ['arsenal', '아스널', '아스널', 'EPL', '#ef0107', '359'],
    ['aston-villa', '아스톤 빌라', '빌라', 'EPL', '#670e36', '362'],
    ['bournemouth', '본머스', '본머스', 'EPL', '#da291c', '349'],
    ['brentford', '브렌트퍼드', '브렌트', 'EPL', '#e30613', '337'],
    ['brighton', '브라이턴', 'BHA', 'EPL', '#0057b8', '331'],
    ['chelsea', '첼시', '첼시', 'EPL', '#034694', '363'],
    ['coventry', '코번트리 시티', 'COV', 'EPL', '#3a8fc9', '388'],
    ['crystal-palace', '크리스털 팰리스', '팰리스', 'EPL', '#1b458f', '384'],
    ['everton', '에버턴', '에버턴', 'EPL', '#003399', '368'],
    ['fulham', '풀럼', '풀럼', 'EPL', '#000000', '370'],
    ['hull', '헐 시티', '헐', 'EPL', '#e07b00', '306'],
    ['ipswich', '입스위치 타운', 'IPS', 'EPL', '#0044a9', '373'],
    ['leeds', '리즈 유나이티드', '리즈', 'EPL', '#1d428a', '357'],
    ['liverpool', '리버풀', '리버풀', 'EPL', '#c8102e', '364'],
    ['man-city', '맨체스터 시티', '맨시티', 'EPL', '#3d8fd1', '382'],
    ['man-utd', '맨체스터 유나이티드', '맨유', 'EPL', '#da291c', '360'],
    ['newcastle', '뉴캐슬 유나이티드', '뉴캐슬', 'EPL', '#241f20', '361'],
    ['nottm-forest', '노팅엄 포레스트', '노팅엄', 'EPL', '#dd0000', '393'],
    ['sunderland', '선덜랜드', 'SUN', 'EPL', '#eb172b', '366'],
    ['tottenham', '토트넘 홋스퍼', '토트넘', 'EPL', '#132257', '367'],
    // 라리가
    ['alaves', '데포르티보 알라베스', 'ALA', 'LA_LIGA', '#0761af', '96'],
    ['athletic', '아틀레틱 빌바오', '빌바오', 'LA_LIGA', '#ee2523', '93'],
    ['atletico', '아틀레티코 마드리드', 'ATM', 'LA_LIGA', '#cb3524', '1068'],
    ['barcelona', 'FC 바르셀로나', '바르사', 'LA_LIGA', '#a50044', '83'],
    ['celta', '셀타 비고', '셀타', 'LA_LIGA', '#3b7bbf', '85'],
    ['deportivo', '데포르티보 라코루냐', '데포르', 'LA_LIGA', '#0055a5', '90'],
    ['elche', '엘체', '엘체', 'LA_LIGA', '#05642c', '3751'],
    ['espanyol', '에스파뇰', 'ESP', 'LA_LIGA', '#007fc8', '88'],
    ['getafe', '헤타페', '헤타페', 'LA_LIGA', '#005999', '2922'],
    ['levante', '레반테', '레반테', 'LA_LIGA', '#b4053f', '1538'],
    ['malaga', '말라가', '말라가', 'LA_LIGA', '#1b6fb8', '99'],
    ['osasuna', '오사수나', 'OSA', 'LA_LIGA', '#d91a21', '97'],
    ['racing', '라싱 산탄데르', '라싱', 'LA_LIGA', '#007a3d', '87'],
    ['rayo', '라요 바예카노', '라요', 'LA_LIGA', '#e53027', '101'],
    ['betis', '레알 베티스', '베티스', 'LA_LIGA', '#00954c', '244'],
    ['real-madrid', '레알 마드리드', '레알', 'LA_LIGA', '#00529f', '86'],
    ['real-sociedad', '레알 소시에다드', 'RSO', 'LA_LIGA', '#0067b1', '89'],
    ['sevilla', '세비야', '세비야', 'LA_LIGA', '#d71920', '243'],
    ['valencia', '발렌시아', 'VAL', 'LA_LIGA', '#ef7d00', '94'],
    ['villarreal', '비야레알', 'VIL', 'LA_LIGA', '#b38f00', '102'],
    // 세리에A
    ['milan', 'AC 밀란', '밀란', 'SERIE_A', '#fb090b', '103'],
    ['roma', 'AS 로마', '로마', 'SERIE_A', '#8e1f2f', '104'],
    ['atalanta', '아탈란타', 'ATA', 'SERIE_A', '#1e71b8', '105'],
    ['bologna', '볼로냐', '볼로냐', 'SERIE_A', '#1a2f48', '107'],
    ['cagliari', '칼리아리', 'CAG', 'SERIE_A', '#002350', '2925'],
    ['como', '코모', '코모', 'SERIE_A', '#0b3a7e', '2572'],
    ['fiorentina', '피오렌티나', 'FIO', 'SERIE_A', '#482e92', '109'],
    ['frosinone', '프로시노네', 'FRO', 'SERIE_A', '#1d4f91', '4057'],
    ['genoa', '제노아', '제노아', 'SERIE_A', '#a6192e', '3263'],
    ['inter', '인테르', '인테르', 'SERIE_A', '#010e80', '110'],
    ['juventus', '유벤투스', '유베', 'SERIE_A', '#000000', '111'],
    ['lazio', '라치오', '라치오', 'SERIE_A', '#2b8ccf', '112'],
    ['lecce', '레체', '레체', 'SERIE_A', '#d4001a', '113'],
    ['monza', '몬차', '몬차', 'SERIE_A', '#e30613', '4007'],
    ['napoli', '나폴리', '나폴리', 'SERIE_A', '#0f87c5', '114'],
    ['parma', '파르마', '파르마', 'SERIE_A', '#1b3f8b', '115'],
    ['sassuolo', '사수올로', 'SAS', 'SERIE_A', '#00a752', '3997'],
    ['torino', '토리노', '토리노', 'SERIE_A', '#881f19', '239'],
    ['udinese', '우디네세', 'UDI', 'SERIE_A', '#231f20', '118'],
    ['venezia', '베네치아', 'VEN', 'SERIE_A', '#e8551c', '17530'],
    // 분데스리가
    ['union-berlin', '우니온 베를린', '우니온', 'BUNDESLIGA', '#eb1923', '598'],
    ['leverkusen', '바이어 레버쿠젠', 'B04', 'BUNDESLIGA', '#e32221', '131'],
    ['bayern', '바이에른 뮌헨', '뮌헨', 'BUNDESLIGA', '#dc052d', '132'],
    ['dortmund', '보루시아 도르트문트', 'BVB', 'BUNDESLIGA', '#1a1a1a', '124'],
    ['gladbach', '보루시아 묀헨글라트바흐', 'BMG', 'BUNDESLIGA', '#009a44', '268'],
    ['frankfurt', '아인트라흐트 프랑크푸르트', 'SGE', 'BUNDESLIGA', '#e1000f', '125'],
    ['augsburg', 'FC 아우크스부르크', 'FCA', 'BUNDESLIGA', '#ba3733', '3841'],
    ['koln', '1. FC 쾰른', '쾰른', 'BUNDESLIGA', '#ed1c24', '122'],
    ['hamburg', '함부르크 SV', 'HSV', 'BUNDESLIGA', '#0a3f86', '127'],
    ['mainz', '마인츠 05', '마인츠', 'BUNDESLIGA', '#c3141e', '2950'],
    ['leipzig', 'RB 라이프치히', 'RBL', 'BUNDESLIGA', '#dd0741', '11420'],
    ['freiburg', 'SC 프라이부르크', 'SCF', 'BUNDESLIGA', '#e2001a', '126'],
    ['paderborn', 'SC 파더보른 07', 'SCP', 'BUNDESLIGA', '#005ca9', '3307'],
    ['elversberg', 'SV 엘버스베르크', 'SVE', 'BUNDESLIGA', '#2d2d2d', '10388'],
    ['schalke', '샬케 04', '샬케', 'BUNDESLIGA', '#004d9d', '133'],
    ['hoffenheim', 'TSG 호펜하임', 'TSG', 'BUNDESLIGA', '#1961b5', '7911'],
    ['stuttgart', 'VfB 슈투트가르트', 'VFB', 'BUNDESLIGA', '#e32219', '134'],
    ['bremen', '베르더 브레멘', '브레멘', 'BUNDESLIGA', '#1d9053', '137'],
    // NBA(ESPN 번호는 종목을 붙여 연결한다: basketball:13)
    ['hawks', '애틀랜타 호크스', 'ATL', 'NBA', '#e03a3e', '1'],
    ['celtics', '보스턴 셀틱스', 'BOS', 'NBA', '#007a33', '2'],
    ['nets', '브루클린 네츠', 'BKN', 'NBA', '#000000', '17'],
    ['hornets', '샬럿 호네츠', 'CHA', 'NBA', '#1d1160', '30'],
    ['bulls', '시카고 불스', 'CHI', 'NBA', '#ce1141', '4'],
    ['cavaliers', '클리블랜드 캐벌리어스', 'CLE', 'NBA', '#860038', '5'],
    ['mavericks', '댈러스 매버릭스', 'DAL', 'NBA', '#00538c', '6'],
    ['nuggets', '덴버 너기츠', 'DEN', 'NBA', '#0e2240', '7'],
    ['pistons', '디트로이트 피스톤스', 'DET', 'NBA', '#c8102e', '8'],
    ['warriors', '골든스테이트 워리어스', 'GSW', 'NBA', '#1d428a', '9'],
    ['rockets', '휴스턴 로키츠', 'HOU', 'NBA', '#ce1141', '10'],
    ['pacers', '인디애나 페이서스', 'IND', 'NBA', '#002d62', '11'],
    ['clippers', 'LA 클리퍼스', 'LAC', 'NBA', '#c8102e', '12'],
    ['lakers', 'LA 레이커스', 'LAL', 'NBA', '#552583', '13'],
    ['grizzlies', '멤피스 그리즐리스', 'MEM', 'NBA', '#5d76a9', '29'],
    ['heat', '마이애미 히트', 'MIA', 'NBA', '#98002e', '14'],
    ['bucks', '밀워키 벅스', 'MIL', 'NBA', '#00471b', '15'],
    ['timberwolves', '미네소타 팀버울브스', 'MIN', 'NBA', '#0c2340', '16'],
    ['pelicans', '뉴올리언스 펠리컨스', 'NOP', 'NBA', '#0c2340', '3'],
    ['knicks', '뉴욕 닉스', 'NYK', 'NBA', '#006bb6', '18'],
    ['thunder', '오클라호마시티 선더', 'OKC', 'NBA', '#007ac1', '25'],
    ['magic', '올랜도 매직', 'ORL', 'NBA', '#0077c0', '19'],
    ['sixers', '필라델피아 76ers', 'PHI', 'NBA', '#006bb6', '20'],
    ['suns', '피닉스 선즈', 'PHX', 'NBA', '#1d1160', '21'],
    ['blazers', '포틀랜드 트레일블레이저스', 'POR', 'NBA', '#e03a3e', '22'],
    ['kings', '새크라멘토 킹스', 'SAC', 'NBA', '#5a2d81', '23'],
    ['spurs', '샌안토니오 스퍼스', 'SAS', 'NBA', '#000000', '24'],
    ['raptors', '토론토 랩터스', 'TOR', 'NBA', '#ce1141', '28'],
    ['jazz', '유타 재즈', 'UTA', 'NBA', '#002b5c', '26'],
    ['wizards', '워싱턴 위저즈', 'WAS', 'NBA', '#002b5c', '27'],
  ]

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
  const problems = []

  for (const [id, name, shortName, league, color, espn] of TEAMS) {
    const reg = await call('POST', '/api/admin/teams', { id, name, shortName, league, startPrice: PRICE, color })
    if (reg.status !== 201 && reg.status !== 409) { problems.push(`${id} 등록 ${reg.status} ${reg.text}`); continue }
    if (espn) {
      const externalId = league === 'NBA' ? `basketball:${espn}` : espn
      const link = await call('PUT', `/api/admin/teams/${id}/feed`, { source: 'espn', externalId })
      if (link.status !== 200) problems.push(`${id} ESPN 연결 ${link.status} ${link.text}`)
    }
  }

  const all = JSON.parse((await call('GET', '/api/teams')).text).map((q) => q.id)
  let sold = 0
  for (const teamId of all) {
    const r = await call('POST', '/api/orders',
      { clientOrderId: `issue-${teamId}`, teamId, side: 'SELL', type: 'LIMIT', price: PRICE, quantity: QTY })
    if (r.status >= 200 && r.status < 300) sold++
    else problems.push(`${teamId} 매도 ${r.status} ${r.text}`)
  }

  document.body.innerText = `구단 ${all.length}개 중 매도 ${sold}개 완료\n` + (problems.length ? '문제:\n' + problems.join('\n') : '문제 없음')
})()
