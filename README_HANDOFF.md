# ReciboZen — Handoff Android nativo (Compose)

Este proyecto es la conversión del prototipo de diseño **ReciboZen V2** (Claude Design, `ReciboZen v2.dc.html`) a un proyecto Android nativo compilable en Kotlin + Jetpack Compose. Es **solo interfaz**: navegación, componentes visuales, estados de pantalla y datos de demostración. No hay persistencia real, motor de tarifas, cámara/OCR, ni servicios externos — eso lo agrega el siguiente desarrollador sobre la arquitectura ya separada que se explica abajo.

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

**Stack:** Kotlin 2.2.10 · AGP 9.3.1 · Compose BOM 2026.02.01 · Material 3 · Navigation Compose · ViewModel + StateFlow · una sola `Activity` (`MainActivity`).

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
- **history**: `HistoryViewModel`
- **settings**: `ProfileViewModel`, `AppearanceViewModel`, `StatesCatalogViewModel`

## Dónde están los datos simulados

Todo centralizado en **`demo/data/DemoReciboZenData.kt`**: meta, recibo base, aparatos, acciones del plan, historial de recibos, lecturas recientes, escenarios solares, datos de DAC/tarifa. Ningún feature tiene cifras sueltas fuera de ahí (excepto un puñado de literales estrictamente decorativos en `ApplianceSimulatorScreen.kt`, marcados como tales, porque ese simulador no representa ningún aparato real de la lista).

El estado de sesión (lo que el usuario puede tocar/cambiar) vive en **`demo/repository/ReciboZenSessionState.kt`** y se mutua únicamente a través de **`ReciboZenRepository`** (interfaz) / **`DemoReciboZenRepository`** (implementación en memoria, sin persistencia). Para conectar una fuente real, se reemplaza `DemoReciboZenRepository` por una implementación nueva de la misma interfaz — ningún Composable ni ViewModel necesita cambiar.

## Qué interacciones están simuladas (con efecto visible durante la sesión, no persistente)

- Agregar/quitar una acción del Plan (`repository.togglePlanAction`) — recalcula el recibo proyectado en vivo.
- Ajustar horas/semana de la PlayStation 5 (`repository.setPs5HoursPerWeek`) — recalcula su costo/kWh y el de Aparatos.
- Ajustar manecillas del medidor (`RzMeterDialCluster` + `repository.setDialAngle/nudgeDial/setDialDigit`) — matemática de ángulo→dígito idéntica a la del prototipo original.
- Elegir escenario solar (Económico/Recomendado/Mayor cobertura).
- Cambiar tema claro/oscuro/sistema (**esto sí es una función real y completa**, no solo visual de una pantalla: cambia el `ColorScheme` de toda la app).
- Registrar una lectura (agrega una fila al historial de sesión).
- Cambiar unidad Pesos/kWh en Historial.

## Qué NO tiene lógica real (a propósito)

- Las pantallas de foto (`ReadingPhotoScreen`, `ReadingPhotoUnclearScreen`) son ilustraciones estáticas — no hay cámara ni OCR.
- El simulador "¿Y si lo compro?" es enteramente demostrativo, no calcula nada a partir de datos reales.
- "Descargar mi preestudio" en Solar es un botón sin acción (`{}`).
- Ningún Composable calcula tarifas: los pocos cómputos que existen (recibo proyectado, lectura interpretada del dial, validación de lectura anómala) viven en `DemoReciboZenRepository`, no en la UI, y están explícitamente marcados como aritmética de demostración, no un motor de tarifas.

## Qué falta / integraciones pendientes para el siguiente desarrollador

- Persistencia real (Room/DataStore) reemplazando `DemoReciboZenRepository`.
- Motor de tarifas y cálculo de consumo real.
- Proyecciones y estimaciones DAC/solares reales.
- Cámara + reconocimiento del medidor (OCR) para las pantallas de foto.
- Importación real de recibos.
- Sincronización / backend.
- `feature/appliances/ApplianceUsageScreen.kt`: hoy solo persiste el cambio de horas para PS5 (única app. que el repo trackea reactivamente); Minisplit usa la misma UI pero su selección es local a la pantalla — extender el repositorio si se necesita que persista.
- `HistoryScreen.kt`: el toggle kWh usa una conversión provisional (`amountMxn / 2.1`) porque `BillPeriod` no trae kWh real — reemplazar cuando exista esa cifra.

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
