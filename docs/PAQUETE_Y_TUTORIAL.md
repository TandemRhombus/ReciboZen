# ReciboZen · Renombre de paquete + arreglo del tutorial

Verificado con compilador: **171 pruebas · 0 fallos · APK debug y release generados · lint sin errores · backend 21/21**.

---

## 1. `com.recibozen.app` → `app.recibozen.android`

### Primero, algo que deberías saber antes de aceptarlo

Para que Play Store te acepte, **lo único que tiene que ser único es el `applicationId`**. El paquete de Kotlin es interno y a Google le da igual. O sea que había una versión de este cambio que eran 2 líneas.

Hice el renombre completo de todos modos, y creo que es lo correcto **por el momento en que estamos**: todavía no publicas nada, no hay usuarios con la app instalada, no hay base de datos en campo, no hay `applicationId` firmado en Play. Hoy este cambio cuesta una hora y se verifica con el compilador. Después de publicar, el `applicationId` **ya no se puede cambiar nunca** y quedarte con el paquete viejo sería una inconsistencia permanente.

Si hubieras tenido usuarios, mi recomendación habría sido la contraria.

### Qué se movió

| | |
|---|---|
| `namespace` y `applicationId` en `app/build.gradle.kts` | ambos a `app.recibozen.android` |
| Archivos Kotlin | **129**, `package` e `import` reescritos |
| Directorios | `java/com/recibozen/app/` → `java/app/recibozen/android/` en `main`, `test` y `androidTest` |
| Backend | `ANDROID_PACKAGE_NAME` por defecto, `compose.billing.yaml` y las 3 pruebas de leases/Play |
| Docs | `README_HANDOFF.md`, `docs/SECURITY_HARDENING.md` |

El `AndroidManifest.xml` **no necesitó cambios**: ya usaba `${applicationId}.files` y `.MainActivity` en vez de escribir el paquete a mano. Eso ya estaba bien hecho.

### Un archivo que casi se me va

`app/proguard-rules.pro` tenía esto:

```
-keepnames class com.recibozen.app.data.local.ReciboZenDatabase
```

No estaba en el barrido inicial porque solo busqué en `app/src`, el backend y los Gradle. **Ese error no habría aparecido en debug**: proguard solo corre en release, así que la app habría compilado, pasado las 171 pruebas y generado APK sin protestar — y habría reventado en producción cuando Room no encontrara la clase.

Por eso además de la compilación normal corrí **`assembleRelease` completo con R8** (5m 45s). Pasa. El `output-metadata.json` de release confirma `applicationId: app.recibozen.android`.

Barrido final del repo: **cero ocurrencias** de `com.recibozen.app` fuera de `build/`.

### Lo que te toca a ti

- En Android Studio, **File → Invalidate Caches / Restart** la primera vez. El IDE se queda con el índice viejo.
- Si tienes la app instalada en tu teléfono, la nueva se instala **al lado** de la vieja, no encima. Son dos apps distintas para Android. Desinstala la anterior.
- Los datos guardados de la app vieja no se migran. Con la app sin publicar da igual, pero que no te agarre desprevenido.

---

## 2. El tutorial: eran tres bugs distintos

Los tres los reportaste juntos, pero tienen causas separadas.

### a) El paso del DAC nunca aparecía

**Causa.** La tarjeta del DAC es un `item {}` de un `LazyColumn`, cerca del final de Inicio. Un `LazyColumn` **no compone los elementos que están fuera de la pantalla**. El modificador que registra el ancla nunca corría, el tutorial no encontraba coordenadas, y a los 2.4 segundos el paso **se saltaba solo, en silencio**.

El `BringIntoViewRequester` que puse en la ronda anterior no podía arreglarlo: no puedes hacer scroll hacia un elemento que todavía no existe.

**Arreglo.** La pantalla ahora le dice al tutorial cómo buscar sus anclas dentro de la lista (`RzCoachLazyAnchors`). Cuando un paso no encuentra su objetivo, recorre la lista elemento por elemento hasta que el ancla se registra, y ahí sí hace el ajuste fino.

Va de uno en uno a propósito, no salta a un índice fijo: **el orden de las tarjetas de Inicio cambia según el modo de datos**, así que un índice fijo apuntaría a otra cosa según el estado de tu cuenta.

También bajé la espera inicial de 2.4 s a 0.9 s antes de empezar a buscar, así que además de funcionar se siente más rápido.

### b) Se encimaba con los botones del celular

**Causa.** El overlay se dibuja **fuera** del `Scaffold`, así que no recibía ningún inset del sistema. El velo cubría toda la pantalla —eso está bien y es a propósito— pero la tarjeta con "Siguiente" también, y con navegación de 3 botones quedaba debajo de ellos.

**Arreglo.** `safeDrawingPadding()` en la tarjeta, no en el velo. El velo sigue cubriendo todo; la tarjeta ahora respeta barra de estado y barra de navegación, en gestos y en 3 botones.

De paso arreglé otra cosa del mismo bloque: la tarjeta decidía ponerse arriba o abajo comparando contra **420 dp fijos**. En una pantalla chica eso la ponía encima del elemento que estaba explicando. Ahora compara contra la mitad de la altura real.

### c) Iniciarlo desde Configuración lo cancelaba en el paso 4-5

Este es el más interesante y era **dos bugs encadenados**.

**Causa 1 — la pila de navegación.** "Iniciar tutorial" en Perfil llamaba a `navigateToBottomTab(Home)`, que hace `popUpTo(Home) { saveState = true }`. Ese `saveState` **guarda la pantalla de Perfil** debajo de Inicio. Cuando el tutorial cambiaba de pestaña en el paso 5, la misma operación con `restoreState = true` **restauraba Perfil encima**. De ahí que aparecieras en Configuración.

No era el tutorial: era una operación de navegación pensada para cuando una persona toca pestañas, usada donde no correspondía.

**Arreglo.** Dos cosas:

- `replayTutorial` ahora hace `popBackStack(Home)` — vuelve a la pantalla de Inicio que **ya está en la pila**, sin guardar Perfil.
- El tutorial usa su propia operación (`navigateToTourTab`) sin `saveState` ni `restoreState`. Así el bug no puede volver aunque alguien agregue otra entrada al tutorial desde otra pantalla.

**Causa 2 — el paso se perdía.** El overlay solo se montaba si estabas en una de las 4 pestañas, y guardaba **el número de paso adentro de sí mismo**. Cualquier instante fuera de una pestaña lo desmontaba y **el tutorial reiniciaba desde el paso 1**.

**Arreglo.** Separé "el tutorial está activo" de "el tutorial se está dibujando". Ahora se mantiene montado mientras está activo y solo se oculta el velo cuando no estás en una pestaña. El paso ya no se pierde.

---

## 3. Pruebas nuevas

4 pruebas (`GuidedTourStepsTest`) que fijan lo que el compilador no puede ver:

- Todos los pasos apuntan a una ruta donde el tutorial **sí se dibuja**. Un paso apuntando a otro lado dejaría al usuario viendo una pantalla oscura sin tarjeta y sin salida.
- Todos los pasos usan un ancla declarada.
- Ningún paso repetido.
- Perfil **no** es ruta de pestaña, que es la premisa del arreglo (c).

**Lo que estas pruebas no cubren, y quiero que lo sepas:** el overlay es un `@Composable` y no hay pruebas de UI de Compose en el proyecto. Los tres arreglos están razonados sobre el código y compilan, pero **la confirmación de que se ven bien tiene que darla el teléfono**. Cuando lo pruebes, dime específicamente:

1. ¿El paso 3 (DAC) ahora hace scroll y resalta la tarjeta?
2. ¿La tarjeta del tutorial ya no queda debajo de los botones?
3. Iniciándolo desde Configuración, ¿llega del 1 al 7 sin regresarte?

---

## 4. Verificación

| Qué | Resultado |
|---|---|
| `testDebugUnitTest` | **171 · 0 fallos** (antes 167) |
| `assembleDebug` | APK · `applicationId = app.recibozen.android` |
| `assembleRelease` (R8 + proguard) | APK release generado |
| `lintDebug` | 0 errores, 70 avisos |
| Backend `node --test` | 21 · 0 fallos |
| `grep com.recibozen.app` en todo el repo | **0** |

---

## 5. Archivos

- `app/build.gradle.kts`, `app/proguard-rules.pro`
- Todo `app/src/{main,test,androidTest}/java/app/recibozen/android/` (129 + pruebas)
- `core/components/RzCoachMark.kt` — anclas dentro de listas, insets, colocación de la tarjeta
- `navigation/BottomNavBar.kt` — `navigateToTourTab`
- `ReciboZenApp.kt` — activo vs. visible
- `feature/home/HomeScreen.kt` — declara el ancla DAC dentro de la lista
- `feature/settings/ProfileScreen.kt` — reinicio del tutorial sin ensuciar la pila
- `test/GuidedTourStepsTest.kt` (nuevo)
- `backend/src/server.mjs`, `backend/test/*`, `backend/compose.billing.yaml`
