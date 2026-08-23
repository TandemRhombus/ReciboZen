# Publicar ReciboZen en Google Play — guía real

---

## 0. Antes que nada: no me mandaste el error

Escribiste "me salió este error app.recibozen.android" pero no llegó el texto del error ni una captura. **No voy a adivinar qué falló y darte un diagnóstico inventado.** Pásame el mensaje completo.

Lo que sí hice fue eliminar la causa más probable. Tu proyecto todavía tenía `app/build/`, `build/` y `.kotlin/` con el paquete **viejo** dentro: el `BuildConfig` y la clase `R` generados decían `com.recibozen.app`. Cuando yo compilé aquí, borré esas carpetas antes — por eso a mí me funcionó y a ti no.

Las moví a `tmp/_to_delete/caches/`. Son 100% regenerables. Abre Android Studio, **File → Invalidate Caches / Restart**, y compila.

Revisé también `.idea/`: la única mención al paquete viejo está en `workspace.xml`, en un mapa de timestamps de "play dynamic filters". Es inofensivo y se regenera solo.

Si después de eso sigue fallando, mándame el texto y lo vemos.

---

## 1. Faltaba algo para poder publicar: la firma

Tu `build.gradle.kts` **no tenía ningún `signingConfig`**. El release salía como `app-release-unsigned.apk`. Play no acepta nada sin firmar, así que esto bloqueaba la publicación aunque tuvieras la cuenta.

Ya está puesto, y **no me creas que funciona porque compila**: generé una llave desechable, firmé un APK y lo verifiqué con `apksigner`. Sale firmado v1+v2+v3. Después borré la llave y el `keystore.properties` de prueba.

### Lo que te toca hacer (una sola vez)

**a) Genera tu llave de subida.** En la carpeta del proyecto:

```bash
keytool -genkeypair -v -keystore recibozen-upload.jks -alias recibozen -keyalg RSA -keysize 4096 -validity 10000
```

Te va a pedir una contraseña y tus datos. Anota la contraseña.

**b) Crea `keystore.properties` en la raíz del proyecto:**

```properties
storeFile=recibozen-upload.jks
storePassword=tu-contraseña
keyAlias=recibozen
keyPassword=tu-contraseña
```

**c) Ya lo agregué al `.gitignore`** (`keystore.properties`, `*.jks`, `*.keystore`). Verifica con `git status` que no aparezcan antes de commitear.

### Esto es lo más importante de todo el documento

**Si pierdes ese archivo `.jks`, no puedes volver a actualizar tu app jamás.** No hay recuperación. Ni con soporte, ni pagando.

Haz **dos copias fuera del proyecto**: una en un USB y otra en un servicio en la nube. Y guarda la contraseña en un gestor, no en un `.txt` junto al archivo.

### Un detalle que casi nadie ve venir

Tu app tiene `AppInstallTrustChecker` y la propiedad `RECIBOZEN_RELEASE_CERT_SHA256`. Con **Play App Signing** (obligatorio para apps nuevas), Google **re-firma tu app con su propia llave** antes de entregarla. La firma que ve el teléfono del usuario **no es la tuya**.

O sea que ese SHA-256 tiene que ser el del **"App signing key certificate"** que aparece en Play Console → *Configuración de la app → Integridad de la app*, **no** el de tu llave de subida. Si pones el tuyo, la app se va a rechazar a sí misma en producción y no vas a entender por qué.

Ese valor lo llenas después de subir el primer bundle, cuando Play te muestre su llave.

---

## 2. Sube un `.aab`, no un `.apk`

Play exige **Android App Bundle** para apps nuevas.

```bash
./gradlew bundleRelease
```

Queda en `app/build/outputs/bundle/release/app-release.aab`. Ya lo verifiqué: se genera bien.

---

## 3. Qué track usar — la parte que te importa

Tu pregunta es "interna, cerrada o abierta". La respuesta corta: **las tres, en ese orden, y la cerrada es obligatoria**.

| Track | Testers | Qué tan rápido | ¿Cuenta para producción? |
|---|---|---|---|
| **Interna** | hasta 100 | minutos | **No** |
| **Cerrada** | por lista de correos o grupo | horas / revisión | **Sí, es la obligatoria** |
| **Abierta** | cualquiera con el enlace | revisión completa | No es requisito |

La regla que decide tu calendario: para cuentas **personales** nuevas, Google exige **12 testers opted-in de forma continua durante 14 días** en una **prueba cerrada** antes de que puedas siquiera *solicitar* acceso a producción.

**Interna NO cuenta.** Google lo dice explícito: *"You must run a closed test before applying to publish your app to production."* Es el error que más tiempo le cuesta a la gente: pasan dos semanas en interna y descubren que el reloj nunca arrancó.

Y "continuo" es literal: si alguien se sale antes de los 14 días, ese tester no cuenta y no puedes reemplazarlo a última hora.

### El orden que te recomiendo

**1. Interna, hoy mismo.** Sube el `.aab`, agrega tu propio correo y 2 o 3 amigos. Sale en minutos. Sirve para descubrir lo que solo se ve instalado desde Play: que el ícono se ve bien, que la firma quedó, que `OFFICIAL_INSTALL_REQUIRED` no está bloqueando tu propia app. **Aquí es donde vas a probar los tres arreglos del tutorial.**

**2. Cerrada, en cuanto la interna se vea decente.** Aquí arranca el reloj de 14 días. No esperes a que la app esté "perfecta": el reloj corre mientras tú sigues programando, y esas dos semanas son gratis si empiezan ya y carísimas si empiezan en un mes.

**3. Solicita producción** al día 14, con los 12 todavía dentro.

**4. Abierta** solo si quieres feedback amplio antes de producción. No es requisito y agrega revisión.

### Los 12 testers son tu cuello de botella

No es un problema técnico y es el que más tarda. Necesitas **12 cuentas de Google reales** que acepten la invitación y **no se salgan en 14 días**. Familia, compañeros de clase, el grupo de WhatsApp de la carrera. Diles explícitamente: *"instálala y no la desinstales durante dos semanas, aunque no la uses"*.

Empieza a juntarlos **hoy**, en paralelo con todo lo demás.

---

## 4. Lo que Play te va a pedir además del `.aab`

Esto también toma tiempo y no es código:

- **Política de privacidad en una URL pública.** La pantalla que ya tienes dentro de la app **no cuenta**: Play pide un enlace web. Puedes publicar el mismo texto en una página de GitHub Pages gratis. Ya te lo tenía como pendiente desde la ronda 5.
- **Ficha de tienda**: título, descripción corta y larga, ícono 512×512, gráfico destacado 1024×500, y capturas de pantalla del teléfono.
- **Cuestionario de contenido** y **Data safety** (qué datos recolectas). Tu app tiene una buena respuesta aquí: casi nada sale del teléfono. Contesta con el contenido de tu pantalla de privacidad.
- **Verificación de identidad** de la cuenta de desarrollador. Toma días.

---

## 5. Sobre cobrar: todavía no puedes

Recuerda que tu backend responde `503 play_verification_not_configured`. Puedes publicar la app gratis y hacer todo el proceso de testing sin tocar eso, pero **Plus no se puede cobrar** hasta que configures Play Billing, y eso necesita la cuenta ya creada.

Es un argumento más para abrir la cuenta hoy: desbloquea Billing y arranca el reloj al mismo tiempo.

---

## 6. Orden concreto para esta semana

1. Invalidate Caches y compila. Si falla, mándame el error.
2. Genera el `.jks`, crea `keystore.properties`, **haz dos respaldos**.
3. Crea la cuenta de Play Console ($25) y empieza la verificación de identidad.
4. `./gradlew bundleRelease` → sube a **prueba interna**.
5. Instálala desde Play y prueba el tutorial: paso 3 del DAC, la tarjeta contra los botones, y de Configuración del 1 al 7.
6. Junta los 12 testers.
7. Publica la política de privacidad en una URL.
8. Sube a **prueba cerrada** y arranca el reloj de 14 días.

---

## Fuentes

- [App testing requirements for new personal developer accounts](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en)
- [Get started with Play Console](https://support.google.com/googleplay/android-developer/answer/6112435?hl=en)
