# AjParkourBastion v1.0.7 — BASTION MC, mapa Void analizado

## Hallazgo principal
El mapa usa numerosos bloques `minecraft:light` invisibles en el aire. La detección antigua `Block.getType().isAir()` los rechazaba como obstáculos y ocasionaba el fallo de generación. Esta versión acepta LIGHT y AIR para el suelo de las plataformas y los dos bloques superiores.

**Preservación del mundo:** el `BlockData` original, incluido el nivel de los bloques LIGHT, se guarda antes de generar cada plataforma y se restaura cuando desaparece o termina la partida.

## Configuración recomendada
- Mundo: `Void`
- Esquinas predeterminadas del área original: `-20,90,16` y `35,130,74`
- Altura preferida de plataformas `game.preferred-platform-y: 148`, comprobada en el mundo proporcionado para el área original.
- Elevación permitida `game.auto-elevate-max-blocks: 50`
- Spawn al caer `Void,0.560,59.000,29.409,0,0`

## Instalación
1. Reemplaza el código del repositorio y compila desde Actions (Java 21).
2. Detén el servidor y reemplaza el JAR anterior de AjParkourBastion.
3. No borres `records.yml` ni las regiones que hayas definido en tu `config.yml`.
4. En servidores con un `config.yml` anterior, agrega `game.preferred-platform-y: 148`. El spawn de retorno se migra automáticamente al encender si no estaba configurado.
5. Reinicia el servidor, usa `/ajparkour diagnostico` y `/ajparkour start`.

No es necesario volver a subir el mundo al servidor. El mapa enviado fue usado solo para analizar sus bloques.

La compilación y prueba en un Purpur real aún están pendientes; el código requiere validación en GitHub Actions y dentro del Lobby.
