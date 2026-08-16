# ReciboZen — Ronda 5

Todo verificado con compilador: **140 pruebas · 0 fallos · APK generado · lint sin errores**.

---

## 1. Gradle 9.7.0 — sí conviene, y ya está

No opiné: lo probé. Cambié el wrapper a 9.7.0 y corrí la suite completa.

| Prueba | Resultado |
|---|---|
| `compileDebugKotlin` | OK |
| `testDebugUnitTest` | 140/140 |
| `assembleDebug` | APK generado |
| Advertencias nuevas de Kotlin | **0** |

Compatible con tu AGP 9.3.1 y con Kotlin 2.2.10. **Lo dejé aplicado.** La primera vez que compiles va a descargar la distribución nueva (~130 MB), y de ahí en adelante igual que siempre.

Nota: durante mis pruebas vi un fallo raro (`Could not close incremental caches`) que **no** era de Gradle 9.7 — fue por lanzar dos builds encima. Si te sale, `./gradlew --stop` y borra `app/build/kotlin`.

---

## 2. Filtros de Aparatos

Diagnóstico de las dos cosas que reportaste:

**Los espacios al final.** La lista era `[Mayor gasto, Habitación, En reposo, Info incompleta] + espacios`. Con los 6 espacios por defecto quedaban 10 chips en una sola fila con scroll horizontal, así que los espacios —lo que más se usa— quedaban fuera de pantalla.

**La pantalla se movía al presionarlos.** Los chips vivían **dentro** del scroll vertical. Al filtrar, la lista de abajo cambia de altura, el contenido total se reacomoda y la página salta bajo el dedo.

Además había un problema de fondo: “Mayor gasto” es un **orden**, “En reposo” y “Falta info” son **filtros de atributo**, y los espacios son **filtro de lugar**. Los tres compartían un solo índice exclusivo (`filterIndex - 4`, con número mágico), así que no se podía pedir “los de la cocina, ordenados por gasto”: elegir un cuarto reseteaba el orden en silencio.

**Cómo quedó:**

- Dos ejes independientes: `selectedSpaceId` y `selectedSort`.
- Dos filas de chips, **fijas arriba de la lista, fuera del scroll**. Presionar un chip ya no puede mover la página.
- Primero los espacios (“Toda la casa” + tus cuartos), porque “¿de qué cuarto?” es la pregunta que la gente hace. Debajo, el orden.
- Tocar el espacio ya seleccionado lo deselecciona.
- Etiquetas más cortas: “Por lugar”, “Falta info”.

2 pruebas nuevas: que espacio y orden son independientes, y que sin espacio seleccionado no se pierde ningún aparato.

---

## 3. Privacidad, términos y fotos (Google Play)

Pantalla nueva **Perfil → Privacidad y términos**, escrita para la persona, no para un checklist:

- **Qué guardamos y dónde** — todo en el teléfono, sin cuenta ni servidor.
- **Fotos del recibo y del medidor** — el texto se reconoce dentro del teléfono, sin conexión; la foto no se sube. Con **botón que de verdad borra** las fotos guardadas (`cache/reading_photos`, `cache/bill_photos`, `files/reading_photos`) y te dice cuántas borró. Las lecturas no se pierden.
- **Qué sí sale del teléfono** — solo tres cosas, todas cifradas: tarifas de CFE, coordenadas redondeadas de tu ciudad para NASA POWER, y la compra si activas Premium. Nunca recibos, lecturas, número de servicio ni fotos.
- **Permisos** — cámara solo al tomar foto, ubicación opcional, notificaciones opcionales.
- **Tus datos son tuyos** y **Sobre las estimaciones** (independiente de CFE, no es cotización oficial).

De paso: la fila “Privacidad” que ya existía en Perfil tenía `onClick = null` — **no hacía nada**. La quité y la reemplacé por esta.

---

## 4. Apoyar el proyecto

El botón abría `paypal.com` (la portada). Ahora abre **`https://paypal.me/KALIJOKER`** directo, con la cuenta ya puesta, y abre la app de PayPal si la tienes. El botón de copiar ahora copia el enlace, no un correo.

---

## 5. Reportar un problema

Iba a `emilianohdzv5@gmail.com`. Ahora va a **`emilianohdzvi@gmail.com`**, el que confirmaste.

Cómo funciona: abre tu app de correo con asunto y cuerpo ya armados, incluyendo versión de app y de Android. **No es envío automático desde un servidor** — tú das enviar. Para envío directo haría falta un endpoint en el backend, y prefiero decírtelo a que creas que ya sale solo.

---

## 6. Datos por suministro

Confirmado el problema: `goalMxn`, `meterType` y `location` eran **globales**. Con Plus (2 suministros) o Familiar (5), cambiar de suministro dejaba la meta del anterior, el mismo tipo de medidor y la misma ciudad — y la ciudad alimenta el preestudio solar, así que dimensionabas paneles de una casa con la irradiancia de otra.

`dacReferenceTariffsByService` ya era por servicio: el patrón existía y no se había aplicado al resto.

**Ahora:** `goalMxnByService`, `meterTypeByService` y `locationByService`, persistidos. Al cambiar de suministro se cargan los del suministro elegido. El nombre del hogar y tu nombre siguen globales, que es lo correcto.

**Migración sin pérdida:** el primer cambio a un suministro adopta lo que ya tenías configurado, así que subir a Plus no borra la configuración original.

---

## 7. Agregar al plan: ¿mueve algo?

Tu duda tenía razón de ser. Lo verifiqué: de ~10 tipos de consejo, **solo el de consumo en reposo trae ahorro estimado** (`estimatedSavingsKwh`/`Mxn`). Los de marca/modelo y los de categoría no traen cifra, así que al agregarlos la proyección no se movía ni un peso — y el botón decía “Agregar a mi plan” igual, prometiendo un cambio que no llegaba.

**Cómo quedó** (elegiste la opción honesta, y estoy de acuerdo):

- Acción **con** ahorro calculado → botón “Agregar a mi plan”, sí resta de la proyección.
- Acción **sin** cifra → botón “Darle seguimiento” y una línea clara: *“Esta no cambia el pronóstico todavía: el efecto aparecerá en tus próximas lecturas.”*
- El encabezado ahora dice cuánto restan de verdad: *“5 acciones · 2 en seguimiento · $64 menos en la proyección”*.

No inventé porcentajes de ahorro por categoría. Sería exactamente lo que pediste no hacer.

---

## 8. Guía de estados y soluciones

Era un muestrario de diseño: mostraba **todas** las tarjetas de estado juntas, siempre, sin relación con el estado real de la app, y la de “lectura dudosa” traía dos botones simulados donde “Guardar” te mandaba a Inicio. Con razón no sabías qué era.

El **contenido** sí es útil (qué significa “calculando”, qué hacer sin conexión, qué es una lectura dudosa), así que no la borré: la convertí en guía de referencia.

- Se llama **“¿Qué significa cada aviso?”**
- Entra desde Ayuda, donde ya estaba.
- Fuera los botones simulados; en su lugar, “Qué hacer” en texto.
- El único botón que queda es el que sí sirve: registrar una lectura.

---

## 9. Verificación

| Qué | Resultado |
|---|---|
| Gradle | **9.7.0** |
| `testDebugUnitTest` | **140 · 0 fallos** |
| `assembleDebug` | APK generado |
| `lintDebug` | 0 errores, 73 avisos |
| Recursos de texto sin usar | 0 |
| Backend | 21/21 |

Los 73 avisos son los de siempre: 58 `PluralsCandidate` (en español la mayoría no cambia de forma), 7 `Typos` que son la palabra “anual” contra un diccionario en inglés, y 8 de versiones de dependencias.

---

## 10. Pendientes

1. **Auditoría visual** — contraste claro/oscuro, texto grande, insets con barra de 3 botones, y que el recorte del velo del tutorial se dibuje. Con capturas lo audito.
2. **Envío directo de reportes** — hoy abre tu app de correo. Un endpoint en el backend lo haría automático.
3. **Verificación real de acciones del plan** — el estado “comprobado” exige separar el efecto de la acción del de la temperatura. Es atribución, no UI.
4. **Inicio de verano y región DAC por localidad** — desbloquea el catálogo de CFE para 1A–1F.
5. **Google Play Billing + Play Integrity en backend.**
6. **Tabla `appliance_references`** — alimentarla o quitarla.
7. **Política de privacidad publicada en web** — Google Play pide una URL pública además de la pantalla en la app. El texto de la pantalla te sirve como base.
