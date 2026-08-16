# ReciboZen — Errores de compilación corregidos y verificación completa

Monté un SDK de Android en mi contenedor para poder compilar de verdad. Ya no estoy adivinando: todo lo que sigue está verificado con el compilador.

---

## 1. Los errores

Fueron **4**, todos míos, todos de la tercera ronda:

| Archivo | Línea | Error | Causa |
|---|---|---|---|
| `core/components/RzCoachMark.kt` | 147 | `None of the following candidates is applicable: delay(Long) / delay(Duration)` | Declaré `POLL_MS` y `ANCHOR_TIMEOUT_MS` como `Int`; `delay()` solo acepta `Long`. |
| `feature/appliances/AppliancesScreen.kt` | 428 | `Unresolved reference 'Alignment'` | Al reescribir `InventoryAdviceSummary` usé `Alignment.CenterVertically` sin importarlo. |
| `feature/appliances/AppliancesScreen.kt` | 429 | `Unresolved reference 'Icon'` | Faltaba `androidx.compose.material3.Icon`. |
| `feature/appliances/AppliancesScreen.kt` | 442 | `Unresolved reference 'Icon'` | Igual que el anterior. |

Los tres de `AppliancesScreen` son el mismo descuido: cambié el contenido de un composable sin revisar qué imports necesitaba lo nuevo. Mi verificación por balance de llaves y paréntesis nunca iba a detectar un import faltante — era una comprobación estructural, no semántica. Con compilador esto no vuelve a pasar.

**Corrección aplicada:** `POLL_MS = 60L`, `ANCHOR_TIMEOUT_MS = 2_400L`, `var waited = 0L`, y los dos imports en `AppliancesScreen.kt`.

---

## 2. Verificación real

| Comprobación | Resultado |
|---|---|
| `./gradlew compileDebugKotlin` | **BUILD SUCCESSFUL** |
| `./gradlew testDebugUnitTest` | **129 pruebas · 0 fallos · 0 errores** |
| `./gradlew assembleDebug` | **BUILD SUCCESSFUL** — `app-debug.apk`, 118 MB |
| `./gradlew lintDebug` | **BUILD SUCCESSFUL** — 0 errores, 71 avisos |
| Pruebas backend (`node --test`) | **21/21** |

Las 129 pruebas incluyen las 24 que agregué en las rondas anteriores: fronteras de DAC, ritmo entre lecturas, periodo que avanza, precedencia de tarifas, tutorial persistente y las 6 de Plan.

---

## 3. Lo que encontró lint y aproveché para arreglar

Lint no reportó ningún error, pero sí avisos accionables que ya corregí:

| Aviso | Qué era | Corrección |
|---|---|---|
| `ModifierFactoryExtensionFunction` | `rememberRzCoachAnchor(id)` devolvía un `Modifier` sin ser extensión de `Modifier` | Ahora es `Modifier.rzCoachAnchor(id)`, que además es más idiomático en los 6 puntos donde se usa |
| `UnusedResources` x7 | Los colores de la plantilla de Android Studio (`purple_200`, `teal_700`, …) que nunca se usaron | `colors.xml` vacío |
| `UnusedResources` x1 | `string-array appliances_empty_suggestion_items` — la lista de aparatos sugeridos de la pantalla vacía que ya no existe | Eliminado |
| `RedundantLabel` | `android:label` repetido en la `Activity` | Quitado del manifiesto |
| `UseKtx` x2 | `Bitmap.createBitmap(...)` y `Uri.parse(...)` | `androidx.core.graphics.createBitmap` y `String.toUri()` |

Los avisos que quedan y **no** vale la pena tocar:

- **`Typos` x7** — todos son la palabra “anual”. Lint la corrige contra un diccionario en inglés (“annual”). Está bien escrita en español; es un falso positivo.
- **`PluralsCandidate` x55** — sugiere convertir a `<plurals>` cadenas con números, tipo “%d kWh”. En español la mayoría no cambia de forma, y ya convertí las dos que sí importaban (lecturas y acciones de ahorro).
- **`GradleDependency` / `NewerVersionAvailable` / `AndroidGradlePluginVersion` x9** — hay versiones más nuevas de algunas dependencias. Es una decisión tuya, no un defecto.

---

## 4. Sobre el APK

Se genera correctamente. El de mi contenedor pesa 118 MB (debug sin minificar, con ML Kit incluido), demasiado para transferirlo por aquí y sin utilidad para ti: ahora que compila, el tuyo sale en segundos.

```
./gradlew assembleDebug
app/build/outputs/apk/debug/app-debug.apk
```

**Tu `local.properties` no lo toqué.** Usé una copia local apuntando a mi SDK y la excluí del paquete; el archivo en tu disco sigue apuntando a `C:\Users\emili\AppData\Local\Android\Sdk`.

---

## 5. Archivos de esta corrección

```
app/src/main/java/com/recibozen/app/core/components/RzCoachMark.kt
app/src/main/java/com/recibozen/app/navigation/BottomNavBar.kt
app/src/main/java/com/recibozen/app/feature/home/HomeScreen.kt
app/src/main/java/com/recibozen/app/feature/plan/PlanScreen.kt
app/src/main/java/com/recibozen/app/feature/appliances/AppliancesScreen.kt
app/src/main/java/com/recibozen/app/feature/history/HistoryScreen.kt
app/src/main/java/com/recibozen/app/feature/readings/ReadingPhotoScreen.kt
app/src/main/java/com/recibozen/app/core/importer/CfePdfDocumentExtractor.kt
app/src/main/res/values/colors.xml
app/src/main/res/values/strings_appliances.xml
app/src/main/AndroidManifest.xml
```

---

## 6. Lo que sigue sin poderse verificar aquí

Compilar no es lo mismo que verse bien. Sigue pendiente comprobar en un dispositivo:

- Que el recorte del velo del tutorial se dibuje (depende de `graphicsLayer(alpha = 0.99f)` para que `BlendMode.Clear` recorte en vez de pintar negro).
- Que el scroll automático deje la tarjeta DAC completamente visible antes de resaltarla.
- Contraste en tema claro y oscuro, texto grande, insets con barra de 3 botones, teclado abierto.

Eso necesita ejecutar la app, no compilarla. Si me pasas capturas de las pantallas principales en ambos temas, sí puedo auditarlas.
