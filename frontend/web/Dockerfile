# Functional multi-stage image for Phase 11's CI docker-build stage.
# Full production hardening (resource limits, docker-compose.prod.yml
# wiring) is Phase 12.

FROM node:22-alpine AS build
WORKDIR /build
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.29-alpine
COPY --from=build /build/dist /usr/share/nginx/html
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
ENV API_PROXY_TARGET=http://spring-api:8080
EXPOSE 80
