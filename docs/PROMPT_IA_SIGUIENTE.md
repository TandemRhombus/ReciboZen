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

### Dos reglas que la sesión anterior ganó a golpes

**1. Cuando escribas una prueba que protege un arreglo, revierte el arreglo y confirma que la prueba falla.** Eso atrapó tres errores: una prueba que exigía lo contrario de lo correcto, un guard que no podía fallar (leía el nodo *fusionado* de una fila en vez del `Text`, así que siempre medía ancho completo), y un experimento de reversión inválido porque se revirtió el layout pero no la cadena de texto que también había cambiado. Un guard que no falla ante su propio bug no es un guard.

**2. Antes de mandar a ejecutar un comando de arreglo, confirma el diagnóstico.** En la misma sesión estuve a punto de pedir un `apt-get install --reinstall ca-certificates` que no habría cambiado absolutamente nada, porque la causa real era otra. Ver la sección de TLS abajo.

## Acceso al servidor (ya configurado, NO necesitas contraseña)

El backend corre en una Orange Pi en la red local. **La autenticación por clave ya está instalada.**

```
host: 192.168.192.242
usuario: orangepi
ruta: /home/orangepi/recibozen/backend
```

**Usa el binario nativo de Windows, no el de Git Bash.** El `ssh` de Git Bash (`/usr/bin/ssh`) **falla** con `Permission denied`:

```bash
/c/Windows/System32/OpenSSH/ssh.exe -o BatchMode=yes orangepi@192.168.192.242 "comando"
```

**Git Bash convierte rutas tipo `/sdcard/...` o `/data/...` en rutas de Windows.** Prefija con `MSYS_NO_PATHCONV=1` cuando pases rutas absolutas de Linux/Android a `adb` o al shell remoto.

Notas del servidor:

- Los contenedores `sim-*` son de **otro proyecto del usuario**. No los toques.
- El backend **siempre** se levanta con los dos archivos compose, o pierde la configuración de billing. Y **siempre con `--build`**: la imagen se construye desde el `Dockerfile` y no monta `src/` como volumen, así que sin reconstruir el contenedor arranca con el código viejo y no lo dice:
  ```bash
  cd /home/orangepi/recibozen/backend && docker compose -f compose.yaml -f compose.billing.yaml up -d --build --no-deps api
  ```
- Los secretos en `backend/secrets/` deben ser `640` y del grupo `10001` (el contenedor corre como UID 10001; con `600` entra en bucle de reinicio). Documentado en `compose.billing.yaml`.
- `sudo` **pide contraseña**. No la necesitas para nada de lo descrito aquí.

## TLS contra CFE: cómo funciona realmente (no lo "arregles")

`app.cfe.mx` **sirve una cadena de certificados incompleta**: manda solo la hoja
(`CN = app.cfe.mx`, emitida por `Entrust EV TLS Issuing RSA CA 2`) y **no manda el
intermedio**. Cualquier cliente que no haga AIA-chasing falla con
`unable to get local issuer certificate` / `UNABLE_TO_VERIFY_LEAF_SIGNATURE`.

Ya está resuelto, deliberadamente, y **no es un bundle de CAs viejo**:

```
NODE_EXTRA_CA_CERTS=/app/certs/cfe-intermediate.pem
```

El contenedor `recibozen-api` lleva el intermedio que a CFE le falta. El archivo vive
en `backend/certs/cfe-intermediate.pem`. `NODE_TLS_REJECT_UNAUTHORIZED` **no** está
puesto, o sea que la verificación de TLS sigue encendida — el apaño es el correcto
(aportar el intermedio faltante), no el peligroso (desactivar la verificación).

**Que falle desde el host es lo esperado**, no un defecto: el host no tiene ese apaño.
Su bundle de CAs está bien y contiene las cinco raíces Entrust. Reinstalar
`ca-certificates` no cambia nada.

**Riesgo operativo que sí vale vigilar:**

| Certificado | Vence |
|---|---|
| Hoja `app.cfe.mx` | **3 dic 2026** |
| Intermedio fijado `Entrust EV TLS Issuing RSA CA 2` | 10 dic 2027 |

Si CFE renueva su hoja bajo un intermedio **distinto**, el `.pem` fijado queda obsoleto
y la sincronización de tarifas se rompe con un error de TLS. La renovación de la hoja
es el próximo momento de riesgo.

## Emulador Android disponible — puedes ver la app tú mismo

Hay SDK completo, imagen android-36, un AVD (`Medium_Phone_API_36.0`) y **WHPX activo**,
así que corre acelerado.

```bash
cd "C:/Users/emili/AppData/Local/Android/Sdk/emulator" && ./emulator.exe -avd Medium_Phone_API_36.0 \
  -no-window -no-audio -no-boot-anim -no-snapshot-save -gpu swiftshader_indirect -port 5554
```

**El teléfono real del usuario suele estar conectado por ADB inalámbrico.** Aísla siempre
con `ANDROID_SERIAL=emulator-5554` para no instalarle nada:

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest
```

Tres tropiezos ya pagados, para que no los repitas:

- `getExternalFilesDir(null)` devuelve **null** en ese AVD. Usa `filesDir` y saca los archivos con `run-as`.
- **Gradle desinstala la app al terminar** `connectedDebugAndroidTest`, y se lleva los archivos con ella. Si necesitas que sobrevivan: `installDebug installDebugAndroidTest` y luego `adb shell am instrument -w -e class <clase> app.recibozen.android.test/androidx.test.runner.AndroidJUnitRunner`.
- Para revisar tamaño de fuente: `adb shell settings put system font_scale 2.0`, y **regrésalo a 1.0 al terminar**.

## Estado verificado el 2026-08-24

| | |
|---|---|
| Paquete | `app.recibozen.android` · versionCode **5** / 1.0.4 |
| Pruebas unitarias Android | **338 · 0 fallos** |
| Pruebas instrumentadas | **7 · 0 fallos** (en emulador) |
| Pruebas backend | **21 · 0 fallos** (`cd backend && node --test`, Node v23.11 local / v22.17 en contenedor) |
| Lint | 0 errores, 79 avisos (66 `PluralsCandidate`, resto versiones y falsos `Typos`) |
| Cadenas huérfanas | **0** |
| Backend | `recibozen-api` healthy · `recibozen-tunnel` up |
| `/readyz` | `{"status":"ready","entitlementKeyConfigured":true,"playBillingConfigured":true}` |
| `POST /v1/entitlements/exchange` con `{}` | `400 invalid_product_id` — **valida, no se niega a existir** |

**No re-verificado** (arrastrado, trátalo como no confirmado): que el bundle release siga
firmado con los 2 certificados de Play App Signing, y todo el estado de Play Console.

```bash
./gradlew testDebugUnitTest assembleDebug lintDebug bundleRelease
ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest
cd backend && node --test
```

---

# LA TAREA: el apartado de IA

## El bloqueo ya se levantó, y está verificado

Durante meses la IA estuvo bloqueada porque el backend respondía
`503 play_verification_not_configured` y **todo el diseño del Worker cuelga de un
entitlement lease que no se podía emitir**. Resuelto:
`POST /v1/entitlements/exchange` con cuerpo vacío responde **`400 invalid_product_id`**
— valida en vez de negarse a existir.

## El diseño está en `docs/PLAN_IA.md`

Léelo completo. Sus 8 capas:

- **Capa 0** — `ANTHROPIC_API_KEY` vive **solo** como secreto del Worker. Nunca en el APK, ni en el repo, ni en Gradle properties.
- **Capa 1** — el Worker valida el mismo lease ECDSA P-256 que ya emite el backend. Sin lease válido → `403`, sin llamar a Anthropic.
- **Capa 2** — el `subject` del lease (firmado por el servidor, no fabricable por el cliente) es el ID de cuota.
- **Capa 3** — cuota mensual por `subject` en un **Durable Object** (no KV: necesita incrementos atómicos).
- **Capa 4** — techo global de gasto en dólares. Al llegar, la IA se apaga para todos.
- **Capa 5** — el cliente **nunca** manda un prompt. Solo intenciones tipadas (`/v1/ai/identify-appliance`, `/v1/ai/explain-bill`). El prompt y el esquema se arman dentro del Worker.
- **Capa 6** — rate limiting por IP y por `subject`.
- **Capa 7** — Play Integrity atado a la emisión del lease.
- **Capa 8** — tokens de entrada/salida guardados en el Durable Object para ver el gasto antes de la factura.

## Correcciones al documento

**1. `PLAN_IA.md` §4 está desactualizado.** Dice que el plan gratuito de Cloudflare no
tiene Durable Objects y que hay que pagar $5/mes. **Falso**: los Durable Objects **sí**
están en el plan gratuito con backend SQLite, que da los incrementos atómicos que el
diseño necesita. 100,000 peticiones/día. **Se construye y prueba todo sin pagar.**

**2. El precio cambió.** El documento calcula con Plus a **$49 MXN**; el usuario decidió
**$69 MXN**. Las tablas de cuotas necesitan recalcularse con el precio real y un tipo de
cambio que él confirme.

Precios verificados de la API (Haiku 4.5, `claude-haiku-4-5`): **$1/MTok entrada,
$5/MTok salida**, lectura de caché ~$0.10/MTok. Los cálculos del documento (≈$0.0014 por
identificar un aparato) se sostienen.

**3. El paso 1 del orden recomendado — la prueba de 30 minutos sobre CFE — YA SE HIZO.**

## La prueba de CFE ya está hecha

| Origen | User-Agent | Resultado |
|---|---|---|
| IP de datacenter | — | **403** |
| Orange Pi · IP residencial MX | `curl` pelado | **200** · 39,675 B |
| Orange Pi · IP residencial MX | Chrome | **200** · 38,496 B |
| Contenedor `recibozen-api` · Node `fetch` | — | **200** · `__VIEWSTATE` presente |

Conclusiones con evidencia:

1. **`app.cfe.mx` está detrás de Imperva** (resuelve a `e85dinu.ng.impervadns.net`).
2. **El discriminante es la IP, no el user-agent.** Un `curl` pelado obtiene 200 desde México. *No es concluyente para Cloudflare* —esa IP no se probó— pero Imperva conoce los rangos de salida de Cloudflare, así que **lo esperable es que el Worker también reciba 403**.
3. **El sitio NO es una SPA.** Es ASP.NET WebForms con postback: `cfe-tariff-source.mjs` lee campos ocultos, hace POST y parsea HTML del servidor. **No hace falta Browser Rendering**, que es de paga y habría tirado la premisa de plan gratuito. Si algún día se migra: el postback necesita continuidad de cookie de sesión entre el GET y el POST.
4. **`robots.txt` permite la ruta de tarifas.** `Allow: /`, y `/Aplicaciones/CCFE/Tarifas/` no está en la lista de `Disallow`.

**Conclusión práctica: el scraper de tarifas se queda en la Orange Pi. El Worker es solo
para la IA.**

## Alcance de la IA, acotado por el usuario

Consejos de ahorro, configuración de equipos, consumo, predicción de costo y alta rápida
de aparatos. **Sin identificación por cámara ni por imagen** — descartado explícitamente.

## La base sobre la que la IA va a escribir — ya existe, no la rompas

**No empieces por el Worker.** El esquema de evidencia está construido y probado.

`core/model/DeviceEvidence.kt` + `core/calculation/EvidenceResolver.kt` (24 pruebas):

- `DeviceEvidence` lleva `value + unit + source + retrievedAt + methodology`. La **unidad la manda la métrica**.
- La **confianza se deriva**, no se declara. Nadie estampa `HIGH` sobre una estimación modelada.
- **`EvidenceSource.AI_SUGGESTION` tiene el rango más bajo de todos.** Una sugerencia de un modelo **nunca** le gana a una etiqueta leída ni a una medición. Hay pruebas que lo fijan.
- **Dos valores que se contradicen NUNCA se promedian.** Gana uno por criterios declarados, el perdedor se conserva y se reporta, y la confianza baja.

Cuando la IA escriba datos de aparatos, entra por ahí, como `AI_SUGGESTION`.

## El orden recomendado (actualizado)

1. ~~Prueba de CFE~~ — **hecha.**
2. **Worker limpio** (auth con lease + cuotas + Durable Objects) en el **plan gratuito**.
3. **Proxy de Haiku** para alta rápida de aparatos, escribiendo como `AI_SUGGESTION`.
4. Explicación en lenguaje natural del recibo.

## Lo que necesitas del usuario

- **Account ID de Cloudflare** y que **él** corra `npm i -g wrangler && wrangler login`. **No le pidas un API token** — tú escribes código y comandos, él los ejecuta.
- Que **él** cree la API key de Anthropic y la ponga con `wrangler secret put ANTHROPIC_API_KEY`. **No le pidas la llave por chat**; quedaría en el historial y habría que rotarla.
- Su **techo mensual de gasto en USD**.
- Subdominio propuesto: `ia-recibozen.simapp.uk` (ya tiene `simapp.uk` en Cloudflare, con el túnel `recibozen-tunnel` sirviendo `api-recibozen.simapp.uk`).

---

# Contexto de lo hecho recientemente (para que no lo rompas)

## Ocho commits en `IA-Implement`

```
198a309  fix: three things only visible with the screens rendered at a large font size
0f62352  fix: make the layouts survive a large system font size
34ce094  feat: let the household record that CFE replaced the meter
dfc4f7b  fix: make the DAC period range show a range and a marker that can move
2278761  fix: make three bar charts show data instead of decoration
d955146  feat: add a nightly reminder to unplug what the house leaves in standby
c3c79f5  feat: show the DAC ceiling as a daily allowance
c3c358f  feat: resolve meter rollover with the household's own testimony
```

## APIs nuevas que tu código tiene que respetar

**`MeterDelta.consumption(...)` cambió de firma.** Ahora acepta `attestedWraps`,
`plausibleDailyKwh` y `replacement`. Es el único lugar que convierte dos lecturas en
consumo.

> **Si agregas cualquier cálculo que cruce un tramo de lecturas, tiene que pasar por
> `MeterDelta` y usar `ReadingInterval.replacementForDelta(...)`.** Un tramo ambiguo debe
> llegar como reemplazo **incompleto** para que `MeterDelta` se niegue; si llega como
> `null`, restaría una lectura de un registro contra la de otro medidor físico distinto.

| Archivo nuevo | Qué resuelve |
|---|---|
| `core/calculation/ReadingInterval.kt` | intervalo real entre lecturas; suma de vueltas atestiguadas; detección de reemplazo en un tramo |
| `core/calculation/AccumulatedConsumption.kt` | curva de acumulado del periodo (salió del ViewModel para poder probarse) |
| `core/calculation/DacDailyBudget.kt` | techo del periodo convertido en ritmo diario |
| `core/calculation/NightUnplugAdvice.kt` | qué aparatos nombrar en el recordatorio nocturno |
| `core/notifications/NightUnplugReminder.kt` | ajustes + agenda por hora + worker |
| `core/designsystem/FontScale.kt` | `rzPrefersStackedLayout()` — umbral 1.3 para apilar layouts |
| `core/components/RzAvatar.kt` | el círculo de la inicial, unificado y con crecimiento acotado |

También `MeterRegister(capacityKwh, multiplier)` en `MeterProfile.kt` — los dos viajan
juntos **a propósito**, porque el multiplicador se omitió en todos los sitios de lectura
durante toda la vida del código por ser un argumento opcional con default de 1.

Y `readStoredSession(context)` en `PersistentReciboZenRepository.kt` — para trabajo en
segundo plano que necesita el inventario. **No abras un segundo DataStore sobre el mismo
archivo, revienta.**

## Decisiones que NO son pendientes

**`Paywall.ENFORCED = false` es deliberado** (`core/model/Paywall.kt:28`). Razones:

1. Play exige atestiguar que las credenciales de prueba dan acceso completo "incluido contenido premium". Con el candado puesto y sin forma de darle una compra al revisor, esa casilla sería una declaración falsa — causa de retiro.
2. Ningún plan base está activo, así que **nadie puede comprar**.

Un flag gobierna las tres compuertas (recordatorios de lectura, recordatorio nocturno,
múltiples suministros). Las pruebas cubren **ambos estados**. **Reactívalo en la misma
versión que active los planes base**, no antes.

**`Type.kt` usa `FontFamily.Default` a propósito.** No lo "arregles" empaquetando una
fuente. La consecuencia —que cada layout sobreviva una escala grande— ya está atendida.

## La lectura hacia atrás quedó cerrada

| Situación | Cómo se resuelve |
|---|---|
| El registro dio la vuelta | atestación con candidatos calculados |
| Varias vueltas | mismo camino, hasta 3 |
| Capturaste mal | manda a corregir la lectura |
| No se sabe cuántos dígitos tiene | se declara en Perfil u onboarding |
| Cambiaron el medidor | se registra, con o sin las lecturas frontera |

Y **el multiplicador de CFE ya se aplica a las lecturas** (el recibo importado gana sobre
el declarado).

## Pendientes reales

**Cerrados:** la pregunta de la vuelta, el multiplicador, el conteo de dígitos declarado,
el cambio de medidor, y la escala de fuente grande.

Quedan:

1. **Escala de fuente: la auditoría no fue exhaustiva.** Se revisaron visualmente Inicio, Perfil, onboarding, confirmación de lectura, DAC y Notificaciones. **No se revisaron** Historial, Aparatos, Solar, Análisis del periodo, Balance ni Premium. El emulador está listo.
2. **Cuatro arreglos sin confirmar en dispositivo** *(arrastrados, no verificados)*: paso 3 del tutorial (DAC), la tarjeta bajo la barra de navegación, el tutorial de Configuración del 1 al 7, y el botón Inicio de la barra inferior.
3. **Dos defectos vistos en capturas** *(arrastrados, no verificados)*: el botón flotante "Registrar lectura" tapa contenido en Inicio e Historial, y en Aparatos aparece **"Toda la casa" dos veces** como chip.
4. **Tabla `appliance_references` sin usar**: aliméntala o quítala. *Candidata natural para la IA* — sería el destino de las sugerencias `AI_SUGGESTION`.
5. **Play**: subir el bundle v5, completar Contenido de la aplicación, y correr la **prueba cerrada con 12 testers durante 14 días** (la interna **no cuenta** para producción).
6. **Vigilar la renovación del certificado de `app.cfe.mx`** (vence 3 dic 2026), por lo del intermedio fijado.

## Mapa de archivos

```
app/src/main/java/app/recibozen/android/
  core/calculation/   DacRisk.kt · DacDailyBudget.kt · MeterDelta.kt · MeterProfile.kt
                      ReadingInterval.kt · AccumulatedConsumption.kt · EvidenceResolver.kt
                      NightUnplugAdvice.kt · TariffEngine.kt · ConsumptionPace.kt
                      OvernightBaseline.kt · SolarEstimator.kt
  core/model/         DeviceEvidence.kt · Readings.kt (HistoryReadingEntry, MeterReplacement)
                      Paywall.kt · ElectricService.kt · Appliance.kt
  core/notifications/ ReadingReminder.kt · NightUnplugReminder.kt
  core/designsystem/  Type.kt · Spacing.kt · FontScale.kt
  core/components/    RzButtons.kt · RzProgress.kt · RzAvatar.kt · RzCharts.kt
  core/security/      EntitlementLeaseVerifier.kt · PremiumEntitlementAuthority.kt
  core/ocr/           MeterReadingParser.kt
  feature/plan/       DacScreen.kt · DacGlance.kt · BalanceScreen.kt · PlanScreen.kt
  feature/premium/    PremiumScreen.kt   ← aquí vive el BillingClient
  feature/readings/   ReadingConfirmScreen.kt · ReadingDialScreen.kt · ReadingPhotoScreen.kt
  demo/repository/    DemoReciboZenRepository.kt        ← energySummary vive aquí
  data/repository/    PersistentReciboZenRepository.kt  ← el repositorio real

app/src/androidTest/  FontScaleTest.kt · ProfileRowFontScaleTest.kt

backend/src/          server.mjs · google-play.mjs · entitlement-lease.mjs
                      cfe-tariff-source.mjs · tariff-catalog.mjs · solar-resource.mjs
backend/certs/        cfe-intermediate.pem   ← el intermedio que a CFE le falta
backend/              compose.yaml + compose.billing.yaml   ← siempre los dos

docs/                 PLAN_IA.md · BACKEND_OPERATIONS.md · COMO_PUBLICAR.md
                      SECURITY_HARDENING.md · REFERENCE_DATA_FORMAT.md
```

## Un pendiente de seguridad

**La contraseña de `orangepi` sigue siendo la de fábrica.** En esa máquina vive la clave
privada que firma los entitlement leases y la cuenta de servicio de Google Play. Punto 1
de `docs/BACKEND_OPERATIONS.md`, ahí desde agosto. El acceso por clave ya está puesto, así
que cambiarla no rompe nada. El usuario ya está enterado y ha decidido no cambiarla por
ahora — menciónalo una vez y no insistas.

---

**Empieza leyendo `docs/PLAN_IA.md` completo, verifica el backend, y ten presente que la
prueba de CFE ya está hecha: el scraper se queda donde está y el Worker es solo para la
IA.**
