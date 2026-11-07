# 화면 이미지: 서버 모드로 빌드한 정적 파일을 nginx가 준다. 빌드도 컨테이너 안에서 한다
FROM node:22-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
# 약관·개인정보처리방침의 운영자 이름과 연락처(deploy/.env의 OPERATOR_NAME, OPERATOR_EMAIL)
ARG VITE_OPERATOR_NAME=""
ARG VITE_OPERATOR_EMAIL=""
RUN VITE_BACKEND=server npm run build

FROM nginx:1.27-alpine
COPY --from=build /src/dist /usr/share/nginx/html
