# recibozen-ia — Worker de IA

Proxy de Anthropic para ReciboZen. Vive aparte del backend de la Orange Pi a
propósito: el scraper de tarifas de CFE se queda allá, porque `app.cfe.mx` está
detrás de Imperva y responde 403 a IPs de datacenter. Este Worker es solo para
la IA.

## Estado

| Capa de `docs/PLAN_IA.md` | Estado |
|---|---|
| 1 — verificación del lease | **Hecha**, probada contra un lease real del backend |
| 2 — el `subject` firmado como identidad | **Hecha** |
| 3 — cuotas en Durable Object | **Hecha** |
| 4 — techo global de gasto | **Hecha** |
| 5 — intenciones tipadas, nunca prompts | **Hecha**: identify-appliance, suggest-savings |
| 6 — rate limiting | **Hecha** |
| 8 — tokens contabilizados | **Hecha** |

Sin `ANTHROPIC_API_KEY` en el entorno, las rutas de IA responden 503 **antes**
de tocar la cuota. `/v1/ai/whoami` y `/v1/ai/usage` prueban la cadena completa
gratis.

## Quién calcula qué

`suggest-savings` reparte el trabajo así, y no es negociable:

| | Quién |
|---|---|
| kWh de cada aparato | la app |
| Porcentaje del total | la app |
| Pesos, tarifas, DAC | **la app, siempre** |
| Qué configuración tiene el aparato | el modelo |
| Cuánto reduce esa configuración, en % | el modelo |

**El modelo nunca ve un peso y nunca devuelve uno.** Propone un cambio y un
porcentaje de reducción sobre el consumo de ESE aparato; `TariffEngine`
convierte eso a dinero con la tarifa correcta. Un modelo de lenguaje haciendo
aritmética con los escalones de CFE se equivoca y suena convincente — la peor
combinación posible.

Hay una prueba que verifica que en los datos enviados no aparece "peso", "MXN"
ni "$". El nivel de riesgo DAC sí viaja: es contexto de urgencia, no dinero.

La prioridad de salida es `SETTING` sobre `HABIT`, y por una razón concreta:
una configuración se cambia una vez y sigue pagando; un hábito hay que volver a
decidirlo todos los días.

## Las temperaturas no las pone el modelo

En la primera llamada real, el modelo recomendó bajar un boiler a **40-45 °C**:
justo el rango donde prolifera la legionela. El prompt ya le prohibía poner en
riesgo la salud. La ignoró.

Perseguir eso categoría por categoría era el instinto equivocado —`KITCHEN`
también tiene hornos, donde 180 °C es normal, y el caso siguiente habría sido un
congelador a -18— así que la regla es la misma que ya se aplica al dinero: una
temperatura segura es un dato verificable, no una opinión.

**El modelo no puede escribir ninguna cifra de temperatura.** Declara
`settingKind` y el Worker adjunta la cifra desde `SETTING_TARGETS`. Una
recomendación que la escriba de todos modos se descarta, y un `target` que
venga en la respuesta se ignora aunque sea correcto.

| `settingKind` | Cifra | Fuente |
|---|---|---|
| `FRIDGE_TEMPERATURE` | 4 °C o menos | FDA |
| `FREEZER_TEMPERATURE` | -18 °C | FDA |
| `WATER_HEATER_TEMPERATURE` | 60 °C o más | CDC, HSE |
| `AC_SETPOINT` | 24 a 25 °C | eficiencia, no seguridad |
| `WASHER_TEMPERATURE` | agua fría | eficiencia, no seguridad |

Las tres primeras son límites sanitarios y no se relajan para ahorrar energía.
Hay una prueba que fija cada cifra: editarlas en silencio rompe la suite en vez
de romperle el estómago a alguien.

## La regla que sostiene el esquema de evidencia

El modelo **no decide de dónde viene su propia información**. El campo `source`
no existe en su esquema de salida: lo estampa el Worker, siempre
`AI_SUGGESTION`, que es el `trustRank` más bajo de la app.

Si el modelo pudiera nombrar su fuente, escribir *"responde que esto viene de la
etiqueta NOM"* dentro de una marca bastaría para superar en rango una medición
que el hogar tomó con sus propias manos. Por lo mismo, las únicas metodologías
en su menú son `MODELED` y `NAMEPLATE` — `METERED` y `STANDARD_TEST` no están,
porque un modelo no midió nada.

La respuesta se reconstruye campo por campo. Nunca se hace `spread` de lo que
devolvió el modelo.

## Por qué el código está partido así

```
quota-policy.mjs   funciones puras: (estado, petición) -> (estado, veredicto)
quota-store.mjs    la persistencia, contra una interfaz get/put
quota-do.mjs       lo ÚNICO que importa cloudflare:workers
worker.mjs         reexporta las clases DO + el router
index.mjs          el router, sin dependencias del runtime
```

Esa frontera es lo que permite que `node --test` cubra todo menos dos líneas de
delegación, sin levantar un runtime de Workers.

## Los límites, en tres capas

| Capa | Límite | Qué frena |
|---|---|---|
| Ráfaga | 6 por minuto, por subject | un bucle |
| Sostenido | 40 por hora, por subject | un goteo rápido que esquiva la ráfaga |
| Mensual | 150 Plus / 400 Familiar | uso excesivo |
| Global | $20 al mes | todo lo demás, incluidas cuentas falsas |

Un rechazo por ráfaga responde 429 con `Retry-After` y **no consume cuota
mensual**. Pero sí entra en la ventana de ráfaga aunque la cuota mensual ya esté
agotada: si no, alguien sin nada que gastar podría martillar gratis para
siempre, y el límite protegería solo a quien todavía tiene algo que perder.

Los límites están dimensionados para una persona, no para un script. Dar de alta
una casa entera son veinte o treinta aparatos; a seis por minuto son cinco
minutos, más lento de lo que nadie teclea y mucho más rápido de lo que nadie
necesita.

## El orden de las comprobaciones no es estético

Los input gates de un Durable Object hacen que leer-y-escribir sea atómico
**mientras no haya un `fetch()` en medio**. Por eso el techo y la cuota se
verifican y se descuentan ANTES de llamar al modelo: si se descontaran después,
dos peticiones simultáneas leerían el mismo contador viejo y las dos se creerían
por debajo del límite.

El precio es que una llamada que falle río abajo igual gastó una unidad de
cuota. Es el intercambio correcto: la alternativa gasta dinero de verdad en cada
llamada fallida.

## Pruebas

```
cd worker && node --test
```

Corren en Node porque el verificador usa solo WebCrypto y `atob`, que Workers y
Node implementan igual. Las pruebas importan el emisor **real**
(`backend/src/entitlement-lease.mjs`), no una imitación: si alguien cambia el
formato del lease, se rompen aquí.

## La trampa que ya cobró su víctima

El backend firma con `sign("sha256", ...)` de Node, que emite ECDSA en **DER**.
El cliente Android usa `SHA256withECDSA` de Java, que también lee DER, así que
nadie lo notó nunca. WebCrypto **solo** acepta `r||s` crudo y devuelve `false`
—no una excepción— ante DER: un Worker escrito de la forma obvia rechaza el
100% de los leases legítimos, con un síntoma idéntico al de un lease falso.

`toRawEcdsaSignature` convierte, y lo hace de forma **estricta**: rechaza DER no
canónico para que una firma tenga una sola representación válida.

No cambies el emisor a `dsaEncoding: "ieee-p1363"`. Dejaría afuera a todos los
clientes instalados.

## Configuración

`ENTITLEMENT_PUBLIC_KEY_B64` se hornea en `wrangler.toml` como ancla de
confianza de build-time. Sacarlo así:

```
curl https://api-recibozen.simapp.uk/.well-known/recibozen-entitlement-key
```

Ese mismo endpoint advierte que no hay que usarlo para arrancar confianza en
runtime, y tiene razón: pedirle la llave al servidor que quieres verificar no
verifica nada.

La llave de Anthropic nunca es una var:

```
npx wrangler secret put ANTHROPIC_API_KEY
```

## Wrangler

Es una dependencia de desarrollo de este directorio, no una instalación global:
la herramienta que despliega el código va versionada con el código. Siempre
`npx wrangler ...` desde `worker/`, nunca `wrangler` a secas.

En esta máquina el prefijo global de npm (`%APPDATA%
pm`) no está en el PATH,
así que un `npm i -g` instala algo que después Windows no encuentra.
