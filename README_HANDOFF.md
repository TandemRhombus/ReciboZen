# ReciboZen — Handoff Android nativo (Compose)

Este proyecto comenzó como la conversión visual del prototipo **ReciboZen V2** y ahora es un **MVP local-first funcional** en Kotlin + Jetpack Compose. Conserva la navegación y el diseño originales, pero ya incluye persistencia con DataStore, cálculo progresivo configurable, lecturas por manecillas/manual/foto, OCR local con ML Kit, edición de perfil/meta, aparatos editables, historial con kWh reales y exportación PDF del preestudio solar.

## Estado funcional actual (11 ago 2026)

- DataStore conserva preferencias y estado de sesión; Room conserva hogares, suministros, medidores, recibos, cargos, historial y datasets de referencia.
- Historial permite elegir un PDF de CFE **o fotografiar el recibo impreso** (`CfePhotoDocumentExtractor`, ML Kit on-device), procesarlo localmente, **corregir cualquier campo mal leído** e importar el recibo y sus periodos anteriores. Ambas rutas comparten el mismo parser y la misma pantalla de revisión editable.
- Perfil → Mis suministros permite nombrar y seleccionar servicios. Los límites viven en `EntitlementPolicy`: Gratis 1, Plus 2 y Familiar 5.
- El release está endurecido con R8, sin backup ni HTTP claro. Premium falla cerrado y nunca se restaura desde preferencias editables; el contrato de firma/backend está en `docs/SECURITY_HARDENING.md`.
- El backend mínimo está en `backend/` y desplegado de forma aislada en la Orange Pi. Expone salud y la clave pública, pero rechaza la activación premium hasta integrar identidad y validación real de Google Play.
- El arranque espera la hidratación de DataStore y abre Inicio si el onboarding ya fue completado.
- `TariffEngine` aplica escalones progresivos configurables a los kWh observados/proyectados. La estimación se presenta como orientativa, no como cotización oficial de CFE.
- **Las tarifas se auto-calibran con el recibo del usuario.** `TariffCalibration` reconstruye un `TariffRateSnapshot` a partir del `unitRateMxn` de cada tramo impreso en el último recibo importado, y `energySummary`/`billProjection` calculan con ese motor calibrado. Sin recibo se usan los escalones genéricos. Se decidió calibrar-desde-recibo en vez de empaquetar una tabla de tarifas porque CFE ajusta precios mensualmente y los umbrales por tramo dependen de la localidad: cualquier tabla nace vencida. `repository.tariffCalibration` expone `Coverage` (FULL/PARTIAL/NONE) para poder ser honestos sobre de dónde salió el número.
- Onboarding pide siempre meta de pago y tarifa (antes solo aparecían tras importar un PDF, dejando `goalMxn = 0` y el ritmo recomendado en cero para quien entraba sin recibo). El campo Ciudad puede autocompletarse con `CityLocator` (Geocoder on-device, permiso opcional, siempre editable). La **tarifa nunca se infiere de la ubicación**: SHCP la asigna por localidad según temperatura, así que adivinarla facturaría mal en silencio.
- Las horas de todos los aparatos configurables recalculan kWh, costo, participación, balance y un **pronóstico inferior-arriba del inventario**. Inicio compara ese resultado con el ritmo del medidor: son dos estimaciones del mismo consumo y nunca se suman. Si todavía no hay lecturas, el inventario puede producir una proyección preliminar con un rango de incertidumbre mayor; cuando sí las hay, el medidor conserva la prioridad y los aparatos muestran qué porcentaje explican.
- Editar uso admite cualquier valor entre 0 y 168 horas semanales, muestra el promedio diario y actualiza el pronóstico en vivo. Los equipos del catálogo que reportan watts en reposo incluyen también esa energía en su cálculo.
- Los filtros de Aparatos ordenan/filtran realmente y las sugerencias/simulador pueden agregar aparatos persistentes.
- Manual, manecillas y foto preparan el valor/método correcto antes de confirmar; las nuevas lecturas se fechan, aparecen en Historial y persisten.
- Foto abre la cámara instalada mediante `TakePicture`, procesa la imagen en el dispositivo con ML Kit, permite reintentar/corregir y conservar o descartar el archivo.
- Solar genera `preestudio_solar_recibozen.pdf` con `PdfDocument` y abre el selector nativo para guardarlo o compartirlo.

## Cómo abrir y compilar

1. Abrir la carpeta `C:\Users\emili\AndroidStudioProjects\ReciboZen` en Android Studio (o cualquier IDE con soporte Kotlin/Gradle).
2. El SDK de Android ya está declarado en `local.properties` (`sdk.dir`). Si se mueve de máquina, Android Studio lo vuelve a generar solo.
3. Sincronizar Gradle (Android Studio lo hace automático al abrir).
4. Compilar por línea de comandos:
   ```bash
   ./gradlew assembleDebug
   ```
   El APK queda en `app/build/outputs/apk/debug/app-debug.apk`.
5. Para correrlo: `Run ▶` en Android Studio sobre un emulador o dispositivo con **minSdk 28** o superior.

**Stack:** Kotlin 2.2.10 · AGP 9.3.1 · Compose BOM 2026.02.01 · Material 3 · Navigation Compose · ViewModel + StateFlow · Room 2.8.4 · DataStore Preferences · ML Kit Text Recognition · una sola `Activity` (`MainActivity`).

## Estructura de carpetas

```
com.recibozen.app
├── ReciboZenApp.kt, MainActivity.kt        — punto de entrada, Scaffold raíz (bottom nav + NavHost)
├── navigation/
│   ├── Routes.kt                           — TODAS las rutas de la app (constantes de string)
│   ├── ReciboZenNavHost.kt                 — grafo de navegación completo
│   ├── BottomNavBar.kt                     — barra inferior (4 pestañas)
│   └── RzViewModelFactory.kt               — helper `rzViewModel { }` para crear ViewModels con repo
├── core/
│   ├── designsystem/                       — Color.kt, Type.kt, Shape.kt, Spacing.kt, Theme.kt, RzIcons.kt
│   ├── components/                         — RzCard, botones, chips, gráficas (Canvas), RzMeterDial, estados, etc.
│   ├── model/                              — data classes puras (Appliance, PlanAction, ConfidenceLevel, ...)
│   └── formatting/                         — (reservado; hoy el formateo vive inline en cada Screen)
├── demo/
│   ├── data/DemoReciboZenData.kt           — TODOS los números y textos fijos de demostración, centralizados
│   └── repository/                         — ReciboZenRepository (interfaz) + DemoReciboZenRepository (impl en memoria)
└── feature/
    ├── onboarding/   home/   period/   appliances/   plan/   solar/   readings/   history/   settings/
```

Cada pantalla sigue el mismo patrón (nombres exactos, ej. Home):

```
HomeRoute        → conecta NavController + ViewModel, no dibuja nada por sí sola
HomeScreen       → composable "tonto": recibe HomeUiState + lambdas, no conoce el repo
HomeUiState      → data class inmutable
HomeViewModel    → expone StateFlow<HomeUiState> armado desde repository.session, reenvía acciones al repo
@Preview         → cada Screen tiene al menos un preview con datos falsos
```

Los Composables **nunca** crean repositorios, ni calculan tarifas, ni conocen Navigation directamente (salvo el propio `XxxRoute`, que solo llama `navController.navigate(Routes.X)` / `popBackStack()`).

## Mapa de rutas (`navigation/Routes.kt`)

| Ruta | Pantalla | Tipo |
|---|---|---|
| `Onboarding` | Bienvenida + alta del hogar (4 pasos internos) | inicio de la app |
| `Home` | Inicio | pestaña |
| `Appliances` | Aparatos | pestaña |
| `Plan` | Plan | pestaña |
| `History` | Historial | pestaña |
| `PeriodAnalysis` | Análisis del periodo | flujo |
| `ApplianceEmpty` | Aparatos · estado vacío | flujo |
| `ApplianceDetailPattern` (`applianceId`) | Detalle de aparato | flujo |
| `ApplianceUsagePattern` (`applianceId`) | Editar uso | flujo |
| `Simulator` | ¿Y si lo compro? | flujo |
| `Balance` | Balance de consumo | flujo |
| `Dac` | Promedio y DAC | flujo |
| `Solar` | Preestudio solar | flujo |
| `ReadingMethod` | Elegir método de lectura | flujo |
| `ReadingPhoto` | Foto del medidor (mock estático) | flujo |
| `ReadingPhotoUnclear` | Foto no reconocida (mock estático) | flujo |
| `ReadingManual` | Escribir lectura | flujo |
| `ReadingDial` | Ajustar manecillas (usa `RzMeterDialCluster`) | flujo |
| `ReadingConfirm` | Confirmar lectura | flujo |
| `HowWeCalculate` | Cómo lo calculamos | flujo |
| `Profile` | Perfil | flujo |
| `Services` | Mis suministros y selector activo | flujo |
| `BillImport` | Selección, revisión e importación de recibo PDF | flujo |
| `Appearance` | Modo oscuro / apariencia (funcional) | flujo |
| `StatesCatalog` | Catálogo de estados y errores (referencia de diseño) | flujo |

Los 4 destinos de pestaña usan el patrón estándar de bottom-nav (`popUpTo(start){saveState=true}` + `restoreState`) para conservar el estado de cada pestaña al cambiar. El resto se apila normalmente con `RzFlowTopBar` (flecha atrás + título) y responde al botón atrás del sistema de forma nativa vía `NavHost`.

## ViewModels

Uno por pantalla, mismo patrón (`class XxxViewModel(repository: ReciboZenRepository) : ViewModel()`), listados por paquete:

- **onboarding**: `OnboardingViewModel`
- **home**: `HomeViewModel`
- **period**: `PeriodAnalysisViewModel`
- **appliances**: `AppliancesViewModel`, `AppliancesEmptyViewModel`, `ApplianceDetailViewModel`, `ApplianceUsageViewModel`, `ApplianceSimulatorViewModel`
- **plan**: `PlanViewModel`, `BalanceViewModel`, `DacViewModel`
- **solar**: `SolarViewModel`
- **readings**: `ReadingMethodViewModel`, `ReadingPhotoViewModel`, `ReadingPhotoUnclearViewModel`, `ReadingManualViewModel`, `ReadingDialViewModel`, `ReadingConfirmViewModel`, `HowWeCalculateViewModel`
- **history**: `HistoryViewModel`, `BillImportViewModel`
- **settings**: `ProfileViewModel`, `ServicesViewModel`, `AppearanceViewModel`, `StatesCatalogViewModel`

## Dónde están los datos simulados

Todo centralizado en **`demo/data/DemoReciboZenData.kt`**: meta, recibo base, aparatos, acciones del plan, historial de recibos, lecturas recientes, escenarios solares, datos de DAC/tarifa. Ningún feature tiene cifras sueltas fuera de ahí (excepto un puñado de literales estrictamente decorativos en `ApplianceSimulatorScreen.kt`, marcados como tales, porque ese simulador no representa ningún aparato real de la lista).

El estado de sesión vive en **`demo/repository/ReciboZenSessionState.kt`** por compatibilidad con el handoff original y se muta únicamente mediante **`ReciboZenRepository`**. La app real crea **`PersistentReciboZenRepository`**: DataStore guarda preferencias y Room es la fuente durable de suministros/recibos. `DemoReciboZenRepository` permanece disponible para previews y pruebas aisladas.

## Interacciones funcionales y persistentes

- Agregar/quitar una acción del Plan (`repository.togglePlanAction`) — recalcula el recibo proyectado en vivo.
- Ajustar horas/semana de aparatos compatibles (`repository.setApplianceHoursPerWeek`) — recalcula costo, kWh, participación, balance y proyección.
- Ajustar manecillas del medidor (`RzMeterDialCluster` + `repository.setDialAngle/nudgeDial/setDialDigit`) — matemática de ángulo→dígito idéntica a la del prototipo original.
- Elegir escenario solar (Económico/Recomendado/Mayor cobertura).
- Cambiar tema claro/oscuro/sistema en toda la app.
- Registrar una lectura manual, por carátulas o por OCR con fecha, diferencia y método correctos.
- Cambiar unidad Pesos/kWh en Historial.
- Editar nombre, hogar, ciudad y meta desde Perfil.
- Agregar aparatos sugeridos o el aparato del simulador.
- Generar/compartir el PDF del preestudio solar.
- Importar un recibo CFE en PDF con texto nativo u OCR local, revisión humana, deduplicación por SHA-256 y persistencia del desglose/historial.
- Consultar desde Inicio y Plan la prevención DAC: usa una ventana móvil de 365 días, normaliza periodos irregulares por días reales y aplica el límite específico de la tarifa doméstica (1, 1A, 1B, 1C, 1D, 1E o 1F). Con historial suficiente muestra el margen restante y cuánto podría consumirse como máximo en el siguiente recibo; con datos incompletos lo presenta como estimación, nunca como una clasificación definitiva de CFE.
- Crear, nombrar y seleccionar suministros respetando el límite del plan.

## Backend provisional

- Código: `backend/` (Node.js sin dependencias externas, pruebas con `node:test` y Docker Compose endurecido).
- Operación y límites de seguridad: `docs/BACKEND_OPERATIONS.md`.
- El build Android ya contiene la clave pública P-256 de esta instancia mediante `RECIBOZEN_ENTITLEMENT_PUBLIC_KEY_B64`; la clave privada sólo existe en el servidor.
- El API conserva `127.0.0.1:8787` para diagnóstico y está publicado para la app en `https://api-recibozen.simapp.uk` mediante un túnel Cloudflare exclusivo; no se modificó el túnel ni la URL de SIM.
- `POST /v1/entitlements/exchange` valida el formato y responde `503 play_verification_not_configured`: no existe un atajo temporal para conceder Plus/Familiar.

## Límites actuales

- No hay login ni sincronización entre dispositivos; el producto funciona sin conexión y guarda todo localmente. El backend actual es sólo la frontera segura inicial para premium.
- No hay conexión ni descarga automática desde CFE; el usuario elige el PDF desde el selector del sistema.
- OCR reconoce números impresos/digitales; un medidor analógico de carátulas puede requerir el flujo interactivo de manecillas.
- Las tarifas embebidas son una configuración estimativa. Antes de publicar se debe conectar una fuente oficial/versionada o permitir captura completa de tarifa, temporada, región, subsidios y cargos del recibo.
- Las proyecciones solares usan escenarios deterministas, no irradiancia, geometría de techo ni una cotización de instalador.

## Integraciones necesarias antes de producción

- Fuente oficial/versionada de tarifas y reglas DAC por región/temporada; las tablas de datasets ya están preparadas y el formato de entrega está en `docs/REFERENCE_DATA_FORMAT.md`.
- Google Play Billing, autenticación y validación de suscripción en el backend para habilitar realmente Plus/Familiar; la política, el paywall y la frontera fail-closed ya están separados del SKU.
- Play Integrity y verificación de Billing deben cerrarse en backend antes de emitir los leases premium firmados. El verificador cliente y la comprobación de certificado/origen ya están implementados.
- Cuenta, backend, sincronización, cifrado y políticas de retención/borrado.
- Motor solar basado en irradiancia y validación profesional del techo.
- Pruebas instrumentadas adicionales para permisos/cámaras de fabricantes y accesibilidad completa.

## Decisiones visuales importantes

- **Tipografía**: el diseño pide Manrope + Inter vía Google Fonts. Sin acceso a red/archivos de fuente en este build, `core/designsystem/Type.kt` usa `FontFamily.Default` (Roboto del sistema) replicando exactamente la escala de tamaños/pesos/tracking del diseño. Cambiar `DisplayFontFamily`/`BodyFontFamily` ahí en cuanto se agreguen los `.ttf` reales a `res/font`.
- **Iconografía**: "Material Symbols Rounded" del diseño se mapeó 1:1 por nombre semántico a `material-icons-extended` clásico en `core/designsystem/RzIcons.kt`. Todo ícono usado en features pasa por ese objeto — no hay imports sueltos de `Icons.Filled.*` en las pantallas.
- **Sin color dinámico** (Material You): la app fija su propia paleta violeta/lima/verde/ámbar en ambos temas, en vez de heredar el wallpaper del dispositivo, porque el brief pide conservar el significado de esos colores.
- **Modo oscuro completo**: no es solo invertir el fondo — superficies, texto, chips, gráficas y estados semánticos (éxito/atención/solar) tienen su propio set de tokens oscuros en `core/designsystem/Color.kt` (`DarkColorScheme` + `DarkExtendedColors`).
- **Colores semánticos extendidos**: M3 no tiene slots nativos para éxito/atención/solar, así que se agregó `RzExtendedColors` + `RzTheme.extendedColors` (patrón de "extended color scheme", común en apps Compose grandes) en vez de forzarlos dentro de los roles de M3.
- **Ajustes de copy del brief** ya aplicados: "Paso X de 4" en onboarding, "Mayor cobertura" en vez de "Máxima" en Solar, "Administrar → Quitar de mi plan" en vez de "En seguimiento · quitar", tarjetas separadas de proyección vs. recibos reales en Historial, filtros de Aparatos sin recorte de texto, "5 lecturas recientes" en vez de "5 de 7 esta semana", "Guardar lectura" deshabilitado hasta revisar las 5 carátulas, redacción no-determinista de meta/rango en Inicio y Plan, mayor contraste en textos secundarios.
- **`RzMeterDial`** (núcleo del flujo de lectura) traduce literalmente la matemática del prototipo original (ángulo → dígito con acarreo de la carátula vecina, sentido horario/antihorario alternado) a Compose `Canvas` + gestos de arrastre (`pointerInput`/`detectDragGestures`), con semántica de accesibilidad por carátula.

## Accesibilidad

Áreas táctiles ≥48dp en todos los controles, `contentDescription` en íconos e ilustraciones estáticas, resúmenes de texto para lectores de pantalla en las gráficas (ej. Análisis del periodo), estados de confianza/selección señalizados con ícono + texto además de color, tipografía en `sp` (escala con el tamaño de fuente del sistema).

## Verificación hecha antes de este handoff

- `./gradlew assembleDebug` compila limpio (sin errores; solo 2 warnings menores de `Locale` deprecado).
- Todas las rutas del grafo de navegación resuelven a una pantalla implementada (auditado 1:1 contra `ReciboZenNavHost.kt`).
- Sin colisión de nombres de recursos entre los `strings_*.xml` de cada feature.
- Sin código muerto conocido ni TODOs bloqueantes.

## Verificación del MVP funcional

- `./gradlew testDebugUnitTest assembleDebug`: **BUILD SUCCESSFUL**.
- El parser tiene fixtures anonimizados de cuatro variantes: tarifa 1 de consumo bajo, tarifa 1 escalonada, 1B estacional y PDBT.
- APK instalado en `Medium_Phone_API_36.0` sin borrar datos.
- Onboarding → Inicio y reinicio: Inicio se restaura correctamente.
- Lectura manual 18,426 → confirmación → Historial: guardada como “escrita” y conservada tras reiniciar el proceso.
- Cámara nativa abierta desde “Escanear medidor”; una imagen sin cifras fue procesada localmente y mostró reintento sin crash.
- Exportación solar creó un PDF de 84,021 bytes y abrió el selector nativo.
- Un PDF real de tarifa 1 se importó en el emulador: 124 kWh, total, medidor y 11 periodos; apareció en Historial y sobrevivió el reinicio. La copia temporal del documento fue eliminada del emulador al terminar.
- Revisión de `logcat`: sin `FATAL EXCEPTION` de ReciboZen durante los recorridos anteriores.
