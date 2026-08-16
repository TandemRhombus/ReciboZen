# ReciboZen — Segunda ronda (UI, textos y tutorial)

Continuación de `docs/AUDITORIA_2026-08-16.md`. Sin commits, sin push.

---

## 1. Primero: cerré dos cabos que yo mismo dejé sueltos

En la ronda anterior te reporté que la app construía datos que nadie consumía. Al revisar mi propio trabajo, yo hice lo mismo dos veces:

- **`tariffRateOrigin()`** — lo agregué al repositorio y ninguna pantalla lo leía.
- **`CurrentBillingPeriod.isBeyondNextPeriod`** — lo agregué al modelo y ninguna pantalla lo mostraba.

Los dos ya están conectados:

| Dato | Dónde se ve ahora |
|---|---|
| `tariffRateOrigin()` | Nueva tarjeta **“De dónde salen las tarifas”** en *Cómo lo calculamos*, con tres mensajes distintos: de tu recibo, del catálogo de CFE, o referencia general con invitación a importar un recibo. Se subió al contrato `ReciboZenRepository` para que los ViewModels lo puedan leer. |
| `isBeyondNextPeriod` | Aviso en ámbar dentro de la tarjeta de proyección de Inicio: *“Las fechas son aproximadas: tu último recibo ya tiene varios periodos de antigüedad.”* |

---

## 2. Tutorial guiado

Sistema nuevo y reutilizable en `core/components/RzCoachMark.kt`:

- `RzCoachMarkState` guarda la posición en pantalla de cada elemento marcable.
- `Modifier.rzCoachAnchor(state, id)` registra un elemento como objetivo. Usa `onGloballyPositioned`, así que **no cambia el layout**: agregarlo no puede mover nada.
- `LocalRzCoachMarkState` lo distribuye por `CompositionLocal`, para no tener que pasar el estado por cada ruta y cada firma de composable.
- `RzCoachMarkOverlay` dibuja el velo oscuro con un **recorte redondeado sobre el elemento resaltado** (`BlendMode.Clear` sobre una capa `graphicsLayer`), la tarjeta de explicación, el contador “Paso X de Y” y los botones Saltar / Siguiente / Listo.

**Detalles de comportamiento:**

- La tarjeta se coloca abajo o arriba según dónde esté el elemento, para no taparlo.
- El overlay absorbe los toques: no se puede presionar el botón resaltado mientras se explica, ni cerrar el tutorial por accidente.
- Un paso cuyo elemento no está en pantalla **se salta** en lugar de apuntar al vacío.
- El velo está marcado con `clearAndSetSemantics {}` para que TalkBack no lo lea como contenido.

**Recorrido (6 pasos):** encabezado de Inicio → botón de registrar lectura → tarjeta DAC → pestaña Aparatos → pestaña Plan → pestaña Historial.

**Persistencia:** `tutorialCompleted` en el estado de sesión, guardado en DataStore. Arranca solo una vez, después del onboarding, y solo en Inicio. Saltar cuenta como terminado, así que no vuelve a insistir. Se puede repetir desde **Perfil → “Ver el tutorial otra vez”**, que además te regresa a Inicio para que empiece de inmediato.

Prueba nueva: `theGuidedTourIsPendingOnAFreshInstallAndSurvivesARestart` (pendiente en instalación nueva, sobrevive el reinicio vía el codec, y se puede reactivar).

**Alcance honesto:** el recorrido cubre Inicio y las cuatro pestañas. No entra a explicar los formularios internos de cada flujo (importar recibo, alta de aparato, preestudio solar). El sistema ya soporta eso: agregar un paso es una línea en `tourSteps` más un `rzCoachAnchor` en el elemento. Si quieres que lo extienda a esos flujos, dime cuáles.

---

## 3. Mejoras de UI y calidad de vida

| Cambio | Por qué |
|---|---|
| **El espacio se recuerda entre altas** | Al agregar un aparato, el selector ya viene con el espacio del último aparato que registraste. Registrar 8 cosas de la cocina ya no significa elegir “Cocina” 8 veces. Sin campo nuevo ni migración: se deriva del inventario existente. |
| **La estimación subió al inicio del diálogo** | Los kWh y el costo estaban hasta abajo, después de todos los campos. Ahora están arriba, en un bloque destacado, y se actualizan mientras editas. Aplica a los dos diálogos: catálogo y aparato personalizado. |
| **La cobertura del inventario ya no se recorta a 100%** | Cuando los aparatos declarados suman más de lo que midió el medidor, antes se mostraba “100%” y el usuario nunca se enteraba. Ahora, arriba de 105%, aparece en ámbar: *“Tus aparatos suman %d%% de lo medido… revisa las horas de uso.”* La barra sí se limita a 100% para no romperse. |
| **`billingDays` con una sola fuente** | La pantalla de alta de aparatos calculaba los días del periodo con su propia regla (61/30), distinta de la del resto de la app. Ahora usa `energySummary().billingDays`, igual que la proyección. |
| **Plural correcto en el contador de lecturas** | Ya no dice “1 lecturas”. |

**Ya estaba bien y no toqué:** las acciones destructivas (borrar aparato, borrar espacio) ya tenían diálogo de confirmación; el reposo ya aparece solo en aparatos donde aplica, arranca apagado y sus watts son editables; la procedencia del dato (fuente, familia de modelo, mercado no verificado, rango anual) ya se muestra en el diálogo de alta.

---

## 4. Textos

Criterio que aplicaste: lo más corto posible, pero **nunca a costa de que el usuario se pierda**.

### 4.1 Reescritos: 44 textos

Los que más lo necesitaban eran los que hablaban en voz de desarrollador — explicaban lo que la app **no** hace, en vez de ayudar a decidir:

| Recurso | Antes | Ahora |
|---|---|---|
| `appliances_real_empty_body` | “…ReciboZen no volverá a atribuirte un minisplit, refrigerador o consola de ejemplo.” | “Agrega los equipos que de verdad tienes en casa. Empieza por los que más usas.” |
| `home_empty_body` | “…Hasta entonces ReciboZen no mostrará montos, aparatos ni proyecciones de ejemplo.” | “Importa un recibo o registra una lectura del medidor para empezar.” |
| `plan_empty_body` | “…No mostraremos recomendaciones precargadas como si fueran tuyas.” | “Necesitamos un recibo, una lectura posterior y los aparatos que agregues.” |
| `dac_tariff_unavailable` | “…No usaremos una tarifa genérica como si fuera la tuya.” | “Importa un recibo con el desglose legible para ver estos tramos con tus cuotas reales.” |
| `home_data_quality_explanation` | “Todavía no lo llamamos proyección del periodo actual hasta tener una lectura posterior.” | “Con el PDF ya podemos comparar periodos. Para proyectar el actual falta una lectura posterior.” |
| `premium_security_note` | “…el backend valida el token, Play Integrity y la relación con esta instalación.” | “ReciboZen no recibe ni guarda los datos de tu tarjeta. Google Play procesa el pago.” |
| `appearance_meaning_note` | Explicaba la semántica de cada color del sistema de diseño (151 car.) | “El modo oscuro conserva los mismos colores y su significado.” |
| `donate_student_body` | 277 caracteres de justificación | 113 caracteres |

Esa primera frase de un usuario nuevo (“no volverá a atribuirte un minisplit de ejemplo”) es literalmente una nota de cambios: solo tiene sentido para quien conoció la versión anterior.

Los textos de más de 135 caracteres en uso pasaron de **51 a 7**, y ninguno de esos 7 pasa de 200.

### 4.2 Advertencias: acortadas, no eliminadas

Se conservan todas, más cortas:

- `solar_disclaimer` mantiene “No prometemos recibo en cero”.
- `dac_exit_actions_body` mantiene “No desconectes refrigeración, salud ni seguridad”.
- `dac_prediction_disclaimer` mantiene que CFE determina la clasificación final.
- `profile_disclaimer` mantiene que las lecturas se guardan en tu teléfono y que la app no está afiliada a ninguna empresa eléctrica.

### 4.3 Eliminados: 118 recursos

Los 118 huérfanos que te reporté como pendiente ya no están. Verifiqué contra todo el código Kotlin, todos los XML (incluido el manifiesto) y las pruebas; también confirmé que no hay búsquedas dinámicas de recursos (`getIdentifier`) que pudieran romperse.

Total de recursos: **1,028 → 910**, con **0 sin usar**. Los bloques más grandes que se fueron correspondían a pantallas del handoff de diseño que nunca se implementaron: `period_*` (22), `solar_refine_*` y `solar_reduce_*` (14), `onboarding_step0_*` y `onboarding_row_*` (17), `appliances_usage_*` (27).

---

## 5. Backend

`Strict-Transport-Security: max-age=31536000; includeSubDomains` en todas las respuestas. Era el último hallazgo bajo pendiente del backend.

---

## 6. Verificación

| Qué | Resultado |
|---|---|
| Pruebas backend | **21/21 pasan** |
| Sintaxis backend (`node --check`) | OK |
| XML de los 14 `strings_*.xml` | OK |
| Recursos de texto sin usar | **0** (antes 118) |
| Composables muertos | **0** |
| Balance estructural de los Kotlin modificados | OK |
| `./gradlew testDebugUnitTest assembleDebug` | **No ejecutado** |

**Riesgo de esta ronda:** es mayor que el de la primera, porque el sistema de coach marks es código nuevo de UI y no lo pude compilar ni ver renderizado. Lo que más conviene revisar en cuanto compiles:

1. **El recorte del velo.** El efecto depende de que `graphicsLayer(alpha = 0.99f)` fuerce una capa fuera de pantalla para que `BlendMode.Clear` recorte en vez de pintar negro. Si el hueco no aparece, es ahí.
2. **La posición del hueco.** Uso `boundsInRoot()`. Con `enableEdgeToEdge()` las coordenadas deberían coincidir con el overlay, que también ocupa la raíz, pero vale la pena confirmarlo en un equipo con barra de 3 botones.
3. **El paso de la pestaña Aparatos/Plan/Historial.** Las pestañas solo se registran como objetivo mientras la barra inferior está visible, que es el caso en Inicio.

Si algo de eso falla, el resto de los cambios es independiente: puedes quitar el bloque `if (showTour)` de `ReciboZenApp.kt` y todo lo demás sigue en pie.

---

## 7. Archivos de esta ronda

**Nuevos (1)**
```
app/src/main/java/com/recibozen/app/core/components/RzCoachMark.kt
```

**Modificados (17)**
```
app/src/main/java/com/recibozen/app/ReciboZenApp.kt
app/src/main/java/com/recibozen/app/navigation/BottomNavBar.kt
app/src/main/java/com/recibozen/app/demo/repository/ReciboZenRepository.kt
app/src/main/java/com/recibozen/app/demo/repository/DemoReciboZenRepository.kt
app/src/main/java/com/recibozen/app/demo/repository/ReciboZenSessionState.kt
app/src/main/java/com/recibozen/app/data/repository/PersistentReciboZenRepository.kt
app/src/main/java/com/recibozen/app/feature/home/HomeScreen.kt
app/src/main/java/com/recibozen/app/feature/home/HomeUiState.kt
app/src/main/java/com/recibozen/app/feature/home/HomeViewModel.kt
app/src/main/java/com/recibozen/app/feature/readings/HowWeCalculateScreen.kt
app/src/main/java/com/recibozen/app/feature/appliances/AppliancesEmptyScreen.kt
app/src/main/java/com/recibozen/app/feature/settings/ProfileScreen.kt
app/src/test/java/com/recibozen/app/HonestDataDefaultsTest.kt
backend/src/server.mjs
app/src/main/res/values/  (los 14 strings_*.xml + strings.xml)
```

---

## 8. Lo que sigue pendiente

Sin cambios respecto al reporte anterior, menos los dos que ya cerré:

1. **Inicio de verano y región DAC por localidad** — desbloquea el catálogo remoto para las tarifas 1A–1F.
2. **Google Play Billing + Play Integrity en backend.**
3. **Tabla `appliance_references`** — alimentarla o quitarla del esquema.
4. **Auditoría visual real** — contraste, texto grande, insets con barra de 3 botones, teclado abierto. Requiere ejecutar la app; sigue siendo lo único que no puedo verificar desde aquí.
