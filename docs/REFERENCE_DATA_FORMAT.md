# Formato para incorporar tarifas y electrodomésticos investigados

Estado: contrato de datos v1  
Fecha: 9 de agosto de 2026

La base local ya tiene tablas versionadas para recibir ambos catálogos. Para que una investigación pueda incorporarse sin convertirla a mano, entregar cada conjunto como UTF-8 en un `.zip` con un `dataset.json` y uno de los CSV descritos abajo. No incluir recibos ni datos personales.

## Metadatos comunes (`dataset.json`)

```json
{
  "schemaVersion": 1,
  "kind": "APPLIANCES",
  "version": "2026-08-09-r1",
  "sourceName": "Investigación ReciboZen",
  "notes": "Alcance y criterios usados"
}
```

- `kind`: `APPLIANCES` o `CFE_TARIFFS`.
- `version`: identificador inmutable. Una corrección crea otra versión.
- ReciboZen calcula SHA-256, fecha de importación y cantidad de filas; nunca se sobrescribe silenciosamente un dataset anterior.

## Electrodomésticos (`appliances.csv`)

Encabezado exacto:

```csv
external_id,name,category,power_min_watts,power_typical_watts,power_max_watts,typical_kwh_per_cycle,typical_kwh_per_30_days,standby_watts,source_url,evidence_note
```

Ejemplo:

```csv
refrigerator_top_mount_14ft,Refrigerador 14 pies,COCINA,90,140,220,,38,2.1,https://fuente.example/ficha,"Rango observado en modelos comparables; ciclo variable"
```

Reglas:

- `external_id` estable, minúsculas y guiones bajos; no reutilizarlo para otro aparato.
- `category`: `CLIMA`, `COCINA`, `LAVADO`, `ENTRETENIMIENTO`, `COMPUTO`, `ILUMINACION`, `AGUA`, `CUIDADO_PERSONAL` u `OTROS`.
- Potencias en watts; consumos en kWh. Los números no llevan símbolos ni separadores de miles.
- Usar vacío cuando no aplique; no usar cero para “desconocido”.
- Un aparato puede tener potencia, kWh/ciclo o kWh/30 días. Para equipos cíclicos se prefiere una medición mensual respaldada.
- Cada fila necesita `source_url` o una explicación verificable en `evidence_note`. Conservar rangos; evitar falsa precisión.

## Tarifas (`tariffs.csv`)

Encabezado exacto:

```csv
tariff_code,region_code,effective_year_month,season,block_order,block_label,limit_kwh,rate_mxn_per_kwh,fixed_charge_mxn,demand_rate_mxn_per_kw,source_url
```

Ejemplos estructurales —los importes son deliberadamente vacíos, no tarifas vigentes—:

```csv
1,,2026-08,NONE,1,BASICO,150,,,,https://app.cfe.mx/Aplicaciones/CCFE/Tarifas/
1B,MERIDA,2026-08,SUMMER,1,BASICO,,,,,https://app.cfe.mx/Aplicaciones/CCFE/Tarifas/
PDBT,YUCATAN,2026-08,NONE,1,ENERGIA,,,,,https://app.cfe.mx/Aplicaciones/CCFE/Tarifas/
```

Reglas:

- `tariff_code`: `1`, `1A`, `1B`, `1C`, `1D`, `1E`, `1F`, `DAC` o `PDBT`; `01` se normaliza a `1`.
- `effective_year_month`: `AAAA-MM`. Nunca mezclar meses distintos en una misma fila.
- `season`: `NONE`, `SUMMER` u `OUT_OF_SUMMER`.
- `block_order` empieza en 1 y conserva el orden de aplicación.
- `limit_kwh` es el límite del bloque, no el consumo acumulado histórico. Vacío significa sin tope o no aplicable.
- Cargos en MXN sin IVA salvo que la fuente indique otra cosa; anotarlo en un archivo de notas si existe excepción.
- `region_code` debe representar la granularidad real de la publicación (localidad, región o estado), no una inferencia.
- Tarifas domésticas, DAC y PDBT no se deben forzar a una sola fórmula. Los campos que no apliquen quedan vacíos.
- Toda fila debe apuntar a la fuente oficial específica. Si la URL solo conduce a un selector, adjuntar también la fecha de consulta y el recorrido en las notas.

## Controles antes de integrar

1. Validar UTF-8, encabezados, enums, fechas y unidades.
2. Rechazar IDs duplicados o claves tarifarias repetidas dentro de la versión.
3. Verificar `min <= typical <= max` cuando existan los tres valores.
4. Detectar huecos o solapamientos entre bloques tarifarios.
5. Comparar una muestra contra la fuente y registrar quién la verificó y cuándo.
6. Importar primero a una base de prueba; el dataset activo solo cambia después de pasar pruebas de regresión.

Las tablas destino son `reference_datasets`, `appliance_references` y `tariff_rate_references` en Room. Esta separación permite cargar la investigación futura sin fijar tarifas cambiantes dentro de los Composables o del APK.
