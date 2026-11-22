# AjParkourBastion 1.0.5 — corrección de teletransporte y caída

## Cambios

- La recuperación de un salto sin salida **ya no teletransporta al jugador al principio**. Se eliminan solo las plataformas futuras y se busca otra cadena desde la plataforma alcanzada.
- `game.auto-elevate-max-blocks: 50` por defecto; el código acepta 0–128. En servidores ya instalados, añadir o modificar este valor en `plugins/AjParkourBastion/config.yml`.
- `game.fall-distance: 5`: al caer más de cinco bloques por debajo de la plataforma alcanzada, se termina la partida, se conservan los récords y se devuelve al Lobby.
- `/ajparkour set lobbyspawn`: **ejecutar en la ubicación real del spawn del Lobby**; se guarda en `area.lobby-spawn` (mundo, posición, yaw, pitch).
- Si no se ha marcado un lobbyspawn, se vuelve a la ubicación donde el jugador estaba cuando inició el parkour. Opcionalmente, se puede establecer `game.fall-command: spawn` para ejecutar `/spawn` (por ejemplo, DeluxeHub).
- El comando `/ajparkour set spawn` sigue siendo únicamente el lugar de la primera plataforma.

## Instalación

1. Sustituir código del repositorio por esta versión y compilar en GitHub Actions.
2. Apagar Lobby, reemplazar solo el JAR, reiniciar.
3. Conservar `plugins/AjParkourBastion/records.yml` y `config.yml`.
4. Cambiar en `plugins/AjParkourBastion/config.yml`: `game.auto-elevate-max-blocks: 50`. Agregar `game.fall-distance: 5` si no existe.
5. Pararse en el spawn real y ejecutar `/ajparkour set lobbyspawn` una vez.
6. `/ajparkour start` para probar.

**Nota:** este ZIP es código fuente; necesita compilarse y probarse dentro de Purpur 1.21.11. No se modifican récords existentes.
