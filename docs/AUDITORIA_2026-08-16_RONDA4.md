# ReciboZen — Auditoría pantalla por pantalla (las 31)

Última ronda. Todo verificado con compilador: **138 pruebas, 0 fallos, APK generado, lint sin errores**.

---

## 1. Empiezo reconociendo el punto

Tenías razón: si no detecté lo de Plan por mi cuenta, no había auditado las pestañas. Esta vez construí un detector para esa clase exacta de defecto —campos de `UiState` que ningún ViewModel asigna nunca— y lo corrí sobre las 31 pantallas. Encontró **Balance**, con el mismo patrón que Plan.

También me equivoqué a media auditoría: di por muerta la pantalla “Estados y soluciones” porque ningún `ProfileScreen` la enlaza, la borré, y después vi que **sí** se llega desde el Centro de ayuda. La restauré. Lo anoto porque el error y la corrección son parte del trabajo.

---

## 2. Los tres hallazgos nuevos

### H-16 (Alta) — Balance: la prueba nocturna nunca se calculaba

`hasOvernightTest`, `overnightObservedKwh`, `overnightExpectedKwh` y `overnightUnexplainedKwh` **nunca se asignaban**. Resultado: la tarjeta siempre decía “todavía no existe una prueba nocturna guardada”, el botón te mandaba a registrar lecturas, y nada las volvía a mirar. Una función que te invita a hacer algo que la app jamás evalúa.

Era implementable de verdad, y es justo la detección de consumo fantasma que da sentido a esa pantalla. Nuevo `OvernightBaselineCalculator`:

- Busca **la noche más reciente**: dos lecturas manuales separadas 4–14 h, la primera entre 19:00 y 02:00, la segunda entre 04:00 y 11:00.
- Calcula la **carga base que el inventario justifica**: un aparato facturado por energía de periodo (refrigerador, router, bomba) aporta su potencia media —kWh ÷ días ÷ 24 h—, y uno con horario aporta **solo el reposo que el usuario declaró**, nunca uno supuesto.
- La diferencia es el consumo sin explicar.

Se niega a dar resultado cuando la evidencia no da: sin inventario contra qué comparar, con lecturas de PDF, con dos lecturas diurnas, o si el medidor retrocedió.

Ahora la tarjeta dice cosas como *“Anoche tu casa gastó 2.0 kWh en 8 horas: unos 250 W constantes. Tus aparatos registrados justifican 0.7 kWh (93 W). Sobran 1.3 kWh sin explicar.”* Y cuando cuadra, lo dice: *“Cuadra con lo registrado. No hay consumo fantasma a la vista.”*

**8 pruebas nuevas**, incluidas las de rechazo.

### H-17 (Media) — Donar: PayPal no llevaba a ningún lado

El botón “Abrir PayPal” abría `https://www.paypal.com/`, la portada. Para donar tenías que copiar un correo, entrar a PayPal y pegarlo tú.

Ahora el botón principal abre **`https://paypal.me/KALIJOKER`** directo, con la cuenta ya puesta, y abre la app de PayPal si está instalada. Copiar el correo queda como alternativa secundaria, no como el camino principal.

### H-18 (Media) — Análisis del periodo: faltaba la gráfica

Esa pantalla solo tenía una barra de progreso de días. Y `RzLineChart` —140 líneas de Canvas ya escritas y probadas— **no lo usaba nadie**: era código muerto. Los textos `period_chart_*` y `period_legend_*` que borré en la ronda 2 eran de esa gráfica que nunca se conectó.

Ahora hay una curva de consumo acumulado con tres series:

- **Real** (línea sólida): reconstruida interpolando entre tus lecturas reales. Se corta donde se acaban las lecturas.
- **Proyección** (guiones): continúa desde ahí hasta el cierre estimado.
- **Meta** (puntos): el ritmo que tu meta permite.

Entre dos lecturas el medidor solo da el total, así que ese tramo se dibuja recto: honesto sobre que no sabemos la forma dentro del hueco. Y la parte medida nunca se confunde con el pronóstico.

---

## 3. Las 31 pantallas, una por una

| # | Pantalla | Verdicto |
|---|---|---|
| 1 | Onboarding | Correcta. Pide meta y tarifa siempre; la tarifa no se infiere de la ubicación. |
| 2 | Inicio | Corregida en rondas 1–2: sin tarjetas muertas, top de aparatos real, periodo que avanza, aviso de fechas aproximadas. |
| 3 | Aparatos | Corregida en ronda 3: sin lista de consejos duplicada, ahora apunta a Plan. Espacio recordado entre altas. |
| 4 | **Plan** | **Reconstruida (ronda 3).** Acciones derivadas del motor sobre tu inventario. |
| 5 | Historial | Correcta. Gráfica de recibos con datos reales, importación por PDF y foto, unidad $/kWh. |
| 6 | Importar recibo | Correcta. Revisión editable campo por campo, deduplicación por SHA-256. |
| 7 | **Análisis del periodo** | **Gráfica agregada.** Antes solo barra de progreso. |
| 8 | Agregar aparato | Corregida en ronda 2: estimación arriba, espacio recordado, días del periodo de una sola fuente. |
| 9 | Espacios del hogar | Correcta. Crear/renombrar/eliminar con confirmación; al borrar reasigna a “Toda la casa”. |
| 10 | Detalle de aparato | Corregida en ronda 3: el botón de agregar al plan ya funciona para cualquier aparato, no solo los dos de demo. |
| 11 | Editar uso | Correcta. Horas o kWh directos, cantidad, marca/modelo, espacio, reposo. |
| 12 | Simulador “¿y si lo compro?” | Correcta. Usa los escalones de tu tarifa. |
| 13 | **Balance de consumo** | **Prueba nocturna implementada.** |
| 14 | Promedio y DAC | Correcta. Ventana móvil, límite por tarifa, margen, máximo del próximo recibo, plan de salida. |
| 15 | Preestudio solar | Correcta. Irradiancia real por ubicación, escenarios, cargas futuras, PDF. |
| 16 | Elegir método de lectura | Correcta. |
| 17 | Foto del medidor | Correcta. Cámara real + OCR on-device. |
| 18 | Foto no reconocida | Correcta. Reintentar o corregir. |
| 19 | Escribir lectura | Correcta. |
| 20 | Ajustar manecillas | Correcta. Matemática de ángulo→dígito con acarreo. |
| 21 | Confirmar lectura | Correcta. |
| 22 | Cómo lo calculamos | Mejorada en ronda 2: nueva tarjeta “De dónde salen las tarifas”. |
| 23 | Perfil | Corregida: fila “Ver el tutorial otra vez”, comentario obsoleto eliminado. |
| 24 | Mis suministros | Correcta. Límites por plan, validación de número de servicio. |
| 25 | Apariencia | Corregida en ronda 3: valores por defecto neutros en vez de las cifras de demo. |
| 26 | Estados y soluciones | Correcta. **Sí es alcanzable**, desde el Centro de ayuda. |
| 27 | Centro de ayuda | Correcta. |
| 28 | **Apoyar el proyecto** | **PayPal directo.** |
| 29 | Reportar un problema | Correcta. Solo envía versión de app y de Android. |
| 30 | Notificaciones | Correcta. Falla cerrado sin compra verificada. |
| 31 | Premium | Correcta. Paywall separado del SKU, backend responde 503 mientras Play no esté configurado. |

Barrido adicional sobre las 31: ningún `TODO`, ningún dato de demostración en rutas de producción, ningún botón sin acción fuera de los `@Preview`.

---

## 4. Gráficas: inventario completo

| Gráfica | Dónde se usa | Estado |
|---|---|---|
| `RzScrollableBarChart` | Inicio (recibos históricos), Historial | Datos reales |
| `RzDualBarChart` | Solar (consumo vs producción mensual) | Datos reales |
| `RzStackedBarRow` | Balance, DAC, Detalle de aparato | Datos reales |
| `RzMarkedProgress` | DAC (posición contra el límite) | Datos reales |
| `RzLinearProgress` | Inicio, Aparatos, Análisis del periodo | Datos reales |
| `RzConfidenceIndicator` | Inicio | Datos reales |
| **`RzLineChart`** | **Análisis del periodo** | **Estaba muerto; ahora en uso** |
| `RzBarChart` | — | Sigue sin usarse. Lo dejé: `RzScrollableBarChart` lo cubre y borrarlo no aporta. |

---

## 5. Verificación

| Qué | Resultado |
|---|---|
| `./gradlew compileDebugKotlin` | **BUILD SUCCESSFUL** |
| `./gradlew testDebugUnitTest` | **138 pruebas · 0 fallos** (eran 129) |
| `./gradlew assembleDebug` | **APK generado** |
| `./gradlew lintDebug` | **0 errores**, 73 avisos |
| Pruebas backend | **21/21** |
| Recursos de texto sin usar | **0** |

Los 73 avisos de lint son los mismos de antes: 57 `PluralsCandidate` (en español la mayoría no cambia de forma), 7 `Typos` que son la palabra “anual” corregida contra un diccionario en inglés, y 9 de versiones de dependencias más nuevas —decisión tuya, no defecto.

---

## 6. Archivos

**Nuevos (2)**
```
app/src/main/java/com/recibozen/app/core/calculation/OvernightBaseline.kt
app/src/test/java/com/recibozen/app/OvernightBaselineTest.kt
```

**Modificados (12)**
```
app/src/main/java/com/recibozen/app/feature/plan/BalanceScreen.kt
app/src/main/java/com/recibozen/app/feature/support/SupportScreens.kt
app/src/main/java/com/recibozen/app/feature/period/PeriodAnalysisScreen.kt
app/src/main/java/com/recibozen/app/feature/period/PeriodAnalysisUiState.kt
app/src/main/java/com/recibozen/app/feature/period/PeriodAnalysisViewModel.kt
app/src/main/java/com/recibozen/app/feature/settings/ProfileScreen.kt
app/src/main/java/com/recibozen/app/navigation/Routes.kt
app/src/main/java/com/recibozen/app/navigation/ReciboZenNavHost.kt
app/src/main/res/values/strings_plan.xml
app/src/main/res/values/strings_support.xml
app/src/main/res/values/strings_period.xml
app/src/main/res/values/strings_settings.xml
```

---

## 7. Pendientes reales

Lo que queda no lo puedo cerrar desde aquí:

1. **Auditoría visual** — contraste claro/oscuro, texto grande, insets con barra de 3 botones, teclado abierto, y que el recorte del velo del tutorial se dibuje. Compilar no es verse bien. Con capturas de las pantallas principales en ambos temas sí puedo auditarlo.
2. **Verificación real de acciones del plan** — devolver el estado “comprobado” exige separar el efecto de la acción del de la temperatura y la estacionalidad. Es atribución, no UI.
3. **Inicio de verano y región DAC por localidad** — desbloquea el catálogo remoto de CFE para las tarifas 1A–1F.
4. **Google Play Billing + Play Integrity en backend.**
5. **Tabla `appliance_references`** — alimentarla o quitarla del esquema.
