# Network Limiter

VPN local para Android que limita el tráfico del dispositivo con un shaper
inspirado en **CAKE** (`besteffort triple-isolate wash rtt 80ms`), tal como lo
configura `chococrispy`. Todo en **Java**, sin Kotlin ni código nativo.

- Intercepta IPv4/IPv6 con `VpnService` y reenvía con sockets protegidos.
- Pila TCP/UDP en espacio de usuario (handshake, ventanas, RTO, fast retransmit).
- Dos colas Cake independientes: subida y bajada.
- RTT de referencia configurable (5–1000 ms) para el AQM Cobalt.
- ICMP echo reenviado al destino real con una sonda sin privilegios; nunca se
  inventa una respuesta local.
- *Split tunneling* por app: todas, solo seleccionadas o todas menos las
  seleccionadas, desde un menú lateral.
- Pestaña **Registros** con búfer circular de todos los niveles (incluido
  debug), sin necesidad de adb.
- Notificación en primer plano permanente + `PARTIAL_WAKE_LOCK` para que el
  servicio no se duerma.
- UI AppCompat oscura con sliders logarítmicos (64 kbps – 100 Mbps), presets y
  métricas en vivo.

El diseño completo, las decisiones y las limitaciones están en
[`docs/PLAN.md`](docs/PLAN.md).

## Requisitos

- Android 8.0 (API 26) o superior.
- SDK de Android en `/opt/android-sdk` (o ajusta `local.properties`).
- JDK 17. El wrapper de Gradle ya está incluido.

## Compilar e instalar

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Uso

1. Abre la app y pulsa **Activar límite**. Acepta el diálogo de permiso VPN de
   Android y, en Android 13+, el permiso de notificaciones.
2. Ajusta bajada/subida con los sliders o los presets. Los cambios se aplican
   en caliente.
3. Ajusta el **RTT de referencia** con su slider. Es el valor que alimenta el
   AQM Cobalt; 80 ms reproduce la configuración de `chococrispy`.
4. Abre el menú lateral (☰) y entra en **Split tunneling** para elegir qué apps
   pasan por la VPN. Pulsa **Aplicar cambios**: si el túnel está activo se
   reinicia para reconstruir la interfaz TUN con la nueva lista.
5. En el menú lateral, abre **Registros** para ver la actividad del motor y de
   la VPN. Puedes activar el trazado paquete a paquete, limpiar el búfer o
   copiar todo al portapapeles. Los mensajes de nivel debug también se guardan.
6. Pulsa **Evitar la suspensión** y concede la exención de batería para que el
   sistema no mate el servicio en segundo plano.
7. La notificación permanente muestra las tasas y permite detener la VPN.

> El wake lock y la VPN activa consumen batería de forma continua; es el precio
> de mantener el túnel y el shaper funcionando sin que Android duerma el proceso.

### Split tunneling

Android permite dos listas excluyentes: apps permitidas o apps denegadas. La
app ofrece los tres modos habituales:

| Modo | Comportamiento |
| --- | --- |
| Todas las apps | Se enrutan todas; la propia app queda fuera del túnel. |
| Solo las seleccionadas | Solo las apps marcadas usan la VPN. |
| Todas excepto las seleccionadas | Todas menos las marcadas usan la VPN. |

- La propia app nunca entra en la VPN: necesita la red real para mostrar
  métricas y para que el sistema pueda revocar el permiso.
- Si el modo inclusivo queda vacío, se comporta como «todas» para no dejar el
  dispositivo sin red.
- En Android 11+ la lista completa de apps requiere `QUERY_ALL_PACKAGES`. Es
  una decisión deliberada para poder mostrar también apps sin lanzador; tenlo
  en cuenta si publicas en Google Play.
- La configuración de split se guarda en `SharedPreferences`; el estado
  encendido/apagado de la VPN no se persiste entre reinicios.

## Pruebas

```sh
./gradlew testDebugUnitTest
./gradlew lintDebug
```

Las pruebas JVM cubren checksums y paquetes IPv4/IPv6/TCP/UDP, ICMP,
fragmentación, aritmética de secuencias, handshake TCP, NAT UDP, Cake (ritmo,
DRR++, Cobalt, buffer), la caché de `rec_inv_sqrt` bit a bit, la configuración
de split tunneling y el reenviador ICMP (éxito, fallo y paquetes que no son
echo request).

La verificación de compilación es `./gradlew assembleDebug`. No hay
dispositivo/emulador en este entorno, así que la validación en runtime
(handshake real, DNS, IPv6, límite efectivo) queda pendiente.

## Estructura

```text
app/src/main/java/org/majhrs16/limiter/
├── MainActivity.java          UI, drawer, sliders, selector de apps y registros
├── LimiterVpnService.java     VpnService + split + notificación + wake lock
├── AndroidIcmpProbe.java      Sonda ICMP real (socket sin privilegios)
└── core/
    ├── VpnConfig.java         Preferencias: ritmos, RTT, MTU y split
    ├── Diag.java              Búfer circular de logs + espejo a Logcat
    ├── TunnelEngine.java      TUN, selector, pila TCP/UDP, shapers
    ├── net/                   IPv4/IPv6, TCP, UDP, ICMP, checksums
    ├── tcp/                   conexión TCP terminada en usuarios
    ├── udp/                   NAT UDP por 5-tupla
    └── shaper/                CakeQueue, CakeShaper, Cobalt
```

## Limitaciones

- Sin reensamblado de fragmentos IP; sin SACK, timestamps ni ECN.
- La subida no aplica caídas AQM (los datos ya se confirmaron al dispositivo);
  la latencia se acota con la ventana y el presupuesto de cola.
- ICMP echo se reenvía al destino con un socket ICMP sin privilegios. Si el
  kernel no lo permite, se usa `InetAddress.isReachable()`; si ninguna sonda
  funciona, no se responde. Multicast/broadcast/link-local se filtran.
- Solo se anuncia `::/0` cuando la red subyacente tiene una dirección IPv6
  global y una ruta por defecto IPv6. Los DNS IPv6 se omiten en redes
  IPv4-only y, si no hay ningún DNS IPv4, se añaden `1.1.1.1` y `8.8.8.8` como
  respaldo.
- El split tunneling se aplica al arrancar el túnel: cambiarlo con la VPN
  activa provoca un reinicio breve del servicio.
- La lista de apps se carga en el hilo principal al abrir la página por primera
  vez; en dispositivos con muchísimas apps puede notarse un pequeño retardo.

## Licencia

Este proyecto se distribuye bajo la **GNU General Public License v3.0**. El
texto completo está en [`LICENSE`](LICENSE).
