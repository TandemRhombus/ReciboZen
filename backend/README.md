# ReciboZen API

Backend aislado para establecer la frontera de confianza premium. El canje
sigue fallando cerrado con `503` hasta que el servidor tenga todas las
credenciales, pero ya puede verificar una suscripción real con Google Play,
validar Play Integrity, reconocer el producto y emitir un lease corto firmado.

## Endpoints iniciales

- `GET /healthz`: salud del proceso; no expone configuración ni secretos.
- `GET /readyz`: disponibilidad de la clave pública y estado de Billing.
- `GET /.well-known/recibozen-entitlement-key`: ancla pública para configurar
  builds. La app no debe confiar dinámicamente en una clave descargada aquí.
- `POST /v1/entitlements/exchange`: vincula `subject`, producto y token de compra
  al `requestHash` de Play Integrity; consulta `subscriptionsv2`, comprueba
  vigencia y cuenta ofuscada, reconoce el pago si hace falta y devuelve un lease
  de máximo 24 horas firmado con ECDSA P-256.
- `GET /v1/tariffs/current`: catálogo mensual normalizado desde las páginas
  oficiales de CFE. Conserva el último catálogo válido en el volumen
  `recibozen_tariff_data` y reintenta la actualización diariamente.
- `GET /v1/solar/resource/{latitud}/{longitud}`: promedios mensuales de
  irradiación `ALLSKY_SFC_SW_DWN` obtenidos de NASA POWER para el preestudio.

El servicio nunca registra cuerpos, `purchaseToken`, `integrityToken` ni las
coordenadas del recurso solar; esa ruta se anonimiza en los logs.

## Despliegue Orange Pi

El Compose usa un proyecto, contenedores, imágenes y red propios. El API sólo
publica `127.0.0.1:8787` para diagnóstico local. Un segundo contenedor
`recibozen-tunnel` establece una conexión saliente y cifrada con Cloudflare y
publica exclusivamente `https://api-recibozen.simapp.uk`; no abre puertos del
router ni permite llegar directamente al hardware.

Los archivos `secrets/entitlement-private.pem` y
`secrets/google-play-service-account.json` existen exclusivamente en el
servidor y se montan en modo de solo lectura mediante `compose.billing.yaml`.
Nunca se copian a la imagen. El directorio `secrets/` está ignorado por Git y
por el contexto Docker.

## Activar la verificación de Google Play

1. Crear en Play Console las suscripciones `recibozen_plus_monthly` y
   `recibozen_family_monthly`.
2. Vincular el proyecto de Google Cloud, habilitar Android Publisher API y Play
   Integrity API, y dar a la cuenta de servicio sólo los permisos necesarios.
3. Guardar su JSON como `secrets/google-play-service-account.json` y la clave
   ECDSA como `secrets/entitlement-private.pem`, legibles por el UID 10001 del
   contenedor pero no por otros usuarios.
4. Definir `PLAY_CERTIFICATE_SHA256_DIGESTS` con los digest base64url que devuelve
   Play Integrity para la firma de producción.
5. Iniciar con:

```bash
docker compose -f compose.yaml -f compose.billing.yaml up -d --build
```

`GET /readyz` sólo responde 200 cuando están disponibles la clave pública, la
clave privada, la cuenta de servicio, el mapa de productos y al menos un digest
de certificado. Nunca colocar estos secretos en `.env`, Gradle o el APK.

Comandos operativos, desde el directorio del backend en el servidor:

```bash
docker compose ps
docker compose logs --tail=100 api
curl --fail --silent --show-error http://127.0.0.1:8787/healthz
```

El túnel, sus credenciales y su hostname son independientes de SIM. No editar
`~/.cloudflared/config.yml` ni `cloudflared-sim.service` al operar ReciboZen.
