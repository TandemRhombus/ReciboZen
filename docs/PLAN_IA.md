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

## 3. Los números: Haiku 4.5 sí aguanta tu modelo de negocio

Precio oficial hoy: **$1 por millón de tokens de entrada, $5 de salida**. Lectura de caché: **$0.10/MTok** — importante, porque el prompt de sistema es idéntico en todas las llamadas y se cachea.

**Identificar un aparato — medido en producción el 2026-08-24**, no estimado:

| Concepto | Tokens | Costo |
|---|---|---|
| Entrada (prompt de sistema + esquema de la herramienta + datos) | ~1,480 | $0.00148 |
| Salida (JSON con la ficha) | ~240 | $0.00119 |
| **Total** | | **$0.0027** |

> **Corrección importante:** la estimación original de este documento era **$0.0014, la mitad del costo real**, porque daba por hecho que el prompt de sistema se cachearía a $0.10/MTok. **No se cachea.** Claude Haiku 4.5 exige un prefijo de **4,096 tokens** para activar el caché — cuatro veces el umbral de Sonnet — y el prefijo real mide 1,477. Nada se cacheó nunca, y nada lo avisa: `cache_creation_input_tokens` simplemente vuelve en cero.
>
> Inflar el prompt hasta pasar los 4,096 sería peor, no mejor: escribir el caché se cobra al **125%** y la entrada dura cinco minutos. Con un hogar que da de alta un aparato de vez en cuando, se pagaría la escritura casi siempre y se leería casi nunca. Por eso `cache_control` se retiró del código.

Con el costo real, sobre un ingreso neto de $2.59 USD por suscriptor Plus (a 19 MXN/USD):

| Perfil | Identificaciones/mes | Costo | % del neto |
|---|---|---|---|
| Usuario real | 20 | $0.053 | **2.1%** |
| Usuario activo | 60 | $0.160 | 6.2% |
| Al tope de la cuota | 150 | $0.401 | 15.5% |

El costo se duplicó y el negocio no se movió: la IA sigue siendo el 2% de lo que deja un suscriptor normal. Con el techo de $20 al mes alcanza para unas 7,500 identificaciones, o unos 373 usuarios reales a pleno uso.

**Explicar un recibo** (los números los calcula tu motor; Haiku solo los redacta): **≈ $0.0023**.

> **Ojo con esta sección:** las cuotas de abajo están calculadas con Plus a **$49 MXN**, y el precio decidido es **$69 MXN**. Los costos por operación siguen vigentes (los precios de Haiku no se movieron); lo que hay que recalcular es el porcentaje del ingreso neto, y para eso falta el tipo de cambio.

Con Plus a $49 MXN te quedan ~$2.10 USD después de la comisión de Google. Un usuario que hace **150 identificaciones + 80 explicaciones al mes** te cuesta **≈ $0.40**, o sea **19% del ingreso neto**.

Cuotas que propongo:

| Plan | Identificaciones/mes | Explicaciones/mes | Costo aprox. | % del neto |
|---|---|---|---|---|
| Gratis | 0 | 0 | $0 | — |
| Plus | 150 | 80 | ~$0.40 | ~19% |
| Familiar | 400 | 200 | ~$1.02 | ~27% |

Son generosas a propósito: un usuario normal no llega ni a 20 identificaciones, y el que llegue a 150 es tu mejor cliente, no tu problema.

**Lo que necesito que decidas:** tu techo global en dólares al mes. Con $30 USD compras ~21,000 identificaciones — suficiente para varios cientos de usuarios activos.

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
