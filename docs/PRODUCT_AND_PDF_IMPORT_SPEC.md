# ReciboZen - Producto premium, multi-servicio e importación de recibos PDF

Estado: decisión de producto y arquitectura previa a implementación  
Fecha: 9 de agosto de 2026

## 1. Decisiones de producto

### La unidad que se cobra no es el medidor físico

El dominio debe separar:

```text
Usuario
└── Hogar o propiedad
    └── Servicio eléctrico (número de servicio / RMU)
        ├── Medidor físico actual
        ├── Medidores físicos anteriores
        ├── Recibos
        └── Lecturas
```

El número de medidor puede cambiar por sustitución sin que cambie el contrato o el historial del servicio. El límite comercial se contabiliza por **servicio eléctrico activo**, aunque en la interfaz se puede explicar como "servicios o medidores".

Cada servicio tiene dos clases de identificadores:

- **Identidad técnica:** número de servicio (12 dígitos en las muestras), RMU y número del medidor físico. Se extraen del PDF cuando hay confianza suficiente o se piden al usuario.
- **Identidad humana:** un alias editable como `Casa Mérida`, `Local Centro` o `Casa de mamá`. Sirve para navegar, nunca para deduplicar ni relacionar recibos.

La interfaz debe decir "número de servicio" —que es la etiqueta observada en los recibos— y puede aclarar que algunas personas lo conocen como contrato. El alta manual permite omitirlo inicialmente, pero la importación debe proponer completarlo.

### Tarifas y versiones

El catálogo inicial admite `1`, `1A`, `1B`, `1C`, `1D`, `1E`, `1F`, `DAC` y `PDBT`. El código estable vive en `ElectricService`; los precios no. Cada recibo crea un `TariffRateSnapshot` fechado con bloques, cargos fijos, impuestos, temporada y procedencia.

- `01` leído en un recibo se normaliza a `1`.
- `1A`–`1F` conservan la separación verano/fuera de verano cuando el documento la trae.
- `DAC` depende del promedio móvil de 12 meses y del límite de la tarifa/localidad de origen.
- `PDBT` exige calibración por recibo/región/demanda y nunca usa el estimador progresivo doméstico.
- Si todavía no hay snapshot, la UI debe llamar al resultado "estimación" y pedir un recibo para calibrarlo; no debe presentar cuotas locales como oficiales vigentes.

### Aparatos y captura personalizada

El producto ofrece primero un catálogo amplio por categorías: clima, cocina, lavado, entretenimiento, cómputo, iluminación, agua y cuidado personal. Cada entrada guarda potencia típica, patrón de uso, reposo y, para aparatos cíclicos como refrigeradores, kWh típicos por 30 días.

Flujo recomendado:

1. Buscar o filtrar el catálogo.
2. Elegir habitación y ajustar horas cuando corresponda.
3. Calcular kWh del periodo y costo estimado con el snapshot tarifario activo.
4. Solo si no existe un equivalente, mostrar `Aparato personalizado`.

El personalizado no empieza pidiendo kWh arbitrarios. Pide nombre, habitación, watts de la etiqueta, horas al día y días por semana, y calcula:

```text
kWh = watts / 1000 * horas_día * días_semana / 7 * días_del_periodo
```

Debe guardar origen (`CATALOG`, `USER_ESTIMATE`, `RECEIPT_INFERENCE`), fórmula y confianza. Una futura captura directa de kWh queda como opción avanzada para equipos cuya ficha ya declare consumo por ciclo o por mes.

### Propuesta de planes

| Plan | Precio de referencia | Servicios activos | Importación PDF | Valor principal |
|---|---:|---:|---|---|
| Gratis | $0 | 1 | Primer recibo y su historial incluido | Entender el recibo, registrar lecturas y obtener una proyección básica |
| Diagnóstico Zen | $49 por servicio | 1 análisis | Un recibo analizado | Anomalías, explicación detallada, plan de ahorro y PDF |
| ReciboZen Plus | $59/mes o $399/año | Hasta 2 | Ilimitada | Historial completo, alertas, metas, DAC y comparación de servicios |
| Familiar | $699/año | Hasta 5 | Ilimitada | Varias casas, locales o medidores y panel consolidado |

Regla de paywall propuesta:

- El primer servicio y la primera importación deben demostrar el valor completo del extractor.
- Al intentar crear un segundo servicio se presenta Plus.
- Al intentar crear el tercero se presenta Familiar o un upgrade equivalente.
- Cambiar un medidor físico dentro del mismo número de servicio no consume otro cupo.
- Un servicio archivado conserva su historial, pero no cuenta como activo.

Los límites y precios deben vivir en `EntitlementPolicy`, no dispersos en Composables ni condicionados directamente por el SKU de Google Play.

## 2. Lo que muestran los recibos de referencia

Se revisaron cuatro recibos CFE de dos páginas con texto digital extraíble. Comparten estructura general, pero cubren variantes que obligan a usar un parser flexible:

| Muestra | Tarifa | Hilos | Variante de tabla |
|---|---|---:|---|
| A | 01 | 1 | Consumo bajo, únicamente bloque básico |
| B | 01 | 1 | Básico, intermedio y excedente |
| C | 1B | 2 | Divide días fuera de verano y días de verano, con precios distintos |
| D | PDBT | 3 | Pequeña demanda en baja tensión, cargos comerciales y cargo fijo |

Hallazgos:

- Página 1 contiene identidad del servicio, RMU, cuenta, tarifa, número de medidor, multiplicador, hilos, periodo facturado, lecturas actual/anterior, kWh del periodo, escalones/cargos y total a pagar.
- Página 2 contiene **11 periodos históricos** con periodo, kWh, importe, pagos y pendientes de pago.
- El PDF es una fuente mejor que una conversión pesos/kWh estimada: cada fila histórica ya incluye ambos valores.
- Los recibos 1B pueden dividir un solo periodo en temporada y fuera de temporada.
- PDBT no debe procesarse como tarifa doméstica; su desglose y sus reglas son diferentes.
- Nombre, dirección, cuenta, número de servicio, RMU, medidor, QR y códigos de barras son datos sensibles.

Validación fuerte observada en las cuatro muestras:

```text
(lectura_actual - lectura_anterior) * multiplicador == kWh_del_periodo
```

El importador debe rechazar confirmación automática o bajar la confianza cuando esta igualdad no se cumpla.

## 3. Modelo de datos propuesto

### Household

- `id`
- `displayName`
- `addressEncrypted`
- `city`
- `stateCode`
- `createdAt`
- `archivedAt`

### ElectricService

- `id`
- `householdId`
- `displayAlias`
- `serviceNumber` (normalizado, único)
- `rmu`
- `accountNumberEncrypted`
- `tariffCode`
- `billingCycle`
- `wireCount`
- `isActive`
- `createdAt`

### Meter

- `id`
- `electricServiceId`
- `meterNumber`
- `multiplier`
- `validFrom`
- `validUntil`
- `isCurrent`

### Bill

- `id`
- `electricServiceId`
- `periodStart`
- `periodEnd`
- `dueDate`
- `cutoffDate`
- `currentReadingKwh`
- `previousReadingKwh`
- `consumptionKwh`
- `amountMxnMinor`
- `tariffCode`
- `tariffSnapshotJson`
- `source` (`PDF_IMPORTED`, `MANUAL`, `LEGACY_HISTORY`)
- `sourceFileHash`
- `parseConfidence`
- `importedAt`

Restricción única: `(electricServiceId, periodStart, periodEnd)`.

### BillCharge

- `billId`
- `type` (`BASIC`, `INTERMEDIATE`, `EXCESS`, `FIXED`, `ENERGY`, `TAX`, `SUBSIDY`, `PREVIOUS_BALANCE`, `PAYMENT`, `OTHER`)
- `season` (`SUMMER`, `OUT_OF_SUMMER`, `NONE`)
- `quantityKwh`
- `unitRateMxn`
- `amountMxnMinor`
- `labelFromDocument`

### MeterReading

- `id`
- `electricServiceId`
- `meterId`
- `readingKwh`
- `capturedAt`
- `method` (`DIAL`, `MANUAL`, `PHOTO`, `BILL_PDF`)
- `billId` opcional
- `confidence`

### BillHistorySummary

Representa filas históricas encontradas dentro de un recibo cuando todavía no se ha importado el PDF detallado de ese periodo:

- `electricServiceId`
- `periodStart`
- `periodEnd`
- `consumptionKwh`
- `amountMxnMinor`
- `paidMxnMinor`
- `pendingMxnMinor`
- `sourceBillId`

## 4. Flujo de importación PDF

1. Usuario elige un PDF con el selector de documentos de Android (`application/pdf`).
2. Se valida firma `%PDF`, tamaño máximo, número razonable de páginas y que no esté cifrado.
3. Se calcula SHA-256 antes de procesar para detectar archivos repetidos.
4. Se intenta extracción de texto nativo.
5. Si el texto es insuficiente, se renderizan las páginas y se usa OCR como fallback.
6. `CfeBillParser` detecta variante de layout y tarifa.
7. Se extraen campos con valor, origen y confianza; nunca solamente un mapa de strings sin trazabilidad.
8. Se ejecutan validaciones aritméticas y de fechas.
9. Se muestra una pantalla de revisión:
   - servicio detectado;
   - medidor detectado;
   - periodo y total;
   - kWh actuales;
   - cantidad de periodos históricos encontrados;
   - advertencias de baja confianza.
10. El usuario asocia el recibo a un servicio existente o crea uno nuevo.
11. Se aplica el entitlement antes de crear un servicio adicional, no antes de analizar el archivo.
12. Se guarda todo dentro de una transacción.
13. El original se elimina después de extraer por defecto; conservarlo es una opción explícita.

## 5. Estrategia del parser

Interfaz sugerida:

```kotlin
interface BillDocumentParser {
    fun supports(document: ExtractedPdfDocument): SupportScore
    fun parse(document: ExtractedPdfDocument): BillParseResult
}
```

Adaptadores iniciales:

- `CfeDomestic01Parser`
- `CfeDomesticSeasonalParser` para 1A-1F, comenzando por 1B
- `CfePdbtParser`
- `CfeGenericFallbackParser`

El parser debe normalizar espacios y etiquetas pegadas como `TARIFA:01NO. MEDIDOR`, tolerar separadores de miles, fechas abreviadas en español y tablas partidas por temporada.

Cada campo extraído debe conservar:

```kotlin
data class ParsedField<T>(
    val value: T?,
    val confidence: Float,
    val page: Int,
    val evidence: String,
)
```

La evidencia sirve para depurar nuevos formatos sin guardar el PDF completo.

## 6. Mezcla del historial sin duplicados

- La factura detallada importada tiene prioridad sobre una fila de resumen histórico del mismo periodo.
- Una fila histórica nunca debe sobrescribir cargos detallados de otro PDF.
- Reimportar el mismo hash no crea datos nuevos.
- Importar otro PDF del mismo servicio actualiza/eleva la calidad de periodos coincidentes.
- Si cambia el número de medidor pero el servicio/RMU coincide, se crea un nuevo `Meter` dentro del mismo servicio.
- Si número de servicio y RMU apuntan a servicios diferentes, se detiene la importación y se pide confirmación.
- Los periodos deben ordenarse por fechas, no por etiquetas como "Feb" o "Ago".

## 7. Privacidad y seguridad

- Procesar localmente por defecto.
- No enviar contenido del recibo a analítica, crash reports ni logs.
- Cifrar cuenta, dirección y cualquier original conservado.
- Enmascarar número de servicio, cuenta y medidor en UI compartible.
- Permitir borrar el PDF sin borrar los datos derivados.
- Permitir exportar/borrar todos los datos de un servicio.
- Pedir consentimiento independiente antes de una futura sincronización en nube.

## 8. Cambio de arquitectura necesario

DataStore debe seguir guardando preferencias pequeñas: tema, onboarding y entitlement cacheado. Multi-servicio, recibos, cargos, lecturas e historial deben migrar a **Room**, porque requieren:

- relaciones y restricciones únicas;
- transacciones de importación;
- consultas por servicio y periodo;
- resolución de duplicados;
- migraciones de esquema;
- cientos de filas de historial sin serializar toda la sesión en un JSON.

La migración debe preceder a la UI de "Agregar otro medidor" para evitar construir el paywall sobre el modelo de sesión única actual.

## 9. Orden de implementación

### Fase 1 - Datos

- Room y entidades `Household`, `ElectricService`, `Meter`, `Bill`, `BillCharge`, `MeterReading` y `BillHistorySummary`.
- Migración de la sesión local actual al primer hogar/servicio.
- Repositorios por servicio y servicio activo seleccionable.
- Catálogos estables de tarifa y aparatos; snapshots de precios separados del código de tarifa.

### Fase 2 - Importador

- Selector PDF y extracción de texto.
- Parser CFE común + 01, 1B y PDBT.
- Validaciones, revisión y merge de los 11 periodos.
- Fixtures anonimizadas y pruebas de regresión por cada layout.

### Fase 3 - Multi-servicio y premium

- Selector de servicio en Inicio, Aparatos, Plan e Historial.
- Alta, archivo y cambio de medidor.
- `EntitlementPolicy` con límites 1/2/5.
- Paywall al confirmar un servicio que excede el límite.

### Fase 4 - Confianza y producción

- OCR de PDFs escaneados.
- Más tarifas domésticas y comerciales.
- Cifrado, exportación/borrado y telemetría sin datos personales.
- Google Play Billing y restauración de compras.

## 10. Criterios de aceptación iniciales

- Los cuatro PDFs de referencia importan sin intervención técnica.
- Se extraen correctamente servicio, tarifa, medidor, periodo, lecturas, kWh y total.
- Se importan 11 filas históricas de cada segunda página.
- Las cuatro validaciones lectura/multiplicador/consumo pasan.
- Reimportar no duplica recibos ni historial.
- Un cambio de medidor conserva el servicio y su historial.
- Gratis no puede activar un segundo servicio, pero puede revisar lo detectado antes del paywall.
- Plus admite dos servicios y Familiar cinco.
- Ningún log contiene nombre, domicilio, cuenta, servicio, RMU, QR o texto completo del recibo.
- El usuario distingue servicios por alias, pero la deduplicación usa número de servicio/RMU.
- `01` se normaliza a `1`; PDBT nunca usa bloques domésticos.
- El catálogo ofrece al menos 30 aparatos con IDs únicos y permite agregar varios del mismo tipo.
- El aparato personalizado calcula kWh desde watts y uso, conserva la fórmula y se marca con confianza baja hasta calibrarse.
