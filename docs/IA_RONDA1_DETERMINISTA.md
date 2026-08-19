# ReciboZen · Rama `IA-Implement` — primera entrega

Todo verificado con compilador: **167 pruebas unitarias · 0 fallos · 21 pruebas de backend · APK generado · lint sin errores**.

Nada de esto usa IA ni gasta un solo token.

---

## 1. Alcance, según lo que pediste

Quitado: identificación por cámara/imagen.

La IA queda solo para consejos de ahorro, configuración de equipos, consumo, predicción de costo y alta rápida de aparatos. Esta ronda es la parte **determinista** de esa lista: lo que se resuelve con aritmética y no debe pagarse por token.

---

## 2. El buscador de aparatos: de 0 falsos positivos, no de 96%

En la ronda anterior te dije que mi matcher mejorado pasaba de 56% a 96%, y luego te dije que no te vendía ese número porque 7 de 14 resultados eran basura. Aquí está el arreglo real.

**Lo que cambió, en reglas:**

1. **Sinónimos español → subcategoría del dataset.** Nadie escribe `aire_acondicionado_ventana_portatil`. Ahora `minisplit`, `clima`, `pantalla`, `refri`, `boiler`, `laptop` llegan a donde deben.
2. **Corrección de erratas contra las marcas reales del catálogo**, con dos candados: solo tokens alfabéticos de 5+ letras, y solo cuando la consulta tiene forma de "estoy nombrando un producto" (una sola palabra, o acompañada de un código de modelo o de una categoría).
3. **Candado de marca.** Si escribiste una marca y el catálogo no la tiene, **no se devuelve otra marca**. Se dice "todavía no tenemos Acros".
4. **Candado de código de modelo.** Si escribiste un modelo y existe, se devuelve ese, no los otros 290 de la misma marca.
5. **Sin ancla, la regla estricta se queda.** Sin marca ni categoría reconocida, sigue exigiendo que todos los tokens aparezcan. Eso es lo que impide que `bomba de agua` devuelva calentadores.

**Corrido contra el catálogo real de 9,305 modelos** (`python3 tools/ai-eval/verify_matcher.py`):

| | Antes | Ahora |
|---|---|---|
| Consultas útiles | 10 | **17** |
| **Falsos positivos** (marca equivocada) | **7** | **0** |
| "Todavía no tenemos esa marca" | 0 | 5 |
| Sin resultados | 13 | 8 |

Casos concretos:

| Consulta | Antes | Ahora |
|---|---|---|
| `whirpool` | 0 | 181, todos Whirlpool |
| `samsug rf28` | 0 | 4, todos Samsung RF28 |
| `Samsung BRB106003**` | 1 | 1 (no 291) |
| `pantalla lg 55 pulgadas` | 0 | 63, todos LG |
| `laptop hp` | 0 | 178, todos HP |
| `Mabe RMA1025` | 0 | 2 Mabe |
| `IEM refrigerador` | 4,535 **ninguno IEM** | 0 + "todavía no tenemos IEM" |
| `aire acondicionado mirage` | 441 **ninguno Mirage** | 0 + "todavía no tenemos Mirage" |
| `horno de microondas` | 60 | 0 |
| `bomba de agua` | 436 | 0 |

### Un error mío que este trabajo destapó

Mi primera versión corregía `horno` → **`Forno`**, que es una marca real de ENERGY STAR a una letra de distancia. `horno de microondas` devolvía 60 lavavajillas de una marca que nadie escribió. Lo detecté corriendo el script contra el catálogo real, no leyendo el código.

El arreglo son dos capas: una lista explícita de palabras que **nunca** son marca (horno, licuadora, plancha, ventilador, bomba, estufa…) y la regla de "solo corrijo erratas cuando la consulta tiene forma de nombre de producto". Hay una prueba unitaria que fija ese caso con la marca Forno dentro del fixture.

### Lo que sigue sin estar bien

- `computadora gamer` devuelve 1,693 computadoras y `boiler eléctrico` 688 calentadores. No hay marca pedida, así que técnicamente no es un falso positivo: es navegar la categoría. La pantalla corta a 80. **No lo cuento como resuelto.**
- Las marcas mexicanas siguen sin datos. El candado evita el daño, no llena el hueco. Ese hueco es el trabajo real de la IA o de un catálogo propio de etiquetas NOM.

---

## 3. "¿Por qué subió mi recibo?"

La función que más gente te va a agradecer, y cuesta cero.

Descompone la diferencia entre dos recibos en causas, **en pesos**, y cada peso cae en exactamente una causa:

| Causa | Qué mide |
|---|---|
| El periodo duró más días | Estira el recibo anterior al número de días del nuevo, al mismo ritmo diario |
| Cambió tu consumo diario | Los kWh de diferencia, al precio que **ya** pagabas |
| Cruzaste al escalón caro | Lo que esos mismos kWh costaron de más por subir de bloque |
| Cambiaron las tarifas | Diferencia de rates entre los dos periodos |
| **No lo podemos explicar** | Lo que el motor no reproduce: DAC, ajustes, adeudos, redondeos |

Decisiones que tomé y por qué:

- **La última fila existe a propósito.** Repartir el residuo entre las otras causas pondría un número al lado de una explicación falsa. Si tu recibo trae 400 pesos que el motor no genera, la app dice que no sabe de dónde salieron.
- **Los totales se restan sin redondear.** Redondear cada estimación antes de restar deja pesos huérfanos.
- **El escalón se mide con el precio del nivel más bajo de los dos.** Sin eso, un recibo que baja producía una línea de "escalón" con signo contrario al ahorro. Lo descubrí porque la prueba falló, no porque lo previera.
- **Hoy los dos recibos se cotizan con la tarifa vigente**, porque la app no guarda tablas de tarifas fechadas por periodo. Consecuencia honesta: si la tarifa cambió, ese efecto cae en "no lo podemos explicar", y si los dos recibos traen tarifas distintas la pantalla lo advierte explícitamente.

10 pruebas nuevas, incluyendo que las causas **suman exactamente** la diferencia real del recibo, que un recibo que baja se explica igual, y que sin fechas de periodo la duración nunca se inventa.

Se ve en **Análisis del periodo**, arriba de todo, en cuanto haya dos recibos importados.

---

## 4. Verificación

| Qué | Resultado |
|---|---|
| `testDebugUnitTest` | **167 · 0 fallos** (antes 157) |
| `assembleDebug` | APK generado |
| `lintDebug` | 0 errores, 74 avisos |
| Backend `node --test` | 21 · 0 fallos |
| `verify_matcher.py` sobre 9,305 modelos | 0 falsos positivos |

Los 74 avisos son los de siempre (59 `PluralsCandidate`, 7 `Typos` por "anual" contra diccionario inglés, 8 de versiones). Subieron 1 respecto a la ronda pasada: dos cadenas nuevas mías con `%d días` / `%d kWh` que lint quiere como plurales. No son errores.

---

## 5. Archivos

**Nuevos**

- `core/calculation/BillChangeExplainer.kt`
- `data/catalog/CatalogQuery.kt`
- `test/BillChangeExplainerTest.kt`, `test/CatalogSearchOutcomeTest.kt`
- `tools/ai-eval/verify_matcher.py`

**Modificados**

- `data/catalog/EnergyStarCatalog.kt` — `searchOutcome()` con las cinco reglas
- `feature/appliances/AppliancesEmptyScreen.kt` — el candado de marca aplica también al catálogo local
- `core/calculation/TariffEngine.kt` — total sin redondear y tarifa marginal
- `feature/period/PeriodAnalysis{UiState,ViewModel,Screen}.kt`
- `res/values/strings_appliances.xml`, `res/values/strings_period.xml`

---

## 6. Lo que falta de esta fase

1. **Esquema `DeviceEvidence`** (`value + unit + source + retrievedAt + confidence + methodology` y regla de conflicto entre fuentes). Es donde la IA va a escribir después. No lo empecé.
2. **Presupuesto inverso** ("no quiero pagar más de $900"). No lo empecé.
3. **Detección de anomalías** en el consumo base nocturno contra su propio histórico.
4. **Auditoría visual** de la pantalla nueva: contraste del rojo/verde de las cifras en tema claro y oscuro. No la puedo hacer sin ver la app corriendo.
5. Todo lo del Worker de Cloudflare sigue esperando tu Account ID y que tú corras `wrangler login`. Y **Google Play Billing sigue devolviendo 503**: sin eso no puedes cobrar Plus, con IA o sin ella.
