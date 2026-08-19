# Prueba de catálogo: qué encuentra ReciboZen hoy y dónde entra la IA

Corrida sobre los catálogos reales: 9,305 modelos ENERGY STAR + 148 del catálogo propio.

---

## 1. Primero, una corrección a lo que te dije ayer

Ayer te dije que el trabajo de la IA era *"normalizar texto y hacer matching contra tu catálogo"*, y que por eso un modelo barato bastaba.

**Los datos dicen que me equivoqué a medias.** El matching es la mitad del problema. La otra mitad es que **tu catálogo no tiene México**.

ENERGY STAR es un dataset de Estados Unidos. Conté las marcas:

| Marca | Modelos en el catálogo |
|---|---|
| Mabe | **2** |
| Acros | **0** |
| IEM | **0** |
| Koblenz | **0** |
| Winia / Daewoo | **0** |
| Mirage | **0** |
| Atvio | **0** |
| Easy | **0** |

Frigidaire tiene 342 y Midea 442, pero Mabe —probablemente la marca de refrigeradores más vendida en México— tiene dos. Eso no se arregla con un mejor buscador.

---

## 2. Qué encuentra el buscador actual

Le pasé 30 consultas escritas como las escribe un usuario mexicano:

**17 de 30 devuelven algo. 56%.**

Las que fallan se parten en dos grupos muy distintos:

**Grupo A — el dato existe, falla la búsqueda (6 casos):**
`televisión samsung` · `pantalla lg 55 pulgadas` · `laptop hp` · `minisplit lg inverter 12000` · `whirpool` (typo) · `samsug rf28` (typo)

Causa: el matcher exige que **todos** los tokens aparezcan como substring. No hay sinónimos español→categoría, no hay tolerancia a erratas, y no hay ranking parcial. Las subcategorías se llaman `televisor`, `computadora`, `aire_acondicionado_ventana_portatil`; nadie escribe eso.

**Grupo B — el dato no existe en ningún catálogo (7 casos):**
`Mabe RMA1025` · `Acros AT1330G` · `IEM refrigerador` · `Winia DFR-25210` · `Koblenz ventilador` · `aire acondicionado mirage` · `computadora gamer`

---

## 3. Probé el arreglo determinista, y el resultado engaña

Implementé sinónimos + corrección de erratas por distancia de edición + puntuación parcial en vez de AND estricto. El número salta de **56% a 96%**.

**No te voy a vender ese 96%.** Revisé si los resultados sirven:

| Consulta | Resultado | Veredicto |
|---|---|---|
| `whirpool` | 181, todos Whirlpool | ÚTIL |
| `samsug rf28` | 4, todos Samsung | ÚTIL |
| `laptop hp` | 201, todos HP | ÚTIL |
| `televisión samsung` | 59, todos Samsung | ÚTIL |
| `pantalla lg 55 pulgadas` | 14, todos LG | ÚTIL |
| `minisplit lg inverter 12000` | 15 de 19 son LG | ÚTIL |
| `Mabe RMA1025` | 2 de 3 son Mabe | ÚTIL |
| `Acros AT1330G` | 4 resultados, **ninguno es Acros** | FALSO POSITIVO |
| `IEM refrigerador` | **4,535 resultados, ninguno es IEM** | FALSO POSITIVO |
| `Koblenz ventilador` | 2, ninguno es Koblenz | FALSO POSITIVO |
| `aire acondicionado mirage` | 441, ninguno es Mirage | FALSO POSITIVO |
| `horno de microondas` | 228 de una categoría que no existe | RUIDO |
| `bomba de agua` | 436 | RUIDO |
| `computadora gamer` | 187 | RUIDO |

**7 útiles, 7 falsos positivos o ruido.**

Y los falsos positivos son **peores que no encontrar nada**. Si alguien escribe "IEM refrigerador", ve 4,535 resultados y elige uno, se lleva el kWh/año de un Frigidaire atribuido a su IEM. Ese número entra al inventario, al balance, a la proyección y al riesgo DAC. Es exactamente el tipo de dato inventado que has prohibido toda la sesión — solo que inventado por el buscador en vez de por un LLM.

---

## 4. Lo que hay que hacer, en orden

**a) Arreglar el buscador Y enseñarle a decir que no.** Sinónimos, erratas y ranking parcial recuperan 7 casos reales, gratis y sin IA. Pero con una regla nueva que hoy no existe:

> Si el usuario escribió una marca y ninguna coincide, **no devolver resultados de otras marcas**. Decir "no tenemos esa marca todavía".

Sin esa regla, un buscador más permisivo es una máquina de atribuir consumos equivocados.

**b) Ahí es donde entra la IA, y es un trabajo distinto al que te dije ayer.** No es matching: es **cubrir las marcas que México compra y tu dataset no tiene**. Eso es conocimiento, no búsqueda. Y como la IA tendría que *aportar* la cifra en vez de encontrarla, la alucinación vuelve al centro del problema.

**c) Pero hay una fuente mejor que cualquier LLM para ese hueco.** En México los electrodomésticos llevan **etiqueta de eficiencia energética con el consumo en kWh/año impreso**, por norma. Ese dato es verificable y es del aparato del usuario, no de un modelo parecido.

El diseño honesto queda así:

| Situación | Qué hace la app | Confianza |
|---|---|---|
| Marca+modelo en catálogo | Lo toma tal cual | Alta |
| Marca conocida, modelo no | Ofrece los modelos parecidos **de esa marca** | Media |
| Marca ausente (Acros, IEM…) | "No tenemos esa marca. ¿Tu aparato trae etiqueta? Escribe los kWh/año" | Alta si responde |
| No hay etiqueta | IA sugiere rango típico por categoría y capacidad, marcado como estimación | Baja |
| Modelo inexistente | "No lo encontré" | — |

La IA nunca es la última palabra sobre el número. Es el respaldo cuando no hay nada mejor, y siempre etiquetada.

---

## 5. El comparador de modelos, listo para correr

No pude ejecutar la comparación Haiku vs Gemini: **no tengo llaves de API de ninguno**, y no quiero que me las pases por aquí porque quedarían en el historial.

Lo dejé armado en `tools/ai-eval/`:

```bash
cd tools/ai-eval
export ANTHROPIC_API_KEY=...     # o GEMINI_API_KEY
python3 run_eval.py anthropic claude-haiku-4-5
python3 run_eval.py gemini gemini-2.5-flash-lite
```

El set de 30 casos está diseñado alrededor de la pregunta que de verdad decide:

- **Grupo A (18 casos)** — modelos con kWh/año certificado. Mide exactitud contra la verdad.
- **Grupo B (8 casos)** — marcas mexicanas ausentes: Mabe, Acros, IEM, Winia, Koblenz, Mirage, Atvio, Easy. Mide si admite que no sabe.
- **Grupo C (4 casos)** — **modelos que inventé y no existen**: `Samsung RX99Z4400ZZ`, `LG QQ-88XTREME`, `Mabe ZZ0000NOEXISTE`, `Bosch KX-000000`. Cualquier cifra concreta aquí es alucinación pura.

El criterio de decisión no es quién acierta más en A. Es **quién inventa menos en B y C**. Un modelo que acierta 90% pero se inventa cifras para marcas que no conoce es inservible para tu app; uno que acierta 75% y dice "no sé" es el correcto.

Tarda un par de minutos y cuesta centavos.

---

## 6. Lo que esto cambia del plan

1. Al buscador hay que agregarle la regla de "no encontré", **antes** de hacerlo más permisivo. Es un arreglo que puedo hacer hoy y previene errores de atribución.
2. Un catálogo de marcas mexicanas vale más que cualquier modelo de IA. Aunque sean 200 modelos de Mabe, Acros, IEM y Winia con su kWh/año de etiqueta, cubren más casos reales que cualquier LLM adivinando.
3. La captura manual de kWh/año desde la etiqueta debería ser un camino de primera clase en el alta de aparatos, no un plan B escondido.
