# ReciboZen API

Backend mínimo y aislado para establecer la frontera de confianza premium. En
esta etapa **no concede entitlements**: el canje responde `503` hasta integrar
identidad, Google Play Billing, Play Integrity y Google Play Developer API.

## Endpoints iniciales

- `GET /healthz`: salud del proceso; no expone configuración ni secretos.
- `GET /readyz`: disponibilidad de la clave pública y estado de Billing.
- `GET /.well-known/recibozen-entitlement-key`: ancla pública para configurar
  builds. La app no debe confiar dinámicamente en una clave descargada aquí.
- `POST /v1/entitlements/exchange`: valida la forma del request y falla cerrado
  mientras la verificación real de Google Play no esté configurada.

El servicio nunca registra cuerpos, `purchaseToken` ni `integrityToken`.

## Despliegue Orange Pi

El Compose usa un proyecto, contenedores, imágenes y red propios. El API sólo
publica `127.0.0.1:8787` para diagnóstico local. Un segundo contenedor
`recibozen-tunnel` establece una conexión saliente y cifrada con Cloudflare y
publica exclusivamente `https://api-recibozen.simapp.uk`; no abre puertos del
router ni permite llegar directamente al hardware.

El archivo `secrets/entitlement-private.pem` se crea exclusivamente en el
servidor con modo `600`; no se copia al contenedor actual. El contenedor monta
sólo `entitlement-public.pem`, en modo de solo lectura. El directorio `secrets/`
está ignorado por Git y por el contexto Docker.

Comandos operativos, desde el directorio del backend en el servidor:

```bash
docker compose ps
docker compose logs --tail=100 api
curl --fail --silent --show-error http://127.0.0.1:8787/healthz
```

El túnel, sus credenciales y su hostname son independientes de SIM. No editar
`~/.cloudflared/config.yml` ni `cloudflared-sim.service` al operar ReciboZen.
