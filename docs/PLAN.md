# Plan de trabajo — Network Limiter

## 1. Objetivo

Aplicación Android (Java, sin Kotlin) que crea una VPN local con `VpnService`,
captura **todo** el tráfico IPv4/IPv6 del dispositivo, lo reenvía a Internet
mediante sockets normales protegidos con `VpnService.protect()` y aplica un
límite de velocidad bidireccional inspirado en el comportamiento real de
**CAKE** tal como lo configura `chococrispy`:

```sh
tc qdisc add dev $iface root cake bandwidth $upload   besteffort triple-isolate wash rtt $latency
tc qdisc add dev ifb0   root cake bandwidth $download besteffort triple-isolate wash rtt $latency
```

Es decir: un único *tin* (besteffort), aislamiento triple (por host origen,
host destino y flujo), DSCP ignorado (*wash*) y un objetivo de latencia de
referencia (`rtt`) que alimenta el AQM Cobalt.

## 2. Arquitectura

```text
                 +---------------------------------------------+
 App Android     |              LimiterVpnService              |
                 |  VpnService.Builder: 10.111.0.1/32, ::/0    |
                 |  (::/0 solo si la red subyacente tiene IPv6)|
                 +----------------------+----------------------+
                                        | ParcelFileDescriptor (TUN)
                        +---------------v----------------+
                        |          TunnelEngine          |
                        |  hilo lector TUN + selector    |
                        |  IPv4/IPv6 · TCP · UDP · ICMP  |
                        +----+----------------------+----+
                             |                      |
                    upShaper (Cake)         downShaper (Cake)
                             |                      |
                 SocketChannel saliente      escritura al TUN
                 (protect() best-effort; app excluida)
```

### Hilos

| Hilo | Responsabilidad |
| --- | --- |
| Lector TUN | `read()` bloqueante del descriptor; encola paquetes y despierta al selector. |
| Motor | Único dueño del estado TCP/UDP; `Selector` no bloqueante para los sockets salientes; timers de retransmisión y timeouts. |
| Shaper subida | Cola Cake del tráfico dispositivo → Internet; entrega al socket. |
| Shaper bajada | Cola Cake del tráfico Internet → dispositivo; escribe en el TUN. |

No hay un hilo por conexión: un `Selector` multiplexa todos los sockets.

### Concurrencia y fairness

El hilo del motor es el único que toca los mapas TCP/UDP, pero comparte estado
con los hilos de los shapers. Para que una descarga o una subida intensa no
deje sin servicio al resto del tráfico:

- Cada despacho de E/S tiene un presupuesto (64 iteraciones TCP, 32 UDP). Al
  agotarlo se conserva el interés `OP_READ`/`OP_WRITE` y el selector vuelve a
  llamar; una conexión no puede acaparar el hilo del motor.
- `pendingUpBytes` es un `AtomicLong`: lo leen/escriben el motor y el shaper de
  subida al mismo tiempo. Antes era un `long` y una actualización perdida podía
  dejar la ventana de recepción cerrada para siempre.
- El buzón del TUN tiene una cola prioritaria para ICMP/ICMPv6 (256 paquetes)
  además del buzón general (2048). Así un `ping` sigue respondiendo aunque una
  página web sature el buzón con TCP.
- `refreshInterests()` itera copias de los mapas para que un cierre durante la
  actualización no invalide el iterador.
- Los descartes de subida ya no escriben un `WARN` por paquete; solo se
  registran en modo de trazado de paquetes, evitando una tormenta de logs que
  compita con el motor.
- Un watchdog cada 5 s comprueba que el hilo del motor completa iteraciones. Si
  se queda bloqueado, vuelca su pila en la pestaña de registros.
- El reenviador ICMP usa 4 hilos, 64 sondas pendientes y 2 s de timeout (más
  un respaldo de 1 s con `isReachable`) para tolerar una red subyacente
  cargada sin dejar de responder al ping.

## 3. Pila TCP/IP en espacio de usuario

### Capa IP
- IPv4: cabecera variable, validación de checksum, descarte de fragmentos.
- IPv6: cabecera fija + extensión (hop-by-hop, routing, destination options);
  descarte de fragmentos.
- Construcción de paquetes IPv4/IPv6 con checksum correcto.
- ICMP echo (v4/v6) se reenvía al destino real con un socket ICMP sin
  privilegios (`SOCK_DGRAM` + `IPPROTO_ICMP`/`IPPROTO_ICMPV6`). Solo se
  construye la respuesta si la sonda recibió un echo reply real; si el kernel
  no permite el socket, se usa `InetAddress.isReachable()`. Nunca se responde
  localmente con una latencia falsa.
- UDP de gran tamaño: fragmentación IPv4 y ICMPv6 *Packet Too Big*.

### TCP
Máquina de estados completa (SYN, SYN-ACK, ESTABLISHED, FIN_WAIT_1/2,
CLOSE_WAIT, CLOSING, LAST_ACK, TIME_WAIT):
- Negociación de MSS y **window scaling**.
- Números de secuencia con aritmética modular de 32 bits.
- Buffers de recepción, reensamblado fuera de orden y ventana anunciada.
- Retransmisión por RTO (RFC 6298, Karn), *fast retransmit* por 3 ACK
  duplicados y control de congestión Reno simplificado. Un RTO que solo
  encuentra paquetes aún en la cola del shaper no duplica el RTO ni reduce
  `cwnd`: es retraso, no pérdida.
- Si el shaper de subida descarta por desbordamiento, se libera el hueco
  correspondiente en la ventana de recepción de la conexión; de lo contrario
  la ventana quedaría cerrada para siempre.
- Semántica de medio cierre (FIN en ambos sentidos), RST y timeouts.
- El socket saliente se abre en modo no bloqueante, se protege con
  `protect()` **antes** de conectar y se registra en el selector.

### UDP
- NAT por flujo (5-tupla) con `DatagramChannel` no bloqueante y `protect()`
  best-effort. La app está siempre excluida del túnel, así que un `protect()`
  fallido no cierra el flujo.
- Timeout de inactividad y reenvío bidireccional.

## 4. Limitador: CAKE real, no un token bucket genérico

La implementación sigue `net/sched/sch_cake.c` (Linux 6.6) en lo que afecta al
comportamiento observable:

### 4.1 Shaper
- Reloj de envío por paquete: `time_next_packet += len * 1e9 / rate`.
- Sin crédito acumulado en reposo: al encolar con la cola vacía se reinicia el
  reloj a `now` (equivalente al reset que hace `cake_dequeue` al vaciarse).
- *Failsafe* a 1.5× del ritmo global.
- `rate = 0` desactiva el shaper.

### 4.2 DRR++ con aislamiento triple
- `flow_quantum = clamp(rate / 4096, 300, 1514)`.
- `quantum_div[n] = 65535 / n`; el quantum efectivo de un flujo es
  `(flow_quantum * quantum_div[host_load] + random(65536)) >> 16`, con
  `host_load = max(flujos_bulk_origen, flujos_bulk_destino)` (dither como Cake).
- Listas `new_flows` (sparse), `old_flows` (bulk) y `decaying_flows`.
  Los flujos nuevos se sirven antes; al agotar su déficit pasan a bulk.
- Al entregar el último paquete de un flujo se cierra su ciclo de inmediato
  (pasa a `decaying` o se libera), como `cake_dequeue`, sin esperar a que
  llegue otro paquete.
- El déficit se descuenta con la longitud real del paquete.

### 4.3 AQM Cobalt
- Parámetros derivados del ritmo y del RTT configurado:
  - `mtu_time = mtu / rate`
  - `target = max(1.5 * mtu_time, 5 ms)`
  - `interval = max(rtt + target - 5 ms, 2 * target)`
  - `p_inc = 1/256`, `p_dec = 1/4096` (punto fijo 0.32).
- Estado por flujo: `count`, `rec_inv_sqrt` (Q0.32, misma iteración de Newton
  que el kernel), `drop_next`, `blue_timer`, `p_drop`, `dropping`.
- `sojourn = now - enqueue_time`; se marca congestión si supera `target` y
  `4 * mtu_time`. Caída determinista según la ley de control de CoDel más la
  probabilidad aleatoria de BLUE. Igual que el kernel, `rec_inv_sqrt` arranca
  en 0: la primera caída tras marcar congestión ocurre en el siguiente
  *dequeue*, y las posteriores respetan `interval / sqrt(count)`.
- Solo se descarta la cabeza si al flujo le queda más de un paquete
  (`flow->head` en Cake); con un único paquete se entrega.
- Cola límite: `max(4 * BDP, 4 MB)` acotada por `10240 * mtu` (15,36 MB con
  MTU 1500), como `cake_change`. Al desbordar se poda la **cola más larga**
  (no el paquete entrante) y se actualiza el estado BLUE de ese flujo.
- Métricas EWMA de retardo (media, pico, base) como Cake.

### 4.4 Direcciones y una desviación consciente
- **Bajada (Internet → dispositivo):** Cake completo, incluidas caídas del
  AQM. La pila TCP propia retransmite, igual que un emisor real.
- **Subida (dispositivo → Internet):** shaper + DRR++ sin caídas. Al terminar
  el TCP en espacio de usuario ya hemos confirmado los datos al dispositivo;
  descartarlos rompería la fiabilidad. La latencia se acota con:
  - ventana anunciada = `min(1.25 * BDP_subida, 512 KB)`;
  - presupuesto global de 8 MB de datos aceptados y aún no entregados; si se
    acerca al límite, todas las conexiones reducen su ventana;
  - cola de subida de 16 MB (sin AQM), para que un desbordamiento no pierda
    datos ya confirmados.
- Los buffers del socket saliente se dimensionan a `2 * BDP` (16 KB–256 KB)
  para que el kernel no acumule segundos de datos fuera del shaper.

## 5. Servicio y UI

- `LimiterVpnService` en primer plano (`specialUse`), notificación con acción
  de parada, DNS tomados de la red subyacente y *fallback* público.
- El servicio se declara `exported="true"` pero protegido por
  `BIND_VPN_SERVICE`: es el sistema quien debe poder enlazarlo para
  `onRevoke`, VPN siempre activa y cambios de red subyacente.
- La red subyacente se identifica explícitamente (se ignora cualquier red con
  `TRANSPORT_VPN`), de modo que al reiniciar el servicio no se heredan la
  dirección IPv6 ni los DNS de la VPN anterior.
- Solo se anuncia `::/0` cuando la red subyacente tiene una dirección IPv6
  global y una ruta por defecto IPv6; en redes IPv4-only no se blackholea el
  tráfico IPv6 de las apps.
- Se prefieren DNS IPv4; los DNS IPv6 se omiten si no hay IPv6 real. Si no se
  añadió ningún DNS IPv4, se usan `1.1.1.1` y `8.8.8.8` como respaldo.
- Los DNS link-local, loopback, multicast y los de la propia interfaz TUN se
  omiten: no son enrutables a través del TUN.
- **Persistencia en runtime:** notificación permanente e inamovible
  (`setOngoing(true)`, `setOnlyAlertOnce(true)`, categoría `service`,
  `FOREGROUND_SERVICE_IMMEDIATE`) que muestra estado y tasas en vivo, más un
  `PARTIAL_WAKE_LOCK` mientras la VPN está activa para que el CPU no duerma y
  el bucle de paquetes siga atendiendo el TUN. La actividad ofrece además un
  acceso directo a la exención de optimización de batería.
- Si el sistema recrea el servicio con una acción `UPDATE` huérfana, se
  detiene (`START_NOT_STICKY`) para no quedar vivo sin notificación.
- Actividad Java + AppCompat (sin Compose/Kotlin): estado, tiempo activo,
  sliders logarítmicos de bajada/subida, presets y estadísticas en vivo.
- **RTT configurable:** slider de 5–1000 ms (80 ms por defecto) que se guarda
  en `SharedPreferences` y se envía al motor con `ACTION_UPDATE`. Al cambiar el
  RTT se recalculan `target`, `interval` y el límite de buffer de Cake.
- **Navegación:** `DrawerLayout` con botón hamburguesa y `ViewFlipper` de tres
  páginas: «Límites y tráfico», «Split tunneling» y «Registros». El botón atrás
  cierra el drawer o vuelve a la página principal.
- **Registros:** búfer circular `Diag` de 3000 entradas que conserva todos los
  niveles (incluido debug) y se replica a Logcat si el servicio está activo.
  La pestaña permite activar el trazado paquete a paquete, limpiar, copiar al
  portapapeles y hacer auto-scroll. No requiere adb. El watchdog del motor
  escribe allí el volcado de pila si el multiplexado se queda bloqueado.
- **Split tunneling:** lista de apps instaladas con búsqueda, filtro de apps
  del sistema y selección múltiple. Modos: todas, solo seleccionadas y todas
  excepto las seleccionadas. La app propia se excluye siempre. Android no
  permite mezclar `addAllowedApplication` y `addDisallowedApplication`, así que
  cada modo usa una sola lista. Si el modo inclusivo queda vacío se enrutan
  todas. Aplicar cambios con la VPN activa envía `ACTION_RESTART`, que recrea
  la interfaz TUN.
- Configuración persistida en `SharedPreferences`; los cambios de límites y RTT
  se aplican en caliente. El estado encendido/apagado no se persiste.
- `QUERY_ALL_PACKAGES` para poder listar todas las apps en Android 11+; es una
  decisión deliberada y tiene implicaciones si se publica en Google Play.

## 6. Pruebas

- 34 pruebas unitarias JVM (`./gradlew testDebugUnitTest`):
  - `PacketTest`: checksums, parseo/construcción IPv4/IPv6/TCP/UDP, ICMP,
    fragmentación.
  - `SequenceTest`: aritmética modular de secuencias.
  - `CobaltTest`: caché `rec_inv_sqrt` bit a bit y ley de caída.
  - `CakeQueueTest`: ritmo, DRR++, prioridad de flujos nuevos, AQM, buffer,
    poda de la cola más larga con muchos flujos y cambio de ritmo con reloj
    inyectado.
  - `IcmpForwarderTest`: una sonda exitosa construye un echo reply real, una
    sonda fallida no inventa respuesta y los paquetes que no son echo request
    se ignoran.
  - `TcpConnectionTest`: handshake completo, dirección de SYN-ACK y ACK.
  - `UdpFlowTest`: NAT de subida/bajada, puertos y checksum.
  - `VpnConfigTest`: límites de ritmo/RTT, modos de split y copia profunda de
    la selección de apps.
- `./gradlew assembleDebug` como verificación de compilación.
- `./gradlew lintDebug` con 0 errores.
- Prueba manual en dispositivo: handshake, HTTP/HTTPS, DNS, IPv6, ping con RTT
  real (no ~1 ms), límite efectivo con `speedtest`/`curl`, revocación del
  permiso VPN, cambio de RTT en caliente, split tunneling en los tres modos y
  pestaña de registros sin adb.

## 7. Limitaciones conocidas

- Sin reensamblado de fragmentos IP entrantes (se descartan); UDP grande se
  fragmenta al bajar en IPv4 y se responde *Packet Too Big* en IPv6.
- Sin SACK ni timestamps TCP (se negocian fuera); retransmisión go-back-N.
- Sin ECN.
- El reensamblado fuera de orden usa claves por número de secuencia exacto;
  funciona con segmentación MSS estable, no con re-segmentaciones raras.
- Un solo dispositivo por VPN (el aislamiento por host degenera en
  aislamiento por flujo, que es el comportamiento correcto para ese caso).
- El split tunneling se fija al crear la interfaz TUN; cambiarlo con la VPN
  activa reinicia el servicio. La app propia queda siempre fuera del túnel.
- La lista de apps se carga en el hilo principal al abrir la página por primera
  vez; con muchísimas apps puede haber un retardo perceptible.
- `QUERY_ALL_PACKAGES` es necesario para listar todas las apps en Android 11+,
  pero Google Play restringe su uso.
- Se filtran multicast, broadcast y link-local (mDNS, SSDP, DHCP, ND): no
  tienen sentido a través del túnel.
- ICMP echo se reenvía al destino con un socket sin privilegios. Si el kernel
  no permite ese socket, se usa `InetAddress.isReachable()`; si ninguna sonda
  funciona, el ping no recibe respuesta (no se simula una latencia local).
- En redes IPv4-only no se anuncia `::/0` ni se usan DNS IPv6; si no hay DNS
  IPv4 se añaden resolvers públicos.

## 8. Estructura

```text
app/src/main/java/org/majhrs16/limiter/
├── MainActivity.java
├── LimiterVpnService.java
├── AndroidIcmpProbe.java
└── core/
    ├── VpnConfig.java · Stats.java · Diag.java · Format.java
    ├── TunnelEngine.java
    ├── net/  IpPacket · TcpSegment · UdpDatagram · Icmp · IcmpProbe · IcmpForwarder · Checksum · FlowKey · HostKey · Delivery
    ├── tcp/  TcpConnection · TcpCallbacks
    ├── udp/  UdpFlow
    └── shaper/ CakeQueue · CakeShaper · Cobalt
app/src/test/java/org/majhrs16/limiter/core/
├── VpnConfigTest.java
├── net/PacketTest.java · IcmpForwarderTest.java
├── shaper/CobaltTest.java · CakeQueueTest.java
├── tcp/SequenceTest.java · TcpConnectionTest.java
└── udp/UdpFlowTest.java
```
