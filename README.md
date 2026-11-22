# AjParkourBastion 1.0.6 — Circuito estable y spawn de regreso

## Cambios
- El recorrido es un circuito cerrado de posiciones prevalidado completamente antes de iniciar. Solo se ponen tres bloques de colores reales en el mundo al mismo tiempo; el resto de la pista es virtual.
- Los saltos son horizontales, de 2 a 4 bloques entre centros, con dificultad progresiva.
- El comienzo del circuito se selecciona al azar en cada partida; **no te teletransporta mientras avanzas**.
- Las puntuaciones se validan con X/Y/Z y contacto con el suelo.
- `/ajparkour set spawn` guarda el punto **de regreso** al caer. Puedes ejecutarlo en cualquier zona del Lobby que sea segura. `/ajparkour set lobbyspawn` es un alias. `/ajparkour set inicio` guarda referencia para inicio (el circuito selecciona su arranque automáticamente).
- Mantiene `game.auto-elevate-max-blocks: 50`, el récord y los textos en YAML.

## Actualización
1. Actualiza código fuente en GitHub y compila con Actions.
2. Apaga el Lobby, sustituye SOLO el JAR de AjParkourBastion. No borres `plugins/AjParkourBastion/records.yml`.
3. Si conservas un `config.yml` anterior, la opción nueva `area.return-spawn` se crea al ejecutar `/ajparkour set spawn`.
4. Entra al lobby en el punto donde deben aparecer los jugadores al caerse, usa `/ajparkour set spawn`, luego `/ajparkour start`.
5. Evita cargar el antiguo `ajParkour.jar` a la vez.

Si el circuito no encuentra un perímetro de aire totalmente libre en algún nivel de la región, mostrará un error claro. En ese caso es necesario una franja de aire rectangular, o aumentar altura permitida.
