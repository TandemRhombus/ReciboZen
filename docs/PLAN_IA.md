# ReciboZen — Plan de IA, cuentas y Cloudflare

Rama `IA-Implement` (ya incluye todo `sergio`, con el fix de la meta de ayer).

---

## 1. Tu pregunta devuelta: ¿todo cabe en Workers?

**Sí, técnicamente todo es portable.** Lo revisé pieza por pieza:

| Pieza actual | ¿Corre en Workers? | Qué cambia |
|---|---|---|
| `server.mjs` (node:http) | Sí | Router `fetch()` en vez de `createServer` |
| `entitlement-lease.mjs` (ECDSA P-256) | Sí | `node:crypto` → WebCrypto, que soporta P-256 |
| `google-play.mjs` (JWT RS256 + fetch) | Sí | WebCrypto `RSASSA-PKCS1-v1_5` |
| `solar-resource.mjs` (fetch NASA + caché) | Sí | La caché en memoria muere con cada instancia → KV |
| `tariff-catalog.mjs` (persistencia en disco) | Sí | `writeFile`/`rename` → KV o R2 |
| Refresco diario | Sí | Cron Triggers |
| 21 pruebas `node:test` | Sí | Reescribir a vitest + `@cloudflare/vitest-pool-workers` |

**Ese desconocido ya se midió, y la respuesta cambió el plan.** `app.cfe.mx` está detrás de Imperva (resuelve a `e85dinu.ng.impervadns.net`). Desde una IP de datacenter: **403**. Desde la IP residencial mexicana de la Orange Pi: **200**, con `curl` pelado y con user-agent de Chrome por igual. El discriminante es la IP, no el user-agent.

Conclusión: **el scraper de tarifas se queda en la Orange Pi.** El Worker es solo para la IA. Dos hallazgos que vinieron de yapa: el sitio no es una SPA sino ASP.NET WebForms con postback, así que no hace falta Browser Rendering (que es de paga); y `robots.txt` permite la ruta de tarifas.

Así que el Worker se levanta **limpio, solo para lo nuevo**: auth, proxy de IA, cuotas. Cero riesgo de migración — si el Worker falla, tu v1.0 ni se entera, porque el backend de la Orange Pi sigue sirviendo tarifas y solar exactamente como hoy.

Media hora de medición evitó un fin de semana de migración que habría que revertir. La tabla de arriba queda como referencia de qué **sería** portable, no de qué conviene portar.

---

## 2. Blindaje de tu API key — la parte que más importa

Diseñé esto asumiendo que alguien va a intentar abusarlo, porque va a pasar.

### Capa 0 — La llave nunca toca la app

`ANTHROPIC_API_KEY` vive **solo** como secreto del Worker. La app nunca la ve, no está en el APK, no está en el repo, no está en Gradle properties. Cualquiera puede descompilar tu APK y no encuentra nada.

**Yo tampoco necesito tu llave.** Tú corres el comando; yo escribo el código que la usa por nombre.

### Capa 1 — Solo usuarios premium verificados

Ya tienes el mecanismo: el *entitlement lease* firmado con ECDSA P-256 que el backend emite y `EntitlementLeaseVerifier` valida en el cliente. El Worker valida **la misma firma** antes de hacer nada. Sin lease válido y sin vencer → `403`, sin llamar a Anthropic.

No inventamos autenticación nueva: reusamos la que ya construiste. **Pero no es copiar y pegar.** El backend firma con `sign("sha256", ...)` de Node, que emite ECDSA en **DER**; Java lo lee sin problema, por eso el cliente Android nunca lo notó. WebCrypto —la única API de crypto que hay en Workers— solo acepta **r||s crudo** y responde `false` **en silencio** ante DER. El Worker convierte DER a crudo antes de verificar; el emisor no se toca, porque cambiarlo dejaría afuera a todos los clientes ya instalados. Implementado y probado en `worker/src/lease.mjs`.

### Capa 2 — Identidad estable por usuario

El lease ya trae `subject` (de `PremiumAccessStore.subject`), atado a la instalación y **firmado por tu servidor**. El cliente no puede fabricarse uno. Ese es el ID de cuota que pediste.

### Capa 3 — Cuota mensual por usuario, contada en el servidor

En un **Durable Object** por `subject` (no KV: KV es consistente-eventual y dos llamadas simultáneas pueden saltarse el límite; con dinero de por medio quiero incrementos atómicos):

```
subject → { mes: "2026-08", identificaciones: 37, explicaciones: 12, tokensIn, tokensOut }
```

Al pasarse: `429` con mensaje claro y cuándo se reinicia. Nunca una llamada más.

### Capa 4 — Techo global de gasto (esto es lo que te salva de una factura absurda)

Las cuotas por usuario no te protegen si aparecen 5,000 cuentas falsas. Un contador global mensual con un tope que **tú defines en dólares**: al llegar, el Worker deja de llamar a Anthropic para todos y responde con un mensaje digno. Prefiero que la IA se apague un día a que te llegue una factura de $800.

### Capa 5 — El cliente nunca manda un prompt

Esto es crítico y se pasa por alto: el Worker **no** acepta texto libre. Acepta intenciones tipadas:

```
POST /v1/ai/identify-appliance   { brand, model, category }
POST /v1/ai/explain-bill         { deltaBreakdown }   // números ya calculados por TariffEngine
```

El prompt, el modelo, `max_tokens` y el esquema de salida se arman **dentro** del Worker. Si aceptáramos prompts libres habríamos construido un ChatGPT gratis para internet, pagado por ti.

### Capa 6 — Límite de ráfaga

Rate limiting de Cloudflare en la ruta (por IP) + chequeo por `subject` dentro del Worker (por minuto). La cuota mensual no sirve si alguien te quema el mes en 4 minutos.

### Capa 7 — Play Integrity

Ya tienes la dependencia. Ata la emisión del lease a una instalación verificada de Play. Sin esto, clonar la app y sacar leases es trivial.

### Capa 8 — Observabilidad

Cada llamada guarda tokens de entrada y salida en el Durable Object. Puedes ver el gasto **antes** de que llegue la factura, no después.

---

## 3. Los números: Haiku 5.5

Desde el 2026-10-26 el Worker usa **Claude Haiku 5.5** (`claude-haiku-5-5`). Precio oficial, para prompts de hasta 100,000 tokens: **$0.10 por millón de entrada, $0.50 de salida**; lectura de caché $0.01, escritura $0.125. Arriba de 100,000 tokens la petición entera se cobra cinco veces más ($0.50 / $2.50). Ninguna intención se acerca a ese límite, pero `worker/src/pricing.mjs` lo modela igual, para que el techo global no dependa de que así siga.

Tres cosas del cambio que tocan el costo:

- **Haiku 5.5 piensa por defecto, y el pensamiento se cobra como salida.** Las dos intenciones lo apagan (`thinking: {type: "disabled"}`, con `effort: "medium"` fijo). Así cada token de salida es la ficha o el plan, como en el modelo de costos.
- **El tokenizador es nuevo: el mismo texto cuenta ~30% más tokens.** Los conteos medidos en Haiku 4.5 no se pueden reutilizar tal cual.
- **Si el modelo se niega** (`stop_reason: "refusal"`), el Worker responde 422 `model_refused`, la app lo muestra como "no se pudo estimar" y los tokens se cuentan igual. Haiku 5.5 no tiene respaldo automático del lado del servidor.

**Costo por operación:**

| Operación | Entrada | Salida | Costo | Origen |
|---|---|---|---|---|
| Identificar un aparato | ~1,920 | ~310 | **≈ $0.00035** | Estimado: lo medido en 4.5 el 2026-08-24 (1,480 / 240) × 1.3 por el tokenizador |
| Explicar un recibo (`explain-bill`, aún no existe en el Worker) | — | — | **≈ $0.0003** | Estimado: los $0.0023 de 4.5 × 0.1 por precio × 1.3 por tokenizador |
| Plan de ahorro (`suggest-savings`) | ~2,750 | ≤ 2,048 | **≤ $0.0013** | Cota superior: entrada escalada por tamaño del prompt, salida al tope de `max_tokens`. Nunca se ha medido |

> **El modelo financiero del 2026-10-26 se queda corto en ~30%.** Supone **$0.00027** por identificación y **$0.00023** por explicación. Esas cifras son los tokens de Haiku 4.5 a precios de Haiku 5.5: les falta el tokenizador nuevo. Con él quedan en **≈ $0.00035** y **≈ $0.0003**. En pesos casi no cambia nada. Un suscriptor con 20 identificaciones y 10 explicaciones cuesta **≈ $0.0099 USD ≈ $0.19 MXN** al mes (a 19 MXN/USD), contra los $0.14 MXN del modelo.
>
> **Ninguna de estas cifras está medida en Haiku 5.5.** Para medirlas, una llamada real por intención, armada igual que en producción:
>
> ```
> cd worker && ANTHROPIC_API_KEY=sk-ant-... node scripts/measure-cost.mjs
> ```
>
> Imprime tokens de entrada y salida, costo en USD y los tipos de bloque de la respuesta. Si aparece un bloque `thinking`, el pensamiento se coló y el costo está mal. Las dos llamadas juntas cuestan menos de un décimo de centavo de dólar. Cuando corra, reemplaza las estimaciones de esta tabla.

**Al tope de la cuota** (las cuotas de `worker/src/quota-policy.mjs`; plan de ahorro a su cota superior):

| Plan | Identificaciones | Planes de ahorro | Explicaciones | Costo/mes | % del precio de lista |
|---|---|---|---|---|---|
| Gratis | 0 | 0 | 0 | $0 | — |
| Plus ($99 MXN) | 150 | 30 | 80 | ≈ $0.12 USD ≈ $2.19 MXN | ≈ 2.2% |
| Familiar ($199 MXN) | 400 | 80 | 200 | ≈ $0.30 USD ≈ $5.75 MXN | ≈ 2.9% |

El porcentaje es sobre el precio de lista, antes de la comisión de Google. Aun así, la IA es ruido incluso para quien agota la cuota.

**El techo global** sigue en $20 USD al mes (`MONTHLY_CEILING_USD`). Ahora alcanza para unas **57,000 identificaciones**, contra 7,500 con Haiku 4.5.

> **Historia — por qué no hay caché.** En Haiku 4.5 el caché exigía un prefijo de 4,096 tokens y el real medía 1,477, así que nada se cacheó nunca. La primera estimación de este documento ($0.0014) contaba con ese caché y era la mitad del costo real. Haiku 5.5 cachea desde un prefijo mucho más corto (512 tokens al lanzamiento), así que el prompt ya calificaría. Sigue sin cachearse a propósito: escribir el caché cuesta **125%** y dura cinco minutos, y con hogares que dan de alta un aparato de vez en cuando se pagaría la escritura casi siempre y se leería casi nunca. Vale la pena encenderlo solo cuando el tráfico muestre llamadas dentro de la misma ventana de cinco minutos.

---

## 4. Lo que necesito de ti

### Cloudflare

1. **Account ID** (aparece en el panel, sección Workers).
2. Que **tú** instales wrangler y te autentiques: `npm i -g wrangler && wrangler login`.
3. Un subdominio para el Worker. Ya tienes `simapp.uk`; propongo `ia-recibozen.simapp.uk`.
4. Confirmar el plan: **el plan gratuito alcanza.** Los Durable Objects con backend SQLite están incluidos en el plan gratuito, con 100,000 peticiones al día — y dan los incrementos atómicos que las cuotas necesitan. La versión anterior de este documento decía que hacían falta $5 al mes; era falso. Se construye y se prueba sin pagar.

**No me des un API token de Cloudflare.** Yo escribo el código y los comandos; tú los corres. No hay razón para que yo tenga acceso de escritura a tu cuenta.

### Anthropic

1. Crea la API key en la consola de Anthropic.
2. Ponla **solo** aquí, tú, en tu máquina:
   ```
   wrangler secret put ANTHROPIC_API_KEY
   ```
   Te la pide por stdin, no queda en el historial ni en ningún archivo.
3. En la consola de Anthropic, ponle un **límite de gasto mensual** a la organización. Es tu último airbag si todo lo demás falla.
4. Dime tu techo mensual en USD para calibrar las cuotas.

**No me pases la llave por aquí.** Si me la mandas, quedaría en el historial de esta conversación y habría que rotarla.

### Google (para el login, cuando lleguemos)

1. Proyecto en Google Cloud Console.
2. OAuth 2.0 Client IDs: uno **Web** (lo usa el backend para validar el token) y uno **Android**.
3. SHA-1 de tus keystores de debug y release.
4. Para Play Billing, que sigue pendiente: cuenta de servicio con acceso a la Play Developer API.

---

## 5. Lo que empiezo hoy, sin depender de nada de lo anterior

Tres funciones que en tu lista aparecían como IA y **no lo son**:

**a) "¿Por qué subió mi recibo?"** — descomposición aritmética de la diferencia entre dos recibos:
- cuánto por más kWh consumidos,
- cuánto por kWh que cruzaron al escalón excedente,
- cuánto por cambio de tarifa o temporada,
- cuánto por días distintos del periodo.

Todo sale de `TariffEngine` con los recibos que ya tienes. Es la función que más gente te va a agradecer y no cuesta un solo token.

**b) Presupuesto inverso** — "no quiero pagar más de $900": kWh disponibles, ritmo diario necesario, y un plan concreto con acciones del inventario ordenadas por impacto. Piezas: `ConsumptionPaceCalculator` + `TariffEngine` + `ApplianceAdviceEngine`. Ya existen las tres.

**c) Anomalías en el consumo base** — comparar el `OvernightBaseline` de ayer contra su propio histórico. "Tu consumo nocturno subió 38% desde el martes" sale de restar dos números que ya calculamos.

**d) El esquema de evidencia** — `DeviceModel` / `DeviceEvidence` con `value + unit + source + retrievedAt + confidence + methodology`, y regla explícita cuando dos fuentes se contradicen. Es donde la IA va a escribir después. Construirlo primero significa que la IA llega a una casa con orden, no al revés.

Con eso Plus ya tiene contenido vendible antes de gastar el primer dólar en tokens.

---

## 6. Una cosa que no voy a validar

El análisis que pegaste trae comparativas contra CFE Contigo y Wibeee, y conclusiones tipo "una de las propuestas más completas del mercado". No las estoy dando por buenas: no las verifiqué y una auditoría competitiva de verdad es otro trabajo.

Lo que sí sostengo, porque lo leí en tu código: el motor tarifario, la calibración desde recibo, la ventana móvil de DAC por tarifa y el inventario con procedencia están mejor construidos de lo que esperaría en un proyecto de este tamaño. Eso es real. Si es "lo mejor del mercado" no lo sé, y tampoco cambia qué toca construir primero.

---

## 7. El orden que propongo

| # | Qué | Bloquea | Semanas |
|---|---|---|---|
| 1 | Las tres funciones deterministas + esquema de evidencia | Nada | Empieza hoy |
| 2 | ~~Prueba: ¿CFE responde desde Workers?~~ | — | **Hecha** — el scraper se queda en la Orange Pi |
| 3 | Worker: auth con lease ✅ · cuotas + Durable Objects | Cuenta Cloudflare | Auth verificada contra un lease real |
| 4 | Proxy de Haiku: identificar aparato | (3) + API key | |
| 5 | Explicación en lenguaje natural del recibo | (4) | |
| 6 | Google Sign-In + sincronización opcional | Google Cloud | |
| 7 | **Google Play Billing de verdad** | Play Console | |

Sobre el 7: **el bloqueo se levantó.** `/readyz` reporta `playBillingConfigured: true` y `POST /v1/entitlements/exchange` con cuerpo vacío responde `400 invalid_product_id` — valida, en vez de negarse a existir. Lo que queda es Play Console: planes base activos y la prueba cerrada. Si el objetivo es monetizar, esa es la pieza que de verdad bloquea el negocio — no la IA. La IA hace que Plus valga la pena; Billing es lo que permite venderlo.

---

## Fuentes

- [Precios de la API de Claude](https://platform.claude.com/docs/en/about-claude/pricing)
