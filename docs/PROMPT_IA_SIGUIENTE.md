# Prompt de continuación — ReciboZen · apartado de IA

> Copia todo lo que sigue y pégalo como primer mensaje en la sesión nueva.

---

Vas a continuar el desarrollo de **ReciboZen**, una app Android nativa (Jetpack Compose, Kotlin) para consumidores de CFE en México, más su backend en Node.js. El proyecto está en `C:\Users\emili\AndroidStudioProjects\ReciboZen`, rama **`IA-Implement`**, repo privado `github.com/TandemRhombus/ReciboZen`.

## Cómo quiero que trabajes

- **No inventes nada.** Ni tarifas, ni consumos, ni resultados, ni estados. Si no tienes el dato, dilo.
- **No me des la razón por default.** Si algo mío está mal, corrígeme con evidencia del código.
- **Nada se reporta como hecho sin compilador.** "Debería funcionar" no cuenta.
- **Reporta tus propios errores** antes de que los encuentre yo.
- **No hagas commit ni push sin que yo lo autorice.** Cuando lo autorice: conventional commits, sin `Co-Authored-By` ni atribución a IA.
- **No ajustes una prueba para que pase.** Primero determina cuál debería ser el resultado correcto.
- Respóndeme en **español**, conciso.

## Acceso al servidor (ya configurado, NO necesitas contraseña)

El backend corre en una Orange Pi en la red local. **La autenticación por clave ya está instalada**, así que puedes ejecutar comandos directamente.

```
host: 192.168.192.242
usuario: orangepi
ruta: /home/orangepi/recibozen/backend
```

**IMPORTANTE — usa el binario nativo de Windows, no el de Git Bash.** El `ssh` de Git Bash (`/usr/bin/ssh`) **falla** con `Permission denied`; el de Windows funciona con la clave. Siempre así:

```bash
/c/Windows/System32/OpenSSH/ssh.exe -o BatchMode=yes orangepi@192.168.192.242 "comando"
```

Verifica que tienes acceso antes de nada:

```bash
/c/Windows/System32/OpenSSH/ssh.exe -o BatchMode=yes orangepi@192.168.192.242 "docker ps --format '{{.Names}}: {{.Status}}'"
```

Otras notas del servidor:
- Los contenedores `sim-*` son de **otro proyecto del usuario**. No los toques.
- El backend **siempre** se levanta con los dos archivos compose, o pierde la configuración de billing:
  ```bash
  cd /home/orangepi/recibozen/backend && docker compose -f compose.yaml -f compose.billing.yaml up -d --no-deps api
  ```
- Los secretos en `backend/secrets/` deben ser `640` y del grupo `10001` (el contenedor corre como UID 10001; con `600` no los puede leer y entra en bucle de reinicio). Está documentado en `compose.billing.yaml`.

## Estado verificado hoy

| | |
|---|---|
| Paquete | `app.recibozen.android` · versionCode **5** / 1.0.4 |
| Pruebas unitarias Android | **268 · 0 fallos** |
| Pruebas backend | **21 · 0 fallos** (`cd backend && node --test`) |
| Lint | 0 errores, 72 avisos (59 `PluralsCandidate`, resto versiones y falsos `Typos`) |
| Cadenas de texto huérfanas | **0** |
| Bundle release | firmado (`jar verified`), con los 2 certificados de Play App Signing |
| Backend | `/readyz` → `{"status":"ready","playBillingConfigured":true}` |

Comandos de verificación:

```bash
./gradlew testDebugUnitTest assembleDebug lintDebug bundleRelease
cd backend && node --test
```

---

# LA TAREA: el apartado de IA

## Lo primero: el bloqueo ya se levantó

Durante meses la IA estuvo bloqueada porque el backend respondía `503 play_verification_not_configured` y **todo el diseño del Worker cuelga de un entitlement lease que no se podía emitir**. Eso **ya está resuelto**: Billing quedó configurado y verificado de punta a punta. `POST /v1/entitlements/exchange` ahora responde `400` a una petición vacía en vez de `503`, que es la prueba de que valida en lugar de negarse a existir.

## El diseño está en `docs/PLAN_IA.md`

Léelo completo. Resumen de sus 8 capas de blindaje:

- **Capa 0** — `ANTHROPIC_API_KEY` vive **solo** como secreto del Worker. Nunca en el APK, ni en el repo, ni en Gradle properties.
- **Capa 1** — el Worker valida el mismo lease ECDSA P-256 que ya emite el backend. Sin lease válido → `403`, sin llamar a Anthropic.
- **Capa 2** — el `subject` del lease (firmado por el servidor, no fabricable por el cliente) es el ID de cuota.
- **Capa 3** — cuota mensual por `subject` en un **Durable Object** (no KV: necesita incrementos atómicos).
- **Capa 4** — techo global de gasto en dólares. Al llegar, la IA se apaga para todos.
- **Capa 5** — el cliente **nunca** manda un prompt. Solo intenciones tipadas (`/v1/ai/identify-appliance`, `/v1/ai/explain-bill`). El prompt y el esquema se arman dentro del Worker.
- **Capa 6** — rate limiting por IP y por `subject`.
- **Capa 7** — Play Integrity atado a la emisión del lease.
- **Capa 8** — tokens de entrada/salida guardados en el Durable Object para ver el gasto antes de la factura.

## ⚠️ Dos correcciones a ese documento

**1. `PLAN_IA.md` §4 está desactualizado.** Dice que el plan gratuito de Cloudflare no tiene Durable Objects y que por eso hay que pagar $5/mes. **Falso**: los Durable Objects **sí** están en el plan gratuito con backend SQLite, que da los incrementos atómicos que el diseño necesita. El plan gratuito da 100,000 peticiones/día. **Se puede construir y probar todo sin pagar.**

**2. El precio cambió.** El documento calcula el modelo de negocio con Plus a **$49 MXN**; el usuario decidió **$69 MXN**. Las tablas de cuotas necesitan recalcularse con el precio real y un tipo de cambio que él confirme.

Precios verificados de la API (Haiku 4.5, `claude-haiku-4-5`): **$1/MTok entrada, $5/MTok salida**, lectura de caché ~$0.10/MTok. Los cálculos del documento (≈$0.0014 por identificar un aparato) se sostienen.

## Alcance de la IA, ya acotado por el usuario

Consejos de ahorro, configuración de equipos, consumo, predicción de costo y alta rápida de aparatos. **Sin identificación por cámara ni por imagen** — el usuario descartó ese alcance explícitamente.

## La base sobre la que la IA va a escribir — ya existe

Esto es importante: **no empieces por el Worker.** El esquema de evidencia ya está construido y probado, y define las reglas que la IA tiene que respetar.

`core/model/DeviceEvidence.kt` + `core/calculation/EvidenceResolver.kt` (24 pruebas en `DeviceEvidenceTest.kt`):

- `DeviceEvidence` lleva `value + unit + source + retrievedAt + methodology`. La **unidad la manda la métrica**, no es un campo suelto que pueda contradecirla.
- La **confianza se deriva**, no se declara. Nadie puede estampar `HIGH` sobre una estimación modelada.
- **`EvidenceSource.AI_SUGGESTION` tiene el rango más bajo de todos.** Una sugerencia de un modelo **nunca** le gana a una etiqueta que alguien leyó ni a una medición. Esto es deliberado y hay pruebas que lo fijan.
- **Regla de conflicto: dos valores que se contradicen NUNCA se promedian.** Un promedio es un número que nadie afirmó con la autoridad de los dos. Gana uno por criterios declarados, el perdedor se conserva y se reporta, y la confianza baja.

Cuando la IA escriba datos de aparatos, tiene que entrar por ahí, como `AI_SUGGESTION`.

## El orden que recomiendo

1. **Prueba de 30 minutos:** un Worker desechable que pida las páginas de tarifas de CFE (`https://app.cfe.mx/Aplicaciones/CCFE/Tarifas/TarifasCREHogar/Tarifas`). Muchos sitios de gobierno mexicanos bloquean IPs de datacenter. Desde la Orange Pi sale IP residencial mexicana; desde Cloudflare no. **Esa respuesta decide si el scraper de tarifas puede migrar o se queda en la Orange Pi para siempre.**
2. **Worker limpio para lo nuevo** (auth con lease + cuotas + Durable Objects), en el **plan gratuito**. Cero riesgo para lo que ya funciona.
3. **Proxy de Haiku** para identificar aparato, escribiendo como `AI_SUGGESTION`.
4. Explicación en lenguaje natural del recibo.

## Lo que necesitas del usuario

- **Account ID de Cloudflare** y que **él** corra `npm i -g wrangler && wrangler login`. **No le pidas un API token de Cloudflare** — tú escribes el código y los comandos, él los ejecuta.
- Que **él** cree la API key de Anthropic y la ponga con `wrangler secret put ANTHROPIC_API_KEY`. **No le pidas la llave por chat**; quedaría en el historial y habría que rotarla.
- Su **techo mensual de gasto en USD**.
- Subdominio propuesto: `ia-recibozen.simapp.uk` (ya tiene `simapp.uk` en Cloudflare, con el túnel `recibozen-tunnel` sirviendo `api-recibozen.simapp.uk`).

---

# Contexto de lo hecho recientemente (para que no lo rompas)

## Google Play — dónde está parado

Cuenta creada, app registrada (`app.recibozen.android`), verificación de desarrollador con 4 huellas verificadas, política de privacidad publicada en `https://tandemrhombus.github.io/recibozen-legal/`, suscripciones `recibozen_plus_monthly` y `recibozen_family_monthly` creadas.

**La app fue rechazada una vez** por la política de afirmaciones engañosas: mostraba información gubernamental (tarifas CFE, límites DAC) sin enlace a la fuente original. Ya está corregido en la ficha y **dentro de la app** (`LegalScreen` y el desplegable del DAC ahora enlazan a la publicación oficial de CFE).

**Falta:** subir el bundle v5, completar Contenido de la aplicación, y correr la **prueba cerrada con 12 testers durante 14 días** (la interna **no cuenta** para producción).

## El candado de Plus está levantado a propósito

`core/model/Paywall.kt` → `ENFORCED = false`. **No es un pendiente, es una decisión.** Razones:

1. Play exige, en la declaración de acceso, atestiguar que las credenciales dan acceso completo "incluido contenido premium". Con el candado puesto y sin forma de darle una compra al revisor, esa casilla sería una declaración falsa — y eso es causa de retiro.
2. Ningún plan base está activo, así que **nadie puede comprar**. El candado quitaba dos funciones a todo el mundo sin recaudar nada.

Un solo flag gobierna las dos compuertas (recordatorios de lectura y múltiples suministros). Las pruebas cubren **ambos estados**, así que reactivarlo es un cambio de una línea que llega probado.

**Reactívalo en la misma versión que active los planes base.** No antes: la pantalla Premium ofrecería cobrar por funciones que ya son gratis.

## Trabajo reciente en el medidor (no lo deshagas)

`core/calculation/MeterDelta.kt` (20 pruebas) reemplazó la resta cruda que había en tres lugares. Reporta consumo **o** reporta que no puede saberlo, nunca algo intermedio:

- El desbordamiento del registro (medidor de 4 carátulas que pasa de 9999) producía consumo **negativo**, que la pantalla mostraba como medición.
- El desbordamiento que aterriza **arriba** de la lectura anterior es invisible: parece una lectura normal pero está subestimada por una capacidad entera.
- El **multiplicador de CFE** no se aplica a lecturas, aunque la importación de recibos sí lo aplica (`BillImportScreen.kt:299`). **Esto sigue sin arreglar** y afecta a servicios con transformador de corriente.

`core/calculation/MeterProfile.kt` reemplazó tres probes `meterType.contains("4")` sobre una etiqueta en español.

`core/ocr/MeterReadingParser.kt` devolvía el código `SIC DGE 4348` de la placa del medidor como si fuera la lectura. Ahora descarta líneas de placa y responde `null` si hay ambigüedad.

## Pendientes reales, en orden

1. **Nadie pregunta al usuario** cuál de las cuatro situaciones ocurrió cuando una lectura va hacia atrás. La app enuncia la ambigüedad honestamente pero no la resuelve. Requiere UI nueva y guardar el conteo de dígitos y el multiplicador como datos declarados.
2. **El multiplicador de CFE** sin aplicar a lecturas.
3. **Tipografía**: `Type.kt` usa `FontFamily.Default` **a propósito** (decisión documentada: respeta la fuente y el tamaño que el usuario eligió en su teléfono). La consecuencia es que **cada layout debe sobrevivir una escala de fuente grande**, y eso nunca se probó.
4. **Cuatro arreglos sin confirmar en dispositivo**: paso 3 del tutorial (DAC), la tarjeta bajo la barra de navegación, el tutorial de Configuración del 1 al 7, y el botón Inicio de la barra inferior.
5. **Dos defectos vistos en capturas**: el botón flotante "Registrar lectura" tapa contenido en Inicio e Historial, y en Aparatos aparece **"Toda la casa" dos veces** como chip.
6. Tabla `appliance_references` sin usar: aliméntala o quítala.

## Mapa de archivos

```
app/src/main/java/app/recibozen/android/
  core/calculation/   DacRisk.kt · MeterDelta.kt · MeterProfile.kt · EvidenceResolver.kt
                      TariffEngine.kt · ConsumptionPace.kt · OvernightBaseline.kt
  core/model/         DeviceEvidence.kt · Paywall.kt · ElectricService.kt · Appliance.kt
  core/security/      EntitlementLeaseVerifier.kt · PremiumEntitlementAuthority.kt
                      AppInstallTrustChecker.kt · PremiumAccessStore.kt
  core/ocr/           MeterReadingParser.kt
  feature/plan/       DacScreen.kt · DacGlance.kt · BalanceScreen.kt · PlanScreen.kt
  feature/premium/    PremiumScreen.kt   ← aquí vive el BillingClient
  feature/readings/   ReadingConfirmScreen.kt · ReadingDialScreen.kt · ReadingPhotoScreen.kt
  demo/repository/    DemoReciboZenRepository.kt   ← energySummary vive aquí
  data/repository/    PersistentReciboZenRepository.kt   ← el repositorio real

backend/src/          server.mjs · google-play.mjs · entitlement-lease.mjs
                      cfe-tariff-source.mjs · tariff-catalog.mjs · solar-resource.mjs
backend/              compose.yaml + compose.billing.yaml   ← siempre los dos

docs/                 PLAN_IA.md · BACKEND_OPERATIONS.md · COMO_PUBLICAR.md
                      SECURITY_HARDENING.md · REFERENCE_DATA_FORMAT.md
play_assets/          ícono, gráfico destacado, 4 capturas, logos, hoja de estilo
```

## Un pendiente de seguridad

La contraseña del usuario `orangepi` **sigue siendo la de fábrica**. En esa máquina vive la clave privada que firma los entitlement leases y la cuenta de servicio de Google Play. Es el punto 1 de `docs/BACKEND_OPERATIONS.md` y lleva ahí desde agosto. Recuérdaselo; el acceso por clave ya está puesto, así que cambiarla no rompe nada.

---

**Empieza leyendo `docs/PLAN_IA.md` completo, y verifica el estado del backend antes de proponer nada.**
