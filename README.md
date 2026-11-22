# AjParkourBastion 1.0.3

Plugin independiente para **BASTION MC**, Minecraft **Purpur 1.21.11**, Java 21.

## Importante antes de instalar
- **Quita el antiguo `ajParkour` de la carpeta `plugins/`** (mueve su JAR a una carpeta fuera de `plugins`) porque ambos registran `/ajparkour` y `/ajpk`.
- **Haz copia de `plugins/ajParkour`** antes de quitar el JAR. NO borres `scores.db`, `positions.yml` ni los datos anteriores.
- Este plugin nuevo guarda sus récords en `plugins/AjParkourBastion/records.yml`. **No importa automáticamente los récords antiguos**.
- Solo permite una partida simultánea por arena: los bloques son físicos, no hologramas, y se restauran al terminar. No modificar la arena mientras alguien juega.

## Instalación
1. Sube todos los archivos a un repositorio GitHub con la estructura incluida (incluye `.github/workflows/build.yml`).
2. Actions -> Compilar AjParkourBastion -> Run workflow. Descarga el artifact `AjParkourBastion-JAR`.
3. Coloca el `.jar` generado en `plugins/`, deja fuera el viejo JAR de ajParkour y reinicia el servidor.
4. Ya viene con las coordenadas antiguas de **Parkour** de `positions.yml`: mundo `Void`, pos1 `-20,90,16`, pos2 `35,130,74`.
5. Si cambias el mapa usa `/ajparkour set posicion 1` desde la primera esquina, y `/ajparkour set posicion 2` desde la opuesta. La posición inicial se calcula dentro del área.
6. Opcional: `/ajparkour set spawn` desde encima del bloque donde deseas iniciar. Debe estar dentro del área.

## Comandos
- `/ajparkour start` o `/ajpk start`: inicia una partida.
- `/ajparkour salir`: termina y guarda el récord.
- `/ajparkour top`: ranking propio.
- `/ajparkour set posicion 1` y `2`: administra las esquinas (permiso `ajparkourbastion.admin`, OP por defecto).
- `/ajparkour set spawn`: establece el bloque de salida.
- `/ajparkour reload`: recarga YAML (termina partida activa).

## Mecánica
- Tres plataformas inicialmente visibles (`jumps-ahead: 2`).
- Plataformas físicas de concreto de colores configurables. Cuando llegas al siguiente bloque, el anterior desaparece y se genera otro.
- Dificultad progresiva a partir de los puntos 20, 35 y 50. Puntuación por chat y récord persistente.
- Validación de coordenadas X, Y, Z y suelo para evitar sumar puntos entre bloques apilados.
- Evita usar dos veces la misma columna X/Z entre las plataformas activas y nunca pone plataformas fuera del área. Si no hay salida válida, finaliza la partida limpiamente, sin puntos infinitos.

## Observación
Este es un proyecto nuevo; requiere compilación y pruebas en el servidor real. No se ha validado en una ejecución Purpur completa.


## v1.0.1: solución al inicio rechazado
Si el centro está ocupado, busca automáticamente un bloque inicial en una posición con tres bloques verticales libres en la arena. Si el mundo no existe o las esquinas son incorrectas, registra en consola la configuración y los mundos cargados. Si toda el área está ocupada, muestra un mensaje específico.

Si ya tienes un `plugins/AjParkourBastion/config.yml`, el plugin no lo reemplazará automáticamente. Puedes agregar `messages.no-space` manualmente o conservar el mensaje por defecto que entrega la nueva versión.

## v1.0.3 - generación con ruta previa
- Planifica las primeras 3 plataformas antes de colocar bloques físicos.
- Da prioridad a zonas libres de la parte alta de la arena en lugar del centro obstruido del Lobby.
- Utiliza saltos horizontales en 8 direcciones y distancias progresivas para evitar columnas superpuestas.
- Cuando una ruta se agota intenta regenerar la secuencia conservando puntos.
- La arena ya no depende solo del tamaño: necesita volumen de aire para las plataformas.
- Advertencia: si no hay espacio libre, mostrará un mensaje y registrará el área en consola.
- Reemplaza los archivos fuente en GitHub, recompila y sustituye solamente el JAR. Conserva `plugins/AjParkourBastion/records.yml`.
- Versión no probada en el servidor real; revisar errores de compilación y en juego.
