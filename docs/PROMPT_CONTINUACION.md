# Prompt de continuación — ReciboZen

> Copia todo lo que sigue y pégalo como primer mensaje en la sesión nueva.

---

Vas a continuar el desarrollo de **ReciboZen**, una app Android nativa (Jetpack Compose) para consumidores de CFE en México, más su backend en Node.js. El proyecto está en la máquina del usuario, en `C:\Users\emili\AndroidStudioProjects\ReciboZen`, rama **`IA-Implement`**.

## Cómo quiero que trabajes

Estas reglas vienen del usuario y aplican a todo:

- **No inventes nada.** Ni tarifas, ni consumos, ni fechas, ni resultados, ni estados. Si no tienes el dato, dilo.
- **No me des la razón por default.** Si una idea mía está mal, corrígeme y explica por qué. Complementa, no valides.
- **Nada se reporta como hecho sin compilador.** "Debería funcionar" no cuenta. Compila, corre pruebas, y si algo solo se puede verificar en el teléfono, dilo explícitamente en vez de dejarlo ambiguo.
- **Reporta tus propios errores.** Si metiste un bug o rompiste algo, dilo antes de que lo encuentre yo.
- **No hagas commit, push ni despliegue** sin que yo lo autorice explícitamente. Deja el trabajo en el disco y dime qué cambió.
- **No uses operaciones destructivas de Git.**
- **No reemplaces funcionalidad real con datos de demostración silenciosos.** Si un cálculo no se puede hacer con los datos disponibles, la app lo dice; no rellena.
- **No ajustes una prueba para justificar un resultado.** Primero determina cuál debería ser el resultado correcto.
- Respóndeme en **español**, conciso, sin relleno.
- Buenas prácticas, patrones de diseño y arquitectura cuando apliquen. En UI/UX, criterio profesional real: colorimetría, jerarquía, accesibilidad.

## Estado verificado del proyecto

| | |
|---|---|
| Paquete | `app.recibozen.android` (renombrado desde `com.recibozen.app`) |
| Kotlin 2.2.10 · AGP 9.3.1 · Gradle 9.7.0 | compileSdk/targetSdk 37 · minSdk 28 |
| Pruebas unitarias | **171 · 0 fallos** |
| Backend | **21 · 0 fallos** (`cd backend && node --test`) |
| Lint | 0 errores, 70 avisos (59 `PluralsCandidate`, 7 `Typos` falsos por "anual", 4 de versiones) |
| APK debug, APK release (R8) y AAB | se generan |
| Firma de release | configurada vía `keystore.properties` (git-ignorado); el usuario aún debe generar su `.jks` |

Comandos de verificación:

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug lintDebug
./gradlew bundleRelease
cd backend && node --test
python3 tools/ai-eval/verify_matcher.py     # buscador de aparatos contra el catálogo real
```

---

# TAREA PRINCIPAL: rehacer la superficie del DAC

## El problema, en palabras del usuario

> "Como está ahorita suelta muchos datos que sí son correctos pero que al final no dejan de ser mucho texto. El usuario final lo que va a querer saber es qué tanto le falta para DAC, o por qué si ya se pasó del límite que se supone aún no está en DAC, qué cantidad se eliminará para su próximo recibo, y si sigue como sigue cuánto margen o colchón le quedará para el siguiente. Y todo sin mucho texto."

## Lo primero: **no toques el cálculo**

`app/src/main/java/app/recibozen/android/core/calculation/DacRisk.kt` (191 líneas) está bien construido y **ya calcula todo lo que el usuario pide**. Tiene 16 pruebas en `DacRiskCalculatorTest`. Implementa la ventana móvil de 365 días con prorrateo por traslape de periodos, anualización cuando el historial tiene huecos, y límite por tarifa (no un límite universal).

El problema es **exclusivamente de presentación**: `feature/plan/DacScreen.kt` tiene **778 líneas y 9 tarjetas**, y hay **~60 cadenas** de DAC, muchas párrafos completos.

Si al terminar borraste código de cálculo, te equivocaste de capa.

## Las 4 preguntas y el campo exacto que ya las responde

| Pregunta del usuario | Campo en `DacRiskSnapshot` |
|---|---|
| ¿Cuánto me falta para caer en DAC? | `annualMarginKwh` (kWh en el año móvil) y `marginMonthlyKwh` |
| Si ya me pasé, ¿por qué aún no estoy en DAC? | `level` = `ABOVE_LIMIT` vs `ALREADY_DAC`, más `hasCompleteHistory` y `coveredDays` |
| ¿Qué cantidad se elimina en mi próximo recibo? | `expiringBeforeNextBillKwh` (y `retainedBeforeNextBillKwh`) |
| Si sigo así, ¿cuánto colchón me queda? | `projectedAnnualMarginKwh`, `recommendedNextBillMaxKwh`, `wouldExceedWithProjection` |

**Todo está calculado y todo está plumbeado hasta `DacUiState`.** Lo que falta es decidir qué se muestra y qué se borra.

## Lo que quiero que construyas

Una pantalla que se pueda leer **de un vistazo, sin scroll, para responder las 4 preguntas**. Mi propuesta de estructura, discútela si tienes una mejor:

1. **Un número grande arriba: el colchón.** "Te quedan **X kWh** antes del límite" o "Estás **X kWh** por encima". Con una barra que muestre acumulado vs. límite anual. Nada más.

2. **La respuesta a "¿por qué no estoy en DAC todavía?" — una sola línea, solo cuando `level == ABOVE_LIMIT`.** Esta es la pregunta que más frustra y hoy está enterrada. La respuesta honesta es corta: *el límite se evalúa sobre una ventana móvil y quien reclasifica es CFE, no la app*. Una línea, no un párrafo. Y **no la conviertas en una promesa**: no digas "todavía no te reclasifican", di lo que sabes.

3. **El próximo recibo, en tres cifras y cero prosa.** Algo como:

   ```
   Salen del año móvil    −340 kWh
   Proyección del recibo  +410 kWh
   Colchón después         −70 kWh   ← en rojo si wouldExceedWithProjection
   ```

   Ese bloque solo, ya responde las preguntas 3 y 4 mejor que las seis cadenas actuales.

4. **"Máximo recomendado: X kWh"** (`recommendedNextBillMaxKwh`) como la única acción con número.

Todo lo demás —explicación de la ventana móvil, límites por tarifa, tramos, el historial cubierto— va **detrás de un "¿Cómo se calcula?"** que se despliega, no en la pantalla principal.

## Restricciones que no se negocian al recortar

- **Recortar texto no es recortar honestidad.** La compresión correcta es *menos números, más grandes*, no *los mismos números con afirmaciones más seguras*. La línea de que CFE es quien decide **tiene que sobrevivir**, corta.
- **`UNKNOWN` sigue siendo `UNKNOWN`.** Con historial incompleto (`hasCompleteHistory == false`), la pantalla no puede dar un veredicto. Hoy lo maneja bien; no lo pierdas al simplificar.
- **`ALREADY_DAC` es otro flujo**: ahí la pregunta no es "cuánto me falta" sino "cuánto tengo que bajar para salir". Ya existe (`dac_exit_*`). Aplícale el mismo criterio de recorte.
- **Borra las cadenas que dejes sin usar.** Hay una verificación de recursos de texto sin usar que debe seguir en 0. No dejes cadenas huérfanas ni campos de UiState sin consumir — este proyecto ha tenido ese defecto antes y se corrigió a propósito.

## Verificación de esta tarea

- Pruebas nuevas para cualquier lógica de presentación que agregues (formateo de signos, elección de mensaje por `level`).
- `DacRiskCalculatorTest` debe seguir en 16 pruebas pasando **sin modificarlo**. Si tuviste que tocarlo, cambiaste el cálculo y hay que revisarlo.
- Cuenta las líneas de `DacScreen.kt` antes y después, y las cadenas `dac_*` antes y después. Repórtalo.
- **Di explícitamente qué no pudiste verificar.** El proyecto no tiene pruebas de UI de Compose, así que el resultado visual solo lo confirma el teléfono.

---

# Pendientes secundarios (en orden)

1. **Esquema `DeviceEvidence`** — `value + unit + source + retrievedAt + confidence + methodology`, con regla explícita cuando dos fuentes se contradicen. Es la base para la captura manual de kWh/año desde la etiqueta NOM, que es lo que de verdad cubre las marcas mexicanas ausentes del dataset (medido: Mabe 2 modelos, Acros/IEM/Koblenz/Winia/Mirage/Atvio/Easy = 0 de 9,305).
2. **Captura de kWh/año desde la etiqueta NOM** como camino de primera clase al dar de alta un aparato, no como plan B.
3. **Presupuesto inverso** — "no quiero pagar más de $900": kWh disponibles, ritmo diario necesario, acciones del inventario ordenadas por impacto. Piezas existentes: `ConsumptionPaceCalculator`, `TariffEngine`, `ApplianceAdviceEngine`.
4. **Anomalías en el consumo base nocturno** contra su propio histórico (`OvernightBaseline` ya existe).
5. **Auditoría visual** — contraste claro/oscuro, texto grande, insets. Requiere capturas del usuario.
6. **Google Play Billing** — el backend responde `503 play_verification_not_configured`. Bloquea cobrar Plus. Depende de que el usuario cree la cuenta de Play Console.
7. **Worker de Cloudflare + IA** — al final. Depende de (1), de la cuenta de Cloudflare del usuario y de poder cobrar. El alcance de IA quedó reducido a: consejos de ahorro, configuración de equipos, consumo, predicción de costo y alta rápida de aparatos. **Sin identificación por cámara/imagen** — el usuario descartó ese alcance.
8. Tabla `appliance_references` sin usar: aliméntala o quítala.
9. Política de privacidad publicada en una URL pública (Play la exige; la pantalla dentro de la app no cuenta).

# Bugs abiertos pendientes de confirmar en el teléfono

Tres arreglos entregados que **nadie ha verificado todavía en dispositivo**. Si el usuario reporta que alguno sigue mal, empieza por ahí:

- Paso 3 del tutorial (DAC): debía hacer scroll y resaltar la tarjeta. La tarjeta vive en un `item {}` de un `LazyColumn` y no se componía; se agregó `RzCoachLazyAnchors`.
- La tarjeta del tutorial no debía quedar bajo la barra de navegación del sistema (`safeDrawingPadding`).
- El tutorial iniciado desde Configuración debía llegar del paso 1 al 7 sin regresar. Causa: `popUpTo(Home) { saveState = true }` guardaba Perfil y lo restauraba.
- Botón **Inicio** de la barra inferior: iba a Historial. Causa: la misma operación guardaba y restauraba la pila en la misma llamada. Se cambió a `popBackStack(Routes.Home)`.

# Contexto de negocio

El usuario es estudiante, es su primera app, y necesita al menos 30% de margen. Plan Plus considerado a $69 MXN con IA. Está por abrir la cuenta de Google Play Console ($25 USD). Para cuentas personales nuevas Google exige **12 testers opted-in continuos durante 14 días en prueba cerrada** (la interna **no** cuenta) antes de poder solicitar producción. Ese reloj es la restricción de calendario más larga del proyecto.

# Cómo entregar

El código vive en la máquina del usuario. Trabaja, compila y verifica; después copia los archivos cambiados a su disco y **confirma con checksums** que llegaron idénticos. No hagas commit ni push. Dile qué archivos tocó, qué verificaste y qué no pudiste verificar.
