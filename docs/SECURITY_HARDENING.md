# Blindaje de ReciboZen contra manipulación y fraude premium

Estado: capa cliente y backend base aplicados; integración Google Play/identidad pendiente por credenciales externas  
Fecha: 11 de agosto de 2026

## Qué protege este parche

1. **El nivel premium ya no es estado local confiable.** `entitlementTier` no se serializa y cualquier valor `PLUS` o `FAMILY` inyectado en el JSON anterior se ignora al hidratar la app.
2. **El release se ofusca y optimiza con R8.** Se eliminan código y recursos sin uso, se renombran clases/miembros y se reescriben nombres de archivo fuente. Conservar en privado `app/build/outputs/mapping/release/mapping.txt` por cada versión publicada para reconstruir trazas.
3. **El premium falla cerrado.** Sin certificado configurado, instalación oficial y lease válido firmado por el servidor, el nivel efectivo es `FREE`; la app básica sigue funcionando.
4. **Detección local de reempaquetado.** El APK compara su certificado SHA-256 con la lista de certificados de firma autorizados y, en release, exige instalación desde Google Play y ausencia de depurador antes de aceptar premium.
5. **Lease premium firmado.** El verificador acepta únicamente leases ECDSA P-256/SHA-256 de hasta ocho días, ligados a `applicationId`, cuenta, versión mínima, vigencia e ID único. Cambiar `PLUS` por `FAMILY`, copiar el lease a otra cuenta o extender su fecha invalida la verificación.
6. **Menor exposición local.** Backup, restauración y transferencia de datos están desactivados y excluidos explícitamente. Todo tráfico HTTP claro está bloqueado.

Estas capas elevan mucho el costo de Lucky Patcher y del reempaquetado casual. No convierten un APK en una frontera de seguridad: alguien con control total del dispositivo puede modificar cualquier condición cliente. Las decisiones con valor económico deben confirmarse en el backend.

## Configuración obligatoria antes de publicar

### 1. Huella de firma instalada

Después de activar **Play App Signing**, copiar la huella SHA-256 de **App signing key certificate**, no la del certificado de subida:

```properties
RECIBOZEN_RELEASE_CERT_SHA256=AA:BB:CC:...:FF
```

Puede colocarse en `~/.gradle/gradle.properties` o como secreto del CI. Para una rotación de clave se admiten huellas separadas por coma. No guardar contraseñas, `.jks` ni claves privadas en el repositorio.

Si esta propiedad falta, `assembleRelease` compila para poder probar R8, pero cualquier entitlement premium se rechaza.

### 2. Clave pública del servidor

El backend conserva una clave privada ECDSA P-256 en su gestor de secretos y publica únicamente la clave X.509 DER codificada en Base64:

```properties
RECIBOZEN_ENTITLEMENT_PUBLIC_KEY_B64=MFkwEwYHKoZIzj0CAQYI...
```

La clave pública de la instancia provisional ya está fijada en `gradle.properties`. La clave privada jamás entra en Android, Gradle, Play Console ni el repositorio. La rotación debe contemplar temporalmente más de una clave pública o un `keyId` en la siguiente versión del esquema.

## Backend base desplegado

El código vive en `backend/` y su operación está descrita en
`docs/BACKEND_OPERATIONS.md`. El servicio publica salud, readiness y los
metadatos de la clave pública. El endpoint de canje valida únicamente la forma
del request y responde `503` de manera intencional: todavía no emite leases ni
acepta un nivel premium indicado por el cliente.

La instancia Docker se ejecuta como UID/GID `10001`, con filesystem de sólo
lectura, todas las capabilities eliminadas, `no-new-privileges`, límites de CPU,
memoria y procesos, red Compose independiente y publicación exclusiva en
`127.0.0.1`. Sólo monta la clave pública. La clave privada P-256 tiene modo
`600` en el host y no está presente dentro del contenedor.

El acceso móvil usa `https://api-recibozen.simapp.uk` mediante un Cloudflare
Tunnel exclusivo. `cloudflared` comparte únicamente la red Docker de ReciboZen
y llega a `api:8080`; no se publicaron 80/443, rangos privados ni servicios de
SIM. La URL no se considera un secreto. Cloudflare protege y oculta el origen,
pero la autorización premium continúa validándose criptográficamente en el API.

## Contrato del lease

Formato firmado exacto:

```text
base64url(payload_utf8).base64url(firma_der_ecdsa)
```

Payload canónico:

```text
1|app.recibozen.android|subject|PLUS|issuedAt|expiresAt|minVersionCode|tokenId
```

- `subject`: hash/ID opaco de la cuenta autenticada; debe coincidir con la sesión. El valor esperado debe provenir de la identidad autenticada del backend, nunca del propio token o de preferencias locales.
- `tier`: solo `PLUS` o `FAMILY`.
- `issuedAt` y `expiresAt`: segundos Unix; vigencia máxima de ocho días y tolerancia de reloj de cinco minutos.
- `minVersionCode`: obliga a actualizar cuando una versión vulnerable debe retirarse.
- `tokenId`: identificador único registrado por el backend para auditoría/revocación.

El token puede almacenarse en DataStore porque no contiene un secreto y su firma impide editarlo. La cuenta, vigencia, reembolsos y revocaciones siguen siendo autoridad del servidor.

## Flujo requerido al conectar Billing

```text
Play Billing → purchaseToken
      ↓
App → Play Integrity standard token (requestHash ligado a compra/cuenta/acción)
      ↓
Backend verifica Play Integrity + purchaseToken con Google Play Developer API
      ↓
Backend registra/acknowledges la compra y emite lease firmado de corta duración
      ↓
App verifica certificado/instalador/depurador + firma/audiencia/cuenta/vigencia
      ↓
PLUS o FAMILY
```

El backend debe:

- Aceptar solo `PLAY_RECOGNIZED` y validar que paquete, certificado, versión y `requestHash` sean los esperados.
- Consultar `Purchases.subscriptionsv2:get` o el endpoint correspondiente; no confiar en `Purchase` recibido del cliente.
- Otorgar acceso únicamente a estado `PURCHASED`, nunca `PENDING`.
- Usar `purchaseToken` como identificador único, vincularlo a la cuenta y procesar renovaciones, cancelaciones, pausas y reembolsos mediante notificaciones en tiempo real.
- Confirmar la compra desde servidor después de conceder el entitlement.
- Aplicar también los límites de suministros y toda operación premium en servidor. Una comprobación únicamente en Compose/Room siempre puede eliminarse de un APK parcheado.

## Controles que no se añadieron deliberadamente

- **Buscar “Lucky Patcher” por nombre de paquete:** se evade renombrándolo, requiere visibilidad de apps y produce falsos positivos.
- **Bloquear todo dispositivo rooteado:** también es evadible, castiga usuarios legítimos y no sustituye Play Integrity.
- **Guardar un secreto HMAC dentro del APK:** cualquier secreto cliente puede extraerse y permitiría fabricar licencias.
- **Certificate pinning sin dominio/backend definitivo:** una rotación mal operada puede dejar a todos los usuarios sin servicio. Se exige TLS del sistema; al definir backend se evaluará Certificate Transparency y una política de rotación segura.
- **Cerrar la app completa por una señal local:** las señales pueden fallar. El producto básico continúa; las acciones económicas fallan cerrado y el servidor decide la remediación.

## Verificación y operación por versión

1. Ejecutar `testDebugUnitTest`, `lintDebug`, `assembleRelease` y luego pruebas internas mediante Google Play, no solo por ADB.
2. Confirmar que el APK/AAB instalado desde Play reporta la huella configurada y el instalador `com.android.vending`.
3. Probar token válido, vencido, alterado, copiado a otra cuenta, pendiente, cancelado y reembolsado.
4. Probar instalación re-firmada: debe conservar Gratis y rechazar premium.
5. Archivar el AAB, `mapping.txt`, símbolos nativos y configuración pública usada en cada `versionCode`.
6. No registrar purchase tokens, leases, datos del recibo ni veredictos completos en `logcat` de release.

## Archivos principales

- `app/build.gradle.kts`: R8, flags de seguridad y material público de verificación.
- `app/proguard-rules.pro`: reglas estrechas de ofuscación.
- `core/security/AppInstallTrustChecker.kt`: certificado, origen de instalación y depurador.
- `core/security/EntitlementLeaseVerifier.kt`: verificación criptográfica pura.
- `core/security/PremiumEntitlementAuthority.kt`: frontera única de confianza premium.
- `data/repository/PersistentReciboZenRepository.kt`: hidratación fail-closed; nunca restaura premium del JSON local.
- `res/xml/network_security_config.xml`: prohibición de HTTP claro.
- `res/xml/data_extraction_rules.xml` y `backup_rules.xml`: exclusión de datos.
