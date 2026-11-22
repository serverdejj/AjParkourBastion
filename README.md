# AjParkourBastion 1.0.4 — corrección de inicio

Esta versión corrige `/ajparkour set spawn`: ahora guarda la **posición de los pies** (aire) en vez del suelo sólido bajo el jugador. El bloque de inicio aparece en esa ubicación y el jugador se coloca encima.

El planificador revisa más posiciones por nivel y habilita, por defecto, una elevación automática hasta **24 bloques por encima de la coordenada Y máxima** del área para sortear los edificios del Lobby. Esto puede colocar el parkour más alto que el techo de la región marcada. Configura `game.auto-elevate-max-blocks: 0` para respetar la altura máxima de la arena.

Nuevo comando administrador: `/ajparkour diagnostico` para ver una muestra de posiciones libres, mundo y alturas examinadas. Si el parkour no inicia, el diagnóstico también aparece en el chat.

## Instalación

1. Actualiza el código del repositorio Github y compila con Actions (Java 21).
2. Apaga el Lobby y reemplaza el JAR.
3. Conserva `plugins/AjParkourBastion/records.yml`.
4. **IMPORTANTE:** el `config.yml` existente NO se sobrescribe al actualizar. Añade bajo `game:` la línea `auto-elevate-max-blocks: 24` o renombra solo `config.yml` para regenerarlo. Los registros no se deben borrar.
5. Ejecuta `/ajparkour diagnostico` y `/ajparkour start`.

Sigue siendo una versión sin prueba de integración en un servidor Purpur real. La existencia de posiciones libres no garantiza que haya una ruta completa; si el problema persiste, el diagnóstico permitirá distinguir entre falta de aire y restricciones de ruta.
