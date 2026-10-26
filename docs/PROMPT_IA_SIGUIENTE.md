# Prompt de continuación — ReciboZen

> Copia todo lo que sigue y pégalo como primer mensaje en la sesión nueva.

---

Vas a continuar el desarrollo de **ReciboZen**: app Android nativa (Jetpack Compose, Kotlin) para consumidores de CFE en México, un backend Node.js en una Orange Pi, y un Worker de Cloudflare que sirve las funciones de IA.

Rama de trabajo: **`IA-Implement`**. Repo privado `github.com/TandemRhombus/ReciboZen`.

## Cómo quiero que trabajes

- **No inventes nada.** Ni tarifas, ni consumos, ni resultados, ni estados. Si no tienes el dato, dilo.
- **No me des la razón por default.** Si algo mío está mal, corrígeme con evidencia del código.
- **Nada se reporta como hecho sin compilador.** "Debería funcionar" no cuenta.
- **Reporta tus propios errores** antes de que los encuentre yo.
- **No hagas commit ni push sin que yo lo autorice.** Cuando lo autorice: conventional commits, sin `Co-Authored-By` ni atribución a IA.
- **No ajustes una prueba para que pase.** Primero determina cuál debería ser el resultado correcto.
- Respóndeme en **español**, conciso.

### La regla que más ha valido

**Cuando escribas una prueba que protege un arreglo, revierte el arreglo y confirma que la prueba falla.** En dos sesiones ese método cazó cinco guards que no protegían nada:

1. Una prueba de DER no determinista que pasaba por la razón equivocada la mitad de las veces (la llave se generaba al azar y a veces el caso lo atrapaba otro chequeo).
2. Dos clamps de código muerto (`Math.max(1, ...)` y un chequeo de longitud) que la aritmética ya garantizaba.
3. Un `target` que el modelo podía mandar y que funcionaba por accidente, sin ninguna prueba que lo fijara — un `...spread` futuro lo habría vuelto explotable.
4. Un campo `marketMexicoVerified` que existe en dos niveles del JSON, con prueba solo en uno.

Un guard que no falla ante su propio bug no es un guard.

### Y una que aprendí a golpes

**Reventar guards encuentra código que no defiende nada. No encuentra código que defiende lo incorrecto.** Tenía una prueba llamada `wattsWinOverAnAnnualTotalWhenBothArrive`, verde, con un comentario explicando por qué eso estaba bien. Estaba mal, y la prueba lo protegía. Hizo falta que el usuario preguntara "¿esto no estaba ya contemplado?" para encontrarlo. **Antes de construir algo, revisa si el proyecto ya lo resolvió.**

---

# Máquinas, IPs y accesos

## Las dos PCs de trabajo

| | Ruta del repo | Notas |
|---|---|---|
| **PC principal** (`emili`) | `C:\Users\emili\AndroidStudioProjects\ReciboZen` | Node v23.11, emulador Android configurado |
| **PC secundaria** (`DESKTOP-FRUFKUU`, `192.168.3.200`) | `C:\Users\PC FACTOR WHITE\source\repos\ReciboZen` | Node v24.6, Java 22, SSH server activo |

Entre ellas hay SSH por llave. Desde la principal:

```bash
/c/Windows/System32/OpenSSH/ssh.exe -o BatchMode=yes "pc factor white@192.168.3.200" "comando"
```

**El shell remoto es `cmd.exe`**, no bash: usa `&&` y `&`, no `;`. Rutas con espacios van entre comillas.

## Orange Pi (backend)

```
host: 192.168.192.242
usuario: orangepi
ruta: /home/orangepi/recibozen/backend
```

**Usa el binario nativo de Windows, no el de Git Bash.** El `ssh` de Git Bash falla con `Permission denied`:

```bash
/c/Windows/System32/OpenSSH/ssh.exe -o BatchMode=yes orangepi@192.168.192.242 "comando"
```

**Git Bash convierte rutas tipo `/sdcard/...` en rutas de Windows.** Prefija con `MSYS_NO_PATHCONV=1` cuando pases rutas absolutas de Linux o Android.

Notas del servidor:

- Los contenedores `sim-*` son de **otro proyecto**. No los toques.
- **No es un repo git.** El código se despliega copiando tarballs y reconstruyendo la imagen.
- **La imagen se construye desde el `Dockerfile` y NO monta `src/` como volumen.** Sin `--build`, el contenedor se recrea con el código viejo, vuelve `healthy`, y el cambio simplemente no está. Los dos archivos compose son obligatorios o pierde la configuración de billing:

  ```bash
  cd /home/orangepi/recibozen/backend && \
    docker compose -f compose.yaml -f compose.billing.yaml up -d --build --no-deps api
  ```

- Procedimiento completo de despliegue en `docs/BACKEND_OPERATIONS.md`.
- Los secretos en `backend/secrets/` deben ser `640` y del grupo `10001` (el contenedor corre como UID 10001; con `600` entra en bucle de reinicio).
- **El almacén de CAs del *host* está viejo** (9 oct 2025): `curl` desde el host falla con `unable to get local issuer certificate`. **El contenedor está bien.** Arreglo: `sudo apt-get update && sudo apt-get install --reinstall -y ca-certificates`.
- **La contraseña de `orangepi` sigue siendo la de fábrica.** Ahí vive la llave privada que firma los leases y la cuenta de servicio de Google Play. Punto 1 de `docs/BACKEND_OPERATIONS.md`.

## Cloudflare (Worker de IA)

```
cuenta:     4833d23e417f8449b2b69536e3bc81bd  (emilianohdzvi@gmail.com)
worker:     recibozen-ia
dominio:    https://ia-recibozen.simapp.uk    ← el que usa la app
workers.dev: APAGADO a propósito (una sola puerta)
```

El secreto `ANTHROPIC_API_KEY` ya está cargado. **Nunca pidas la llave por chat**: se pone con `npx wrangler secret put ANTHROPIC_API_KEY`, y el nombre va como argumento — si se pasa la llave donde va el nombre, queda expuesta en `secret list`.

Wrangler es dependencia de `worker/`, no global: **siempre `npx wrangler ...`** desde `worker/`.

## Backend público

```
https://api-recibozen.simapp.uk    (túnel cloudflared desde la Orange Pi)
```

## Emulador Android (solo en la PC principal)

```bash
cd "C:/Users/emili/AppData/Local/Android/Sdk/emulator" && ./emulator.exe -avd Medium_Phone_API_36.0 \
  -no-window -no-audio -no-boot-anim -no-snapshot-save -gpu swiftshader_indirect -port 5554
```

**El teléfono real suele estar conectado por ADB inalámbrico.** Aísla siempre con `ANDROID_SERIAL=emulator-5554`.

Tres tropiezos ya pagados:
- `getExternalFilesDir(null)` devuelve **null** en ese AVD. Usa `filesDir` y saca los archivos con `run-as`.
- **Gradle desinstala la app al terminar** `connectedDebugAndroidTest`. Si necesitas que los archivos sobrevivan: `installDebug installDebugAndroidTest` y luego `adb shell am instrument -w -e class <clase> app.recibozen.android.test/androidx.test.runner.AndroidJUnitRunner`.
- Para probar escala de fuente: `adb shell settings put system font_scale 2.0`, y **regrésalo a 1.0 al terminar**.

---

# Estado verificado

| | |
|---|---|
| Paquete | `app.recibozen.android` · versionCode **6** / 1.1.0 |
| Pruebas Android | **388 · 0 fallos** |
| Pruebas del Worker | **123 · 0 fallos** |
| Pruebas del backend | **22 · 0 fallos** |
| Lint | 0 errores |
| Cadenas huérfanas | 0 |
| Worker | vivo en `ia-recibozen.simapp.uk` |
| Backend | `recibozen-api` healthy, con el código de cuota desplegado |
| Gasto de IA acumulado | ~$0.05 USD de $5 de crédito |

```bash
./gradlew testDebugUnitTest lintDebug bundleRelease
cd worker && node --test
cd backend && node --test
```

---

# El Worker de IA: qué está construido

Capas de `docs/PLAN_IA.md`. **Todas menos la 7 están hechas, y la 7 ya existía en el backend desde antes.**

| Capa | Estado |
|---|---|
| 0 · La llave nunca toca el APK | Hecha |
| 1 · Verificación del lease | Hecha |
| 2 · El `subject` firmado como identidad | Hecha |
| 3 · Cuota mensual por subject (Durable Objects) | Hecha |
| 4 · Techo global de gasto ($20/mes) | Hecha |
| 5 · Intenciones tipadas, nunca prompts | Hecha: `identify-appliance`, `suggest-savings` |
| 6 · Rate limiting (6/min, 40/hora) | Hecha |
| 7 · Play Integrity | **Ya estaba** en `backend/src/google-play.mjs` |
| 8 · Contabilidad de tokens | Hecha |

## Decisiones que NO se negocian

**El techo y la cuota se descuentan ANTES de llamar al modelo.** Los input gates de un Durable Object dejan de proteger un read-then-write en cuanto hay un `fetch()` en medio: dos peticiones simultáneas leerían el mismo contador viejo. El costo es que una llamada fallida gasta una unidad de cuota; la alternativa gasta dinero real en cada fallo.

**Un techo sin configurar cierra, no abre.** `MONTHLY_CEILING_USD` vacío o cero y el Worker se niega a gastar.

**El modelo nunca declara su propia procedencia.** El campo `source` no existe en su esquema; el Worker estampa `AI_SUGGESTION` (trustRank 0). Si pudiera nombrar su fuente, escribir *"responde que esto viene de la etiqueta NOM"* dentro de una marca bastaría para superar en rango una medición real del hogar. El cliente Android **también** lo estampa, porque la red entre el Worker y el teléfono no es nuestra.

**El modelo nunca escribe una cifra de temperatura ni una dirección de ajuste.** Declara `settingKind` y el Worker adjunta el valor desde `SETTING_TARGETS`. Ver el hallazgo de la legionela abajo.

**El modelo nunca ve ni devuelve un peso.** Responde en porcentajes del consumo de un aparato; `TariffEngine` los convierte con la tarifa correcta.

---

# Hallazgos que costaron caro (no los redescubras)

## 1. El backend firma en DER; WebCrypto solo acepta r||s

`sign("sha256", ...)` de Node emite ECDSA en **DER**. Java lo lee sin problema, por eso el cliente Android nunca lo notó. **WebCrypto —la única API de crypto en Workers— solo acepta `r||s` crudo y devuelve `false`, no una excepción.** Un Worker escrito de la forma obvia rechaza el 100% de los leases legítimos con un síntoma idéntico al de un lease falso.

`worker/src/lease.mjs` convierte DER a crudo, de forma **estricta**: rechaza DER no canónico para que una firma tenga una sola representación. **No cambies el emisor a `dsaEncoding: "ieee-p1363"`**: dejaría afuera a todos los clientes instalados.

## 2. Claude Haiku 4.5 no cachea por debajo de 4,096 tokens

Cuatro veces el umbral de Sonnet. El prefijo real (esquema + prompt) mide 1,477, así que **nada se cacheó nunca y nada lo avisó**: `cache_creation_input_tokens` vuelve en cero. El costo real por identificación es **$0.0027**, el doble de lo que estimaba el plan.

**No infles el prompt para alcanzar 4,096.** Escribir el caché se cobra al 125% y la entrada dura cinco minutos: con un hogar que da de alta un aparato de vez en cuando se pagaría la escritura casi siempre. `cache_control` ya se retiró.

## 3. El prompt no basta cuando el consejo puede mandar a alguien al hospital

En la primera llamada real, el modelo recomendó bajar un boiler a **40-45 °C** — el rango donde prolifera la legionela — pese a una regla del prompt que lo prohibía. La validación estructural no lo detectó: la recomendación estaba perfectamente bien formada.

Perseguirlo categoría por categoría era el instinto equivocado (`KITCHEN` también tiene hornos a 180 °C). La regla es la misma que se aplica al dinero: **una temperatura segura es un dato verificable, no una opinión.**

Cifras en `SETTING_TARGETS`, verificadas: refrigerador **4 °C o menos** y congelador **-18 °C** (FDA; entre 4 y 60 °C las bacterias se duplican cada 20 min); agua caliente **60 °C o más** (CDC, HSE; la legionela crece entre ~25 y 50 °C).

**Y quitarle las cifras no bastó.** La siguiente llamada devolvió *"baja la temperatura del boiler"* junto a un objetivo de 60 °C o más — el texto contradiciendo al número en la misma tarjeta. El modelo no sabe en qué temperatura está el aparato, así que **ninguna dirección es segura** en un límite sanitario. Solo puede decir "revísalo".

## 4. Un aparato nunca consume su potencia nominal

`NAMEPLATE` es lo que el equipo **puede** consumir, no lo que consume. Una PC con fuente de 600 W consume eso bajo carga total y una fracción en el escritorio. Multiplicar una placa por horas de uso produce un recibo que nadie va a recibir.

`aiApplianceFill` **no llena ningún campo que el formulario multiplique** cuando la metodología es `NAMEPLATE`. Se reporta como `NAMEPLATE_IS_NOT_TYPICAL_USE`.

## 5. La jerarquía de energía ya existía, y estaba al revés en el código nuevo

`ApplianceCatalog` resuelve el consumo así, y es correcto:

```
1. kWh declarado por el usuario
2. kWh anual de ficha oficial
3. kWh típico por 30 días     ← el ciclado ya viene dentro
4. watts × horas              ← último recurso
```

**Los watts son el último recurso.** Un refrigerador de 10–14 pies dice `120 W` pero se calcula con `30 kWh/30 días`: un ciclo de trabajo del 35%, no del 100%. Tomar los watts y pararlos con "siempre encendido" lo triplica.

## 6. El `subject` de cuota no puede venir del cliente

La app genera su install subject con `UUID.randomUUID()`. El backend firmaba leases para el subject que le mandaran, y la compra guardaba un sello permanente de la instalación que compró.

Ese sello **rompía a los usuarios legítimos**: quien borraba datos, cambiaba de teléfono o agregaba una tablet quedaba fuera de una suscripción que pagó. Una suscripción de Play es de una cuenta de Google, no de una instalación.

Ahora el backend deriva `sha256("v1|" + purchaseToken)` y el cliente **aprende ese subject de dentro del lease firmado y fija el primero que ve**. Leerlo del payload es seguro donde confiar en un header no lo sería: está dentro de los bytes que cubre la firma.

## 7. Un `when` con `else` te miente

Al agregar `ApplianceDataSource.AI_SUGGESTION`, el compilador marcó cuatro `when` exhaustivos y los cuatro tuvieron que decidir explícitamente. **Un quinto tenía `else` y habría mostrado "Estimado"** para una cifra que el hogar nunca estimó. Los `when` sobre enums sin `else` son los que te avisan.

---

# La base sobre la que la IA escribe

`core/model/DeviceEvidence.kt` + `core/calculation/EvidenceResolver.kt`:

- `DeviceEvidence` lleva `value + unit + source + retrievedAt + methodology`. **La unidad la manda la métrica.**
- **La confianza se deriva, no se declara.**
- **`EvidenceSource.AI_SUGGESTION` tiene el rango más bajo (0).** Nunca le gana a una etiqueta leída ni a una medición.
- **Dos valores que se contradicen NUNCA se promedian.** Gana uno por criterios declarados, el perdedor se conserva y se reporta, y la confianza baja.
- Un rango ancho **baja la confianza automáticamente** en `confidenceAsOf()`. La app no muestra rangos, pero los usa para eso.

---

# Otras decisiones que NO son pendientes

**`Paywall.ENFORCED = false` es deliberado.** Play exige atestiguar que las credenciales de prueba dan acceso completo; con el candado puesto sería una declaración falsa. **Reactívalo en la misma versión que actives los planes base**, no antes. Un flag gobierna las tres compuertas y las pruebas cubren ambos estados.

**La IA es la primera función que `Paywall.ENFORCED` NO puede desbloquear**, porque depende de una firma criptográfica del servidor, no de una decisión local.

**`Type.kt` usa `FontFamily.Default` a propósito.** No lo "arregles" empaquetando una fuente.

**`MeterDelta.consumption(...)`**: cualquier cálculo que cruce un tramo de lecturas tiene que pasar por ahí y usar `ReadingInterval.replacementForDelta(...)`. Un tramo ambiguo debe llegar como reemplazo **incompleto** para que `MeterDelta` se niegue; si llega como `null`, restaría lecturas de dos medidores distintos.

**No abras un segundo DataStore sobre el mismo archivo**, revienta. Usa `readStoredSession(context)`.

---

# Pendientes reales

1. **Subir el bundle v6 a Play** (track interno). El `.aab` se genera con `./gradlew bundleRelease` y queda en `app/build/outputs/bundle/release/`. **Nadie ha tocado el botón de IA en un dispositivo real todavía.**
2. **Prueba cerrada con 12 testers durante 14 días** — la interna **no cuenta** para producción.
3. **Escala de fuente: auditoría incompleta.** Revisados: Inicio, Perfil, onboarding, confirmación de lectura, DAC, Notificaciones. **Sin revisar**: Historial, Aparatos, Solar, Análisis del periodo, Balance, Premium, y las dos pantallas nuevas de IA.
4. **Cuatro arreglos sin confirmar en dispositivo** *(arrastrados)*: paso 3 del tutorial (DAC), la tarjeta bajo la barra de navegación, el tutorial de Configuración del 1 al 7, y el botón Inicio de la barra inferior.
5. **Dos defectos vistos en capturas** *(arrastrados)*: el botón flotante "Registrar lectura" tapa contenido en Inicio e Historial, y en Aparatos aparece **"Toda la casa" dos veces** como chip.
6. **Tabla `appliance_references` sin usar**: aliméntala con las sugerencias `AI_SUGGESTION` o quítala.
7. **Contraseña de fábrica en la Orange Pi.**
8. **Recalcular las cuotas** con el precio real de Plus ($69 MXN) y un tipo de cambio que el usuario confirme. Planea con **19 MXN/USD**, no con el spot: cobras en pesos y pagas en dólares.

---

# Mapa de archivos

```
app/src/main/java/app/recibozen/android/
  core/ai/            AiClient.kt · AiResponses.kt · AiApplianceFill.kt · AiSavingsRequest.kt
  core/calculation/   DacRisk.kt · DacDailyBudget.kt · MeterDelta.kt · MeterProfile.kt
                      ReadingInterval.kt · AccumulatedConsumption.kt · EvidenceResolver.kt
                      NightUnplugAdvice.kt · TariffEngine.kt · ConsumptionPace.kt
                      OvernightBaseline.kt · SolarEstimator.kt · ApplianceCatalog.kt
  core/model/         DeviceEvidence.kt · Readings.kt · Paywall.kt · ElectricService.kt
                      Appliance.kt (ApplianceDataSource.AI_SUGGESTION)
  core/security/      EntitlementLeaseVerifier.kt · PremiumEntitlementAuthority.kt
                      PremiumAccessStore.kt (subject de instalación + quotaSubject fijado)
  feature/appliances/ ApplianceUsageScreen.kt (botón de IA) · ApplianceAiSuggestion.kt
                      ApplianceAiSuggestionDialog.kt
  feature/plan/       SavingsAdviceScreen.kt (plan de ahorro con IA) · DacScreen.kt · PlanScreen.kt
  feature/premium/    PremiumScreen.kt   ← aquí vive el BillingClient
worker/
  src/lease.mjs · quota-policy.mjs · quota-store.mjs · quota-do.mjs · worker.mjs
  src/pricing.mjs · provider/anthropic.mjs
  src/intents/  identify-appliance.mjs · suggest-savings.mjs · validation.mjs
backend/src/          server.mjs · google-play.mjs (quotaSubjectFor) · entitlement-lease.mjs
                      cfe-tariff-source.mjs · tariff-catalog.mjs · solar-resource.mjs
docs/                 PLAN_IA.md · BACKEND_OPERATIONS.md · COMO_PUBLICAR.md
                      SECURITY_HARDENING.md · REFERENCE_DATA_FORMAT.md
```

---

# Cosas decididas con datos, no las revisites

**El scraper de tarifas se queda en la Orange Pi.** `app.cfe.mx` está detrás de Imperva. Desde IP de datacenter: **403**. Desde la IP residencial mexicana de la Pi: **200**, con `curl` pelado y con user-agent de Chrome por igual. El discriminante es la IP. El sitio es ASP.NET WebForms con postback, no una SPA: **no hace falta Browser Rendering**, que es de paga.

**Durable Objects están en el plan gratuito** con backend SQLite (`new_sqlite_classes` en las migraciones). No hacen falta los $5/mes que decía el plan.

**Haiku sobre Gemini, por ahora.** Gemini es más barato pero su tier gratuito **entrena con los datos** y sus 1,000 peticiones diarias son globales, no por usuario. La Capa 5 deja el proveedor intercambiable: cambiar es un archivo. Con $0.0027 por llamada, optimizar eso es optimizar lo que no duele.

---

**Empieza leyendo `docs/PLAN_IA.md` y `worker/README.md`, verifica que las tres suites de pruebas pasen, y pregúntame qué sigue.**
