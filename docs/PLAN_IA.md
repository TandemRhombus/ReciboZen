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

**Pero hay un desconocido real que no puedo resolver leyendo código:** si el sitio de CFE responde bien a peticiones que salen de la red de Cloudflare. Muchos sitios de gobierno y utilities en México bloquean IPs de datacenter o filtran por geografía. Desde tu Orange Pi sale una IP residencial mexicana; desde Workers no.

Por eso **no arrancaría migrando**. Mi propuesta, ordenada por riesgo:

1. **Workers limpio para lo nuevo** — auth, proxy de IA, cuotas. Cero riesgo de migración, y si Workers falla tu v1.0 ni se entera.
2. **En paralelo, una prueba de 30 minutos**: un Worker desechable que pida las páginas de tarifas de CFE. Si responde, migramos tarifas y solar y apagas la Orange Pi. Si no responde, la Orange Pi se queda con ese trabajo específico y ya sabemos por qué.

Decidir eso con datos cuesta media hora. Decidirlo sin datos cuesta un fin de semana de migración que quizá haya que revertir.

---

## 2. Blindaje de tu API key — la parte que más importa

Diseñé esto asumiendo que alguien va a intentar abusarlo, porque va a pasar.

### Capa 0 — La llave nunca toca la app

`ANTHROPIC_API_KEY` vive **solo** como secreto del Worker. La app nunca la ve, no está en el APK, no está en el repo, no está en Gradle properties. Cualquiera puede descompilar tu APK y no encuentra nada.

**Yo tampoco necesito tu llave.** Tú corres el comando; yo escribo el código que la usa por nombre.

### Capa 1 — Solo usuarios premium verificados

Ya tienes el mecanismo: el *entitlement lease* firmado con ECDSA P-256 que el backend emite y `EntitlementLeaseVerifier` valida en el cliente. El Worker valida **la misma firma** antes de hacer nada. Sin lease válido y sin vencer → `403`, sin llamar a Anthropic.

No inventamos autenticación nueva: reusamos la que ya construiste.

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

**Identificar un aparato:**

| Concepto | Tokens | Costo |
|---|---|---|
| Sistema (cacheado) | ~1,200 | $0.00012 |
| Entrada (marca + modelo) | ~30 | $0.00003 |
| Salida (JSON con ficha) | ~250 | $0.00125 |
| **Total** | | **≈ $0.0014** |

**Explicar un recibo** (los números los calcula tu motor; Haiku solo los redacta): **≈ $0.0023**.

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
4. Confirmar el plan: Workers de paga son **$5 USD/mes** e incluyen Durable Objects. El plan gratis **no** tiene Durable Objects, y sin ellos las cuotas se pueden burlar con llamadas simultáneas. Para proteger dinero, los $5 valen la pena.

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
| 2 | Prueba: ¿CFE responde desde Workers? | Nada | 30 min |
| 3 | Worker: auth con lease + cuotas + Durable Objects | Cuenta Cloudflare | Después de (1) |
| 4 | Proxy de Haiku: identificar aparato | (3) + API key | |
| 5 | Explicación en lenguaje natural del recibo | (4) | |
| 6 | Google Sign-In + sincronización opcional | Google Cloud | |
| 7 | **Google Play Billing de verdad** | Play Console | |

Sobre el 7: hoy tu backend responde `503 play_verification_not_configured`. **No puedes cobrarle a nadie todavía.** Si el objetivo es monetizar, esa es la pieza que de verdad bloquea el negocio — no la IA. La IA hace que Plus valga la pena; Billing es lo que permite venderlo.

---

## Fuentes

- [Precios de la API de Claude](https://platform.claude.com/docs/en/about-claude/pricing)
