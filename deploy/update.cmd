@echo off
rem 더블클릭하면 최신 코드를 받고 이미지를 다시 빌드해 띄운다(Windows 서버 PC용).
rem 터널까지 함께 띄우려면 .env에 COMPOSE_PROFILES=tunnel 을 둔다.
chcp 65001 > nul
cd /d "%~dp0"

echo [1/3] 최신 코드 받기
git pull || goto fail

echo [2/3] 다시 빌드하고 띄우기 (몇 분 걸려요)
docker compose up -d --build || goto fail

echo [3/3] 상태
docker compose ps
echo.
echo 끝났어요. 창을 닫아도 돼요.
pause
exit /b 0

:fail
echo.
echo 실패했어요. 위 메시지를 확인하세요.
pause
exit /b 1
