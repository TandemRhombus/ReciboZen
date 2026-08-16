# Auditoría ReciboZen — 16 ago 2026

Rama `sergio`, working tree limpio antes de empezar (solo `tmp/source_review/node_modules` sin trackear).
~24,400 líneas Kotlin en 125 archivos, 24 suites de prueba, backend Node con 4 suites.
Sin commits, sin push, sin operaciones destructivas de Git.

---

## 1. Resumen ejecutivo

El proyecto está bastante más maduro de lo que el brief asume. Varias cosas que pediste **ya estaban resueltas y bien resueltas**: DAC usa ventana móvil de 365 días con el límite específico de cada tarifa (no 3,000 fijo), los periodos irregulares se prorratean por días reales, el pico anual solar exige año completo antes de usarse, las lecturas manuales se deduplican contra recibos, el `TariffCalibration` reconstruye escalones desde el recibo con reglas de umbral correctas, y `HonestDataDefaultsTest` ya blinda contra datos de demostración en instalación nueva.

Lo que sí encontré son **seis defectos reales** que sí afectan a un usuario, y un patrón de fondo: **hay infraestructura construida que nadie consume**. El caso más caro es el catálogo remoto de tarifas CFE — backend que raspa CFE, Docker, WorkManager diario, tabla de Room, migración de esquema — y ninguna consulta lo lee. La app sigue estimando con bloques genéricos inventados (1.08 / 1.32 / 3.94 MXN/kWh) para cualquier hogar que no haya importado un PDF.

Los tres hallazgos con más impacto en la experiencia:

1. **Un recibo viejo se presentaba como “periodo actual” indefinidamente.** Sin guardia de antigüedad, un recibo importado en julio seguía etiquetando el periodo en enero.
2. **Falso “consumes más de lo recomendado”.** El ritmo diario se dividía entre los días desde el cierre del recibo aunque el numerador arrancara en una lectura anterior. Con una lectura 10 días antes del cierre, el ritmo salía al doble.
3. **Inicio tenía ~170 líneas de UI muerta cableada a tres aparatos ficticios** (`minisplit`, `refrigerador`, `ps5`) más una tarjeta de consejo fija sobre un minisplit. No se renderizaba —por eso no llegó a usuarios— pero el `HomeViewModel` real importaba `DemoReciboZenData` y buscaba esos IDs en cada recomposición. El handoff afirma “sin código muerto conocido”; no es exacto.

Corregí los seis y dejé el resto documentado con archivo y línea.

---

## 2. Tabla de hallazgos

| # | Sev. | Hallazgo | Evidencia | Estado |
|---|------|----------|-----------|--------|
| H-01 | **Crítica** | Catálogo remoto de tarifas CFE se descarga, valida y persiste, pero **ninguna consulta lo lee**. La estimación cae en bloques genéricos inventados para todo hogar sin recibo. | `ReciboZenDao.kt` tenía `insertTariffReferences` sin ningún `@Query` de lectura; `TariffEngine.DEFAULT_1C_BIMONTHLY_BLOCKS` | **Corregido** |
| H-02 | **Alta** | Un recibo cuyo periodo siguiente ya cerró se seguía mostrando como periodo en curso. `daysRemaining` quedaba en 0 y las etiquetas de fecha mentían. | `BillingPeriodTimeline.after()` sin parámetro de fecha actual; `HomeViewModel:44`, `PeriodAnalysisViewModel:23` | **Corregido** |
| H-03 | **Alta** | Ritmo diario inflado → falso “consumes más”. `measuredKwh` arrancaba en la lectura base pero se dividía entre los días desde el cierre del recibo. | `DemoReciboZenRepository.energySummary()`, cálculo de `measuredDays` | **Corregido** |
| H-04 | **Alta** | Inicio con UI muerta acoplada a fixtures: `MinisplitTipCard` e `InfluenceCard` nunca invocadas; `HomeViewModel` importaba `DemoReciboZenData` y buscaba IDs `"minisplit"`/`"refrigerador"`/`"ps5"`; `onSeeWhyMinisplit` navegaba a un aparato inexistente. | `HomeScreen.kt:663,999`; `HomeViewModel.kt:11,38-41,135` | **Corregido** |
| H-05 | **Alta** | Backend: caché de recurso solar sin cota. Coordenadas redondeadas a 2 decimales, `Map` que nunca desaloja → agotamiento de memoria en la Orange Pi. Sin límite de tasa en las dos rutas públicas. | `solar-resource.mjs:9`; `server.mjs` sin rate limiting | **Corregido** |
| H-06 | **Media-alta** | El reposo se descartaba en silencio cuando el aparato tenía horario pero no potencia nominal. Un TV o consola con watts de espera configurados perdía ese consumo. | `DemoReciboZenRepository.adjustedAppliances()`, rama `isScheduled && hoursPerWeek > 0` | **Corregido** |
| H-07 | Media | `TariffSyncWorker` no exigía HTTPS ni bloqueaba redirecciones (el hermano `SolarResourceClient` sí). Mitigado por `network_security_config`, pero inconsistente. Además reintentaba errores no reintentables. | `TariffSyncWorker.kt:38-45` | **Corregido** |
| H-08 | Media | `/readyz` reportaba todo el servicio como `not_ready` solo porque Google Play no está configurado. Un orquestador dejaría fuera de rotación una API de tarifas sana. | `server.mjs`, rama `/readyz` | **Corregido** |
| H-09 | Media | 129 recursos de texto sin usar, incluyendo pantallas completas abandonadas (`period_chart_*`, `solar_refine_*`, `onboarding_step0_*`). | Detector propio sobre `strings_*.xml` vs. todo el código | **Parcial**: eliminé los 11 que quedaron huérfanos; el resto listado abajo |
| H-10 | Media | Textos con jerga de implementación (“se incorporaron”, “el backend”, “el motor”) y una promesa a futuro sobre Plus/Familiar. | `strings_solar.xml:18`, `strings_premium.xml:20`, `strings_settings.xml:49`, `strings_plan.xml:149`, `strings_readings.xml:116,118` | **Corregido** |
| H-11 | Media | Tabla Room `appliance_references` con `insertApplianceReferences` sin ningún escritor ni lector. Esquema mantenido sin propósito. | `ReciboZenDao.kt:71` | Documentado |
| H-12 | Media | `dac_footnote` decía “confianza media” fijo, sin depender de la confianza real. Está **sin usar** hoy, así que no llegó a pantalla. | `strings_plan.xml:114` | Documentado |
| H-13 | Baja | `applianceCoveragePercent` se recorta a 100. Cuando el inventario explica más que el medidor, el usuario no se entera de la contradicción. | `HomeViewModel`, `coerceIn(0,100)` | Documentado |
| H-14 | Baja | Backend sin `Strict-Transport-Security`. Mitigado porque el TLS termina en el túnel Cloudflare. | `server.mjs`, `sendJson` | Documentado |
| H-15 | Baja | Comparación de ritmo usa ventanas distintas: “ahora” es promedio de lo transcurrido, “recomendado” es lo que queda entre los días restantes. Defendible, pero el texto debería decirlo. | `ConsumptionPaceCalculator` + `PaceCard` | Documentado |

---

## 3. Correcciones matemáticas

### 3.1 Ritmo diario (H-03)

Antes:

```
measuredKwh = lecturaNueva − lecturaBase
measuredDays = díaLecturaNueva − díaCierreRecibo      ← denominador equivocado
```

Si la lectura base se tomó 10 días **antes** del cierre y la nueva 10 días después, el numerador cubría 20 días reales pero se dividía entre 10. Ritmo al doble, proyección al doble, y `ConsumptionPaceCalculator.relation()` marcando `ABOVE_RECOMMENDED` a un hogar que iba bien.

Ahora se separan dos conceptos:

- `intervalDays` = días reales entre las dos lecturas → es el denominador del ritmo.
- `measuredDays` = días de ese intervalo que caen dentro del periodo actual → es lo observado del periodo.
- `observedKwh` = ritmo × `measuredDays`.

Cuando la lectura base está exactamente en el cierre (caso normal, con el PDF aportando el endpoint), ambos coinciden y el resultado es idéntico al anterior. Prueba: `ReadingIntervalRateTest.aBaselineTakenBeforeTheBillClosedDoesNotInflateTheDailyRate` — 200 kWh en 20 días reales dan 10 kWh/día y 100 kWh observados, no 20 kWh/día y 200.

### 3.2 Periodo en curso (H-02)

`BillingPeriodTimeline.after()` ahora acepta `asOfEpochDay` y encadena periodos completos hacia adelante (los periodos CFE comparten frontera: el que cierra el 14 de julio abre el siguiente el 14 de julio) hasta que uno contenga esa fecha. Tope de 12 periodos: más allá, devuelve `null` en vez de inventar fechas.

También expone `periodsAfterLatestBill`, `daysRemaining(asOf)` y `daysElapsed(asOf)`, y corrige la etiqueta corta cuando el periodo cae dentro de un solo mes (`Jul 26`, no `Jul–Jul 26`).

**Fuente única:** el periodo ahora se calcula una vez en `energySummary()` y viaja en `EnergySummary.currentPeriod`. `HomeViewModel` y `PeriodAnalysisViewModel` lo leen de ahí en lugar de recalcularlo cada uno. `daysRemaining` sale de la misma fuente.

### 3.3 Reposo (H-06)

La rama que calcula desde kWh almacenados y horario ignoraba `standbyWatts`/`standbyEnabled`. Ahora aplica la misma fórmula que la rama con potencia nominal: `W ÷ 1000 × (168 − horas) × días ÷ 7`. Prueba con valores verificados: 20 kWh/30 días escalados a 62 días = 41.33 kWh activos; 10 W en 140 h/semana durante 62 días = 12.4 kWh; reposo = 23 % del total.

### 3.4 Fronteras DAC

Agregué las tres pruebas que pediste, más cinco de robustez, todas con el resultado correcto derivado primero:

- **Justo debajo** (2,988 kWh anuales, tarifa 1): margen positivo, nivel `RISK`.
- **Exactamente en el límite** (3,000 kWh = 250 kWh/mes): margen 0, nivel `RISK` — CFE reclasifica cuando el promedio **excede**, así que el borde exacto no puede reportarse como rebasado.
- **Justo arriba** (3,012 kWh): margen negativo, nivel `ABOVE_LIMIT`.
- El máximo recomendado para el siguiente recibo es el mayor que aún cabe: `retenido + recomendado ≤ 3,000` y `+1` ya lo rebasa.
- Tarifa no soportada (PDBT) responde `UNSUPPORTED` en lugar de tomar prestado otro límite.
- Cuenta DAC sin tarifa doméstica de referencia reporta su estado sin inventarla.
- Periodos invertidos o con consumo negativo se descartan sin corromper la ventana.

**Verifiqué los límites del catálogo** (`TariffCatalog.supported`): 1→250, 1A→300, 1B→400, 1C→850, 1D→1,000, 1E→2,000, 1F→2,500 kWh/mes. Coinciden con los valores publicados por CFE. No los toqué.

---

## 4. Datos que estaban desaprovechados

| Dato | Estado anterior | Decisión |
|---|---|---|
| Catálogo remoto de tarifas CFE (`tariff_rate_references`) | Escrito diario por `TariffSyncWorker`, jamás leído | **Utilizarse.** Nueva consulta `observeTariffReference()`, nuevo modelo `RemoteTariffReference`, y precedencia explícita en `activeTariffEngine()` |
| `EnergySummary.currentPeriod` | No existía; cada ViewModel recalculaba el periodo | **Consolidarse.** Fuente única |
| `standbyWatts` con horario sin potencia nominal | Persistido y editable, descartado en el cálculo | **Utilizarse** |
| Inventario real de aparatos en Inicio | La tarjeta “Qué está influyendo más” existía pero estaba muerta y cableada a 3 IDs ficticios | **Utilizarse.** Ahora rankea los aparatos reales del usuario por costo y calcula “sin identificar” restando lo explicado del total del hogar |
| `appliance_references` (tabla Room) | Sin escritor ni lector | **Pendiente de decisión.** Ver §8 |
| 129 recursos de texto | Sin referencias | **Eliminarse** (parcial) |

### Precedencia de tarifas, ahora explícita

```
1. Recibo del usuario   → conserva localidad, temporada y región exactas
2. Catálogo CFE sincronizado → oficial, pero sin contexto de localidad
3. Bloques genéricos    → orden de magnitud, nunca una cotización
```

Con una salvaguarda deliberada: **el catálogo remoto solo se usa si la temporada no es ambigua**. Las tarifas 1A–1F traen dos juegos de rangos (verano / fuera de verano) y cuál aplica depende del inicio de verano de la localidad, dato que solo trae el recibo. Adivinar la temporada facturaría mal en silencio, así que en ese caso la estimación se queda en los bloques genéricos y `tariffRateOrigin()` lo reporta como `GENERIC`. Esto significa que **el beneficio real de H-01 hoy aplica a tarifa 1 y a DAC**; para 1A–1F sigue haciendo falta capturar el inicio de verano (ver §8).

También validé la tabla remota antes de usarla: si los umbrales no crecen o no hay bloque abierto final, se descarta — una tabla progresiva corrupta cobraría el escalón equivocado.

---

## 5. Textos reescritos

| Recurso | Antes | Ahora |
|---|---|---|
| `solar_manual_evidence` | “También **se incorporaron** %d días útiles entre lecturas manuales que no duplican recibos.” | “Incluye %d días medidos con tus lecturas.” |
| `premium_verifying` | “…ahora **el backend** está verificando la compra y la integridad de la app.” | “Google Play confirmó el pago. Estamos validando la compra; toma unos segundos.” |
| `services_limit_body` | “ReciboZen Plus **permitirá** hasta 2 suministros y Familiar hasta 5…” | “El plan gratis maneja 1 suministro. Puedes revisar un recibo antes de decidir; el límite se aplica al guardar el nuevo suministro.” |
| `dac_tariff_effective_bill` | 225 caracteres explicando por qué el desglose no permite reconstruir las cuotas | “El costo por kWh viene de tu último recibo DAC. Se actualiza cuando importes uno más reciente.” |
| `how_we_calculate_two_forecasts_body` | “El medidor proyecta… La comparación revela cuánto consumo falta por identificar o qué horarios conviene revisar.” | “Tu medidor apunta a %d kWh y tus aparatos explican %d kWh. No se suman: el medidor ya los incluye. La diferencia es lo que falta por identificar.” |
| `how_we_calculate_assumptions_body` | 458 caracteres con “el inventario genera”, “el motor usa” | 5 viñetas cortas sin jerga; ahora también dice que las tarifas pueden venir del catálogo de CFE |

**Eliminados** (11, huérfanos tras quitar la UI muerta): `home_appliance_minisplit`, `home_appliance_refrigerador`, `home_appliance_ps5`, `home_tip_eyebrow`, `home_tip_headline`, `home_tip_savings`, `home_chip_effort_low`, `home_chip_confidence_medium`, `home_see_why`, `home_add_to_plan`, `home_tracking`.

**Agregado**: `home_confidence_readings_status` como `<plurals>`. Antes el `HomeViewModel` construía el texto en Kotlin (`"$n lecturas posteriores al recibo"`), lo que además producía “1 lecturas”.

Lo que **no** toqué a propósito: las advertencias sobre estimaciones, seguridad, privacidad, DAC y paneles. `solar_disclaimer` (“No prometemos un recibo de cero pesos”) y `dac_exit_actions_body` (“No desconectes equipos esenciales…”) están bien redactadas y son necesarias.

---

## 6. Mejoras de UI

- **Inicio, “Qué está influyendo más”**: pasa de tarjeta muerta con tres aparatos ficticios a lista real de los 3 aparatos de mayor costo del usuario, más una fila de “Todavía sin identificar” que solo aparece cuando existe un total del hogar contra el cual restar (proyección de medidor o recibo cerrado). Si el usuario no tiene aparatos, la tarjeta no se muestra: sin filas en cero.
- **Etiquetas de periodo**: dejan de mentir cuando el último recibo es viejo, y el periodo mensual contenido en un mes ya no se etiqueta “Jul–Jul”.
- **Contador de lecturas**: plural correcto vía recurso, no concatenación en Kotlin.
- Firma de `HomeScreen` con 2 lambdas menos.

Lo que **no** pude auditar en esta sesión: contraste en claro/oscuro, comportamiento con texto grande, insets con barra de 3 botones, teclado abierto y scroll hasta el último control. Eso requiere renderizar la app; sin SDK de Android en este entorno no puedo verificarlo y no voy a afirmar que está bien sin haberlo visto.

---

## 7. Cambios del backend

- **Caché solar acotada** (`MAX_CACHE_ENTRIES = 512`), con desalojo de expirados primero y luego LRU. Antes crecía sin techo: cada coordenada distinta era una entrada permanente.
- **Rate limiting** con ventana fija por cliente y ruta: 20/min en `/v1/solar/resource`, 10/min en `/v1/entitlements/exchange`. El mapa de contadores también está acotado (4,096) y se poda. La identidad del cliente sale de `cf-connecting-ip` solo si es un valor único bien formado; si no, del socket, para que un encabezado falsificado no pueda crear buckets infinitos.
- **`/readyz` corregido**: la disponibilidad para servir tráfico deja de depender de que Google Play esté configurado. Ahora reporta `tariffCatalogAvailable` y `solarResourceConfigured` por separado, y `playBillingConfigured` sigue visible como capacidad opcional.

Revisé y **no cambié** (están bien): validación de coordenadas antes de llamar a NASA, `AbortSignal.timeout(12s)`, escritura atómica del catálogo con `rename` y modo `0600`, verificación de que la llave privada y la pública coinciden al arrancar, `503 play_verification_not_configured` sin atajo temporal, encabezados de seguridad, redacción de coordenadas en el log, límite de 64 KiB en el cuerpo JSON, y los timeouts del servidor.

---

## 8. Pendientes reales (requieren datos o decisiones externas)

1. **Inicio de verano y región DAC por localidad.** Sin esto, el catálogo remoto solo puede aplicarse a tarifa 1. Es la pieza que desbloquea el beneficio completo de H-01. Necesita una fuente verificable o captura explícita del usuario.
2. **Google Play Billing + Play Integrity en backend.** El paywall, la política y la frontera fail-closed ya están; falta la validación real.
3. **Tabla `appliance_references`.** Decidir: alimentarla desde un paquete versionado del dataset, o eliminarla del esquema. Hoy es esquema mantenido sin propósito.
4. **118 recursos de texto sin usar** (después de eliminar 11). Los bloques más grandes: `period_*` (21), `solar_refine_*` y `solar_reduce_*` (13), `onboarding_step0_*` y `onboarding_row_*` (12), `appliances_usage_*` (11). Corresponden a pantallas del handoff de diseño que nunca se implementaron o se reescribieron. Hay que decidir cuáles son trabajo pendiente y cuáles son basura.
5. **Auditoría visual real** (contraste, texto grande, insets, teclado). Requiere ejecutar la app.

---

## 9. Verificación

| Qué | Resultado |
|---|---|
| Pruebas backend (`node --test test/*.test.mjs`) | **21/21 pasan** (antes 17; agregué 4) |
| Sintaxis backend (`node --check`) | OK |
| Sintaxis XML de todos los `strings_*.xml` | OK |
| Balance estructural de los 10 archivos Kotlin modificados | OK |
| Detector de composables muertos en `feature/home` | 0 (antes 2) |
| `./gradlew testDebugUnitTest assembleDebug` | **No ejecutado** |
| APK | **No generado** |

Sobre la compilación: el SDK de Android vive en tu Windows (`C:\Users\emili\AppData\Local\Android\Sdk`), que no es accesible desde donde ejecuto, y me pediste no gastar la sesión levantando un SDK propio. Los cambios en Kotlin están verificados por revisión y balance estructural, no por compilador. **Antes de confiar en ellos, corre:**

```
./gradlew testDebugUnitTest assembleDebug
```

Riesgos concretos si algo falla: `EnergySummary` ganó un campo (`currentPeriod`) con valor por defecto, así que las construcciones existentes siguen válidas; `activeTariffBlocks()` pasó de expresión a bloque con tipo de retorno explícito; `HomeScreen` perdió 2 parámetros y `HomeUiState` 9 campos, todos ellos referenciados únicamente desde código que también eliminé. Si aparece un error, lo más probable es un import sin usar (advertencia, no error) o una preview.

---

## 10. Archivos modificados

**App (10)**
```
app/src/main/java/com/recibozen/app/core/calculation/BillingPeriodTimeline.kt
app/src/main/java/com/recibozen/app/core/tariff/TariffSyncWorker.kt
app/src/main/java/com/recibozen/app/data/local/ReciboZenDao.kt
app/src/main/java/com/recibozen/app/data/repository/PersistentReciboZenRepository.kt
app/src/main/java/com/recibozen/app/demo/repository/DemoReciboZenRepository.kt
app/src/main/java/com/recibozen/app/demo/repository/ReciboZenSessionState.kt
app/src/main/java/com/recibozen/app/feature/home/HomeScreen.kt
app/src/main/java/com/recibozen/app/feature/home/HomeUiState.kt
app/src/main/java/com/recibozen/app/feature/home/HomeViewModel.kt
app/src/main/java/com/recibozen/app/feature/period/PeriodAnalysisViewModel.kt
```

**Textos (6)**
```
app/src/main/res/values/strings_home.xml
app/src/main/res/values/strings_plan.xml
app/src/main/res/values/strings_premium.xml
app/src/main/res/values/strings_readings.xml
app/src/main/res/values/strings_settings.xml
app/src/main/res/values/strings_solar.xml
```

**Pruebas (4)**
```
app/src/test/java/com/recibozen/app/BillingPeriodTimelineTest.kt     (reescrita: 1 → 7 pruebas)
app/src/test/java/com/recibozen/app/DacRiskCalculatorTest.kt         (9 → 16 pruebas)
app/src/test/java/com/recibozen/app/ReadingIntervalRateTest.kt       (nueva, 5 pruebas)
app/src/test/java/com/recibozen/app/TariffRateOriginTest.kt          (nueva, 5 pruebas)
```

**Backend (4)**
```
backend/src/server.mjs
backend/src/solar-resource.mjs
backend/test/server.test.mjs      (+2 pruebas)
backend/test/tariff-solar.test.mjs (+2 pruebas)
```

Total: **24 archivos**, +18 pruebas nuevas. Sin commits.

---

## 11. Nota sobre la prueba que ajusté

`readiness distinguishes process health from billing configuration` afirmaba que `/readyz` debía responder 503 cuando Play no está configurado. Determiné primero cuál debía ser el resultado correcto: la disponibilidad para servir tráfico no depende de una capacidad opcional. Renombré la prueba a lo que realmente verifica (`readiness reports not_ready when no data service can answer`) y agregué una segunda que prueba el caso que antes fallaba en silencio: catálogo de tarifas presente y Play sin configurar debe responder `ready`. No ajusté ninguna prueba para justificar un resultado incorrecto.
