# Operación del backend ReciboZen en Orange Pi

Estado: desplegado y saludable el 11 de agosto de 2026.

## Aislamiento actual

- Directorio remoto: `/home/orangepi/recibozen/backend`.
- Proyecto Compose: `recibozen`.
- Contenedor: `recibozen-api`.
- Imagen: `recibozen-api:0.1.0`.
- Red: `recibozen_backend`, separada de `sim_default`.
- Publicación: `127.0.0.1:8787 -> 8080`; no está abierto a LAN, ZeroTier,
  Tailscale ni Internet.
- API pública: `https://api-recibozen.simapp.uk`, únicamente mediante el
  contenedor y túnel `recibozen-tunnel`/`recibozen-api-tunnel`.
- Reinicio: `unless-stopped` para recuperarse después de reiniciar Docker o el
  equipo.
- Límites: 1 CPU, 256 MiB de RAM y 100 procesos.
- Contenedor no root, filesystem de sólo lectura, capabilities `ALL` eliminadas
  y `no-new-privileges` activo.

No se modificaron ni reiniciaron los contenedores `sim-*`, su red, el servicio
`cloudflared-sim`, su túnel `sim-farma-tunnel`, su URL `farma.simapp.uk`, el
firewall, Tailscale o ZeroTier.

Cloudflare termina HTTPS en su borde y `recibozen-tunnel` inicia conexiones
salientes hacia Cloudflare. El router no necesita port forwarding y el API no
tiene un puerto público que permita saltarse Cloudflare. El hostname sí es un
API público: la protección económica sigue dependiendo de validar Play
Integrity, la compra y la cuenta en el backend, no de mantener secreta la URL.

## Comprobaciones

Desde una sesión SSH en el servidor:

```bash
# Los dos archivos compose, siempre: sin compose.billing.yaml el contenedor
# arranca sin la llave de entitlements ni la cuenta de servicio de Play, y
# /readyz lo reporta como playBillingConfigured:false.
cd /home/orangepi/recibozen/backend
docker compose -f compose.yaml -f compose.billing.yaml ps
docker compose logs --tail=100 api
docker compose logs --tail=100 tunnel
curl --fail --silent --show-error http://127.0.0.1:8787/healthz
curl --fail --silent --show-error http://127.0.0.1:8787/readyz
curl --fail --silent --show-error http://127.0.0.1:8787/v1/tariffs/current
curl --fail --silent --show-error http://127.0.0.1:8787/v1/solar/resource/19.4326/-99.1332
curl --fail --silent --show-error https://api-recibozen.simapp.uk/healthz
```

Resultados esperados:

```json
{"status":"ok","service":"recibozen-api","version":"0.1.0"}
{"status":"not_ready","entitlementKeyConfigured":true,"playBillingConfigured":false}
```

Mientras Play Billing no esté configurado, `/readyz` responde HTTP `503` de
forma intencional aunque `/healthz`, tarifas y recurso solar estén operativos.

`POST /v1/entitlements/exchange` debe responder HTTP `503` con
`play_verification_not_configured` hasta completar Google Play. Si comienza a
devolver un lease antes de esa integración, se considera una falla de seguridad.

## Claves de entitlement

- Privada: `secrets/entitlement-private.pem`, modo `600`, sólo en el host.
- Pública: `secrets/entitlement-public.pem`, montada de sólo lectura.
- Algoritmo: ECDSA P-256 con SHA-256.
- `keyId`: `ORgBpJC9J7KYSWc98MWmCN`.
- SHA-256 de la clave pública DER:
  `391801a490bd27b29849673df0c5a608ddc278076541dc525fe3c4ad5a34012c`.

La clave privada necesita un respaldo cifrado fuera de la Orange Pi antes de
publicar la app. No copiarla al repositorio, APK, Play Console, mensajes, logs o
un respaldo sin cifrar. Como aún no se han emitido leases, si no puede hacerse
ese respaldo debe rotarse la clave antes del lanzamiento, no después.

## Pendientes para habilitar premium

1. Cambiar la contraseña inicial del usuario del servidor y configurar acceso
   por clave SSH; comprobar una segunda sesión antes de desactivar contraseñas.
2. Definir cuenta/identidad de ReciboZen. El `subject` del lease debe salir de la
   sesión autenticada, nunca del JSON enviado por la app.
3. Crear productos y suscripciones en Play Console y configurar una cuenta de
   servicio con el mínimo permiso necesario para Google Play Developer API.
4. Añadir Play Billing en Android y ligar `purchaseToken`, cuenta y acción a un
   `requestHash` de Play Integrity.
5. Verificar Integrity y la compra en el servidor, aceptar sólo compras válidas
   y no pendientes, procesar cancelaciones/reembolsos y entonces firmar leases
   breves.
6. Configurar reglas específicas de Cloudflare para el hostname del API
   (protección administrada y rate limiting) cuando el volumen previsto y el
   plan contratado estén definidos. No colocar Cloudflare Access interactivo
   delante del canje móvil ni guardar un service token extraíble dentro del APK.
7. Configurar la huella de Play App Signing en
   `RECIBOZEN_RELEASE_CERT_SHA256` y ejecutar la batería de pruebas de
   `docs/SECURITY_HARDENING.md` mediante un track interno de Google Play.

## Actualización segura

Antes de actualizar, ejecutar las pruebas locales de `backend/`. Transferir sólo
los archivos versionados; `secrets/` permanece en el host. En el servidor:

```bash
cd /home/orangepi/recibozen/backend
node --test
docker compose -f compose.yaml -f compose.billing.yaml config --quiet
docker compose -f compose.yaml -f compose.billing.yaml build api
docker compose -f compose.yaml -f compose.billing.yaml up -d --no-deps api
docker compose -f compose.yaml -f compose.billing.yaml up -d --no-deps tunnel
docker compose -f compose.yaml -f compose.billing.yaml ps
```

El último comando debe mostrar `healthy`. Después se vuelve a comprobar SIM y
`cloudflared-sim` para detectar cualquier interferencia accidental.
