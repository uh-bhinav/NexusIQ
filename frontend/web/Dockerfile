# Production image: multi-stage, resource-limited + healthchecked via
# docker-compose.prod.yml. nginx already runs as its own non-root worker
# processes by default in this image.

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
# Target 127.0.0.1, not localhost: /etc/hosts resolves localhost to ::1
# first, but nginx only listens on 0.0.0.0:80 (our template replaces the
# base image's own IPv6 listen directive), so "localhost" gets a false
# connection-refused — confirmed during Phase 12 live verification.
HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1/ || exit 1
