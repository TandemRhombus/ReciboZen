FROM node:22.17.0-alpine3.22@sha256:fc3e945f920b7e3000cd1af86c4ae406ec70c72f328b667baf0f3a8910d69eed

RUN addgroup -S -g 10001 recibozen \
    && adduser -S -D -H -u 10001 -G recibozen recibozen

WORKDIR /app
COPY --chown=10001:10001 package.json ./
COPY --chown=10001:10001 src ./src

ENV NODE_ENV=production \
    PORT=8080 \
    NODE_EXTRA_CA_CERTS=/etc/ssl/certs/ca-certificates.crt

USER 10001:10001
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD node -e "fetch('http://127.0.0.1:8080/healthz').then(r=>{if(!r.ok)process.exit(1)}).catch(()=>process.exit(1))"

CMD ["node", "src/server.mjs"]
