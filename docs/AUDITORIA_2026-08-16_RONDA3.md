# ReciboZen — Tercera ronda (Plan reconstruido y tutorial navegable)

Continuación de `AUDITORIA_2026-08-16.md` y `AUDITORIA_2026-08-16_RONDA2.md`. Sin commits, sin push.

---

## 1. El diagnóstico de Plan era peor de lo que parecía

Tu instinto fue correcto, pero la causa no era “le faltan cosas”. Al rastrear el código:

```
ReciboZenSessionState.planActions: List<PlanAction> = emptyList()
```

**Nada en toda la app escribía esa lista.** El decodificador incluso hacía `defaults.planActions.map { ... }`, o sea que mapeaba sobre una lista vacía. Los únicos usos de `DemoReciboZenData.planActions` estaban en los `@Preview`.

Consecuencias para un usuario real:

- La sección “Sugerencias para ti” renderizaba su título y **nada debajo**, siempre.
- `PlanSummaryCard` repetía la proyección que ya es el encabezado de Inicio.
- `billProjection` restaba los ahorros de las acciones en seguimiento: siempre 0.
- En la ficha de cada aparato, `onToggleRecommendation()` no hacía nada, porque `recommendationActionId()` solo mapeaba los ids `"minisplit"` y `"ps5"` a acciones de demostración; para cualquier aparato real devolvía `null`.

Mientras tanto `ApplianceAdviceEngine.rankInventory()` —que sí produce recomendaciones priorizadas, con ahorro estimado y nivel de evidencia, y ya tiene pruebas— lo consumían Aparatos y DAC. La pestaña cuyo trabajo entero es “plan de ahorro” era la única que no lo usaba.

---

## 2. Plan = centro de acciones

### Cambio de modelo de datos

Se elimina `planActions: List<PlanAction>` del estado persistido y se reemplaza por:

```kotlin
val trackedActionIds: Set<String> = emptySet()
```

**Solo se persiste la decisión del usuario, nunca una copia de la recomendación.** Guardar la lista generada congelaría un consejo que debe cambiar cuando cambia el aparato, sus horas o la tarifa.

La lista se deriva en cada lectura:

```kotlin
override fun planActions(state): List<PlanAction> =
    ApplianceAdviceEngine.rankInventory(appliances(state), billingDays, limit = 8)
        .map { … status = if (id in state.trackedActionIds) TRACKING else SUGGESTED … }
```

El id de cada acción es `"$applianceId::$adviceId"`, porque el mismo consejo se repite entre aparatos: dos televisiones comparten el tip de reposo y compartirían el id.

### Qué muestra ahora cada acción

- **Título** con el aparato: “Televisión sala · apagar el reposo”.
- **Qué hacer**, en la descripción del motor, no solo el título.
- **Ahorro estimado**, del motor o convertido a pesos con la tarifa efectiva del periodo. Si no hay cifra confiable, se omite en vez de inventarla (`plan_action_meta_no_savings`).
- **Confianza derivada de la evidencia**: guía oficial de modelo o marca → alta; datos del propio aparato → media; consejo por categoría → baja.
- **Guía oficial** cuando existe, con su fuente.
- **Enlace al aparato** para editarlo desde ahí.

### Lo que quité en vez de fingirlo

`VerifiedActionCard` y el estado `VERIFIED` ya no se muestran. Nada podía producirlos, y atribuir honestamente una baja de consumo a una acción concreta requiere descartar temperatura y estacionalidad. Prefiero no tener la tarjeta a tenerla mintiendo. Queda como pendiente real.

### Duplicado eliminado en Aparatos

Aparatos tenía su propio bloque `InventoryAdviceSummary` con las mismas recomendaciones del motor. Ahora es una tarjeta compacta —“Tienes N acciones de ahorro”, con la primera de ejemplo— que lleva a Plan. Una sola lista, un solo lugar que mantener.

### Estados vacíos que sí dicen qué hacer

- Sin aparatos: “Agrega los aparatos de tu casa y aquí te decimos por dónde empezar” + botón que lleva a Aparatos.
- Con aparatos pero sin acciones claras: “Con lo que registraste no encontramos ahorros claros. Agrega más aparatos o revisa sus horas de uso.”

Antes ambos casos mostraban el mismo texto, que además pedía un recibo y una lectura aunque el problema fuera otro.

### Reparto final de responsabilidades

| Pestaña | Su trabajo |
|---|---|
| Inicio | Cómo voy en este periodo |
| Aparatos | Qué tengo y cuánto consume |
| Plan | Qué hago para gastar menos, más DAC y preestudio solar |
| Historial | La evidencia: recibos y lecturas |

---

## 3. Tutorial: scroll y navegación

Tenías razón en las dos cosas.

**El paso de DAC apuntaba mal** porque esa tarjeta está debajo del pliegue y el overlay resaltaba las coordenadas de un elemento fuera de pantalla.

**Cómo quedó resuelto.** El ancla dejó de ser solo observacional:

```kotlin
@Composable
fun rememberRzCoachAnchor(id: String): Modifier   // antes: Modifier.rzCoachAnchor(state, id)
```

Ahora combina `BringIntoViewRequester` con `onGloballyPositioned`. El tutorial puede pedir “tráeme este elemento a la vista” y el scroll sube por la cadena de scrollables, así que funciona igual en el `LazyColumn` de Inicio que en el `Column` con scroll de Plan. Al salir de la pantalla, el `DisposableEffect` borra el ancla: nunca se resalta una coordenada que ya no existe.

**Navegación entre pantallas.** Cada paso declara dónde vive:

```kotlin
RzCoachStep(RzTourAnchors.APPLIANCES_ADD, …, route = Routes.Appliances)
```

En cada cambio de paso el overlay: navega si hace falta → espera a que el ancla aparezca (hasta 2.4 s) → hace scroll → deja asentar → recorta el hueco. Si el elemento nunca aparece, **avanza solo** en vez de dejarte en una pantalla oscura. Mientras viaja, el velo se muestra sin hueco en lugar de recortar donde no debe.

**Recorrido de 7 pasos, de punta a punta:**

1. Encabezado de Inicio — qué te dice esta pantalla
2. Botón de registrar lectura
3. Tarjeta DAC *(con scroll)*
4. Barra de pestañas — cómo te mueves
5. Botón “Agregar aparato” *(navega a Aparatos)*
6. Plan *(navega a Plan)*
7. Importar recibo *(navega a Historial)*

El overlay ahora permanece visible en las cuatro pestañas, no solo en Inicio, y se oculta dentro de cualquier flujo apilado.

---

## 4. Última fuga de demostración

`AppearanceUiState` tenía como valores por defecto `$846`, `"18 jun a 18 ago"` y la meta de la demo. El ViewModel los sobrescribe siempre, así que solo aparecían un instante — pero eran cifras inventadas en un estado de producción. Ahora son ceros.

Fuera de eso, `DemoReciboZenData` solo se usa en `@Preview` y en la migración del esquema v1, que es su lugar legítimo.

---

## 5. Pruebas

`PlanActionsTest`, 6 pruebas nuevas:

- Un inventario vacío no produce plan, en lugar de sugerencias precargadas.
- El inventario produce acciones reales, todas ligadas a un aparato existente, todas con instrucción, ninguna arranca en seguimiento.
- Seguir una acción la marca y sobrevive al reinicio vía el codec.
- Volver a tocarla deja de seguirla.
- Borrar un aparato borra sus acciones en seguimiento: un ahorro no puede seguir restando para algo que ya no existe.
- Dos televisiones iguales no comparten el id de la acción.

| Qué | Resultado |
|---|---|
| Pruebas backend | **21/21 pasan** |
| Recursos de texto sin usar | **0** |
| Composables muertos | **0** |
| XML de los 14 `strings_*.xml` | OK |
| Balance estructural de los Kotlin modificados | OK |
| `./gradlew testDebugUnitTest assembleDebug` | **No ejecutado** |

---

## 6. Qué revisar primero al compilar

Esta ronda toca el modelo de datos persistido, así que el orden de revisión importa:

1. **Migración de estado.** `planStatuses` desapareció del JSON y entró `trackedActionIds`. Una instalación existente simplemente arranca sin acciones en seguimiento — no se rompe, pero vale confirmarlo.
2. **`rememberRzCoachAnchor` en `RowScope`.** En Aparatos se usa como `rememberRzCoachAnchor(...).weight(1f)`; `weight` es extensión de `RowScope` y ahí sí lo estamos, pero es el punto más frágil sintácticamente.
3. **`BringIntoViewRequester`.** Lo marqué con `@OptIn(ExperimentalFoundationApi::class)`. Si en tu versión de Compose ya es estable, saldrá una advertencia de opt-in innecesario, no un error.
4. **El recorte del velo**, igual que en la ronda anterior: depende de `graphicsLayer(alpha = 0.99f)`.

Si el tutorial da problemas, sigue siendo aislable: quitar el bloque `if (showTour)` de `ReciboZenApp.kt` no afecta nada de Plan.

---

## 7. Archivos de esta ronda

**Nuevos (1)**
```
app/src/test/java/com/recibozen/app/PlanActionsTest.kt
```

**Modificados (15)**
```
app/src/main/java/com/recibozen/app/ReciboZenApp.kt
app/src/main/java/com/recibozen/app/core/components/RzCoachMark.kt
app/src/main/java/com/recibozen/app/core/model/PlanAction.kt
app/src/main/java/com/recibozen/app/navigation/BottomNavBar.kt
app/src/main/java/com/recibozen/app/demo/repository/ReciboZenRepository.kt
app/src/main/java/com/recibozen/app/demo/repository/DemoReciboZenRepository.kt
app/src/main/java/com/recibozen/app/demo/repository/ReciboZenSessionState.kt
app/src/main/java/com/recibozen/app/data/repository/PersistentReciboZenRepository.kt
app/src/main/java/com/recibozen/app/feature/home/HomeScreen.kt
app/src/main/java/com/recibozen/app/feature/plan/PlanScreen.kt
app/src/main/java/com/recibozen/app/feature/appliances/AppliancesScreen.kt
app/src/main/java/com/recibozen/app/feature/appliances/ApplianceDetailScreen.kt
app/src/main/java/com/recibozen/app/feature/history/HistoryScreen.kt
app/src/main/java/com/recibozen/app/feature/settings/AppearanceScreen.kt
app/src/main/res/values/  (strings_appliances, strings_common, strings_plan)
```

---

## 8. Pendientes

1. **Verificación real de acciones.** Devolver el estado VERIFIED requiere comparar lecturas antes y después descartando temperatura y estacionalidad. Es un problema de atribución, no de UI.
2. **Inicio de verano y región DAC por localidad** — desbloquea el catálogo remoto para 1A–1F.
3. **Google Play Billing + Play Integrity en backend.**
4. **Tabla `appliance_references`** — alimentarla o quitarla del esquema.
5. **Auditoría visual real** — contraste, texto grande, insets, teclado. Sigue siendo lo único que no puedo verificar sin ejecutar la app.
