# 백업 서비스 이미지: MySQL 클라이언트(mysqldump)에 백업 스크립트를 넣는다
FROM mysql:8.4
COPY backup.sh /usr/local/bin/backup.sh
# Windows에서 받은 저장소는 줄 끝이 CRLF일 수 있다
RUN sed -i 's/\r$//' /usr/local/bin/backup.sh && chmod +x /usr/local/bin/backup.sh
ENTRYPOINT ["/usr/local/bin/backup.sh"]
