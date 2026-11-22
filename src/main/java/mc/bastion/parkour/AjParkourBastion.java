package mc.bastion.parkour;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.regex.*;

public final class AjParkourBastion extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private final Random random = new Random();
    private final Map<Block, BlockData> originals = new LinkedHashMap<>();
    private final Deque<Location> platforms = new ArrayDeque<>();
    private final List<Location> circuit = new ArrayList<>();
    private int circuitNextIndex = 0;
    private final Set<String> visitedColumns = new HashSet<>();
    private Player runner;
    private int score;
    private long lastProgress;
    private BukkitTask watchdog;
    private YamlConfiguration scores;
    private File scoresFile;
    private Location startReturnLocation;
    private final Map<UUID, Location> pendingRespawns = new HashMap<>();

    @Override public void onEnable() {
        saveDefaultConfig();
        scoresFile = new File(getDataFolder(), "records.yml");
        scores = YamlConfiguration.loadConfiguration(scoresFile);
        Bukkit.getPluginManager().registerEvents(this, this);
        PluginCommand command = getCommand("ajparkour");
        if (command != null) { command.setExecutor(this); command.setTabCompleter(this); }
        watchdog = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (runner == null) return;
            if (!runner.isOnline() || runner.isDead()) { end(false); return; }
            int idle = Math.max(15, getConfig().getInt("game.idle-seconds", 120));
            if (System.currentTimeMillis() - lastProgress > idle * 1000L) end(true);
        }, 20L, 20L);
        getLogger().info("AjParkourBastion activo. Usa /ajparkour start para probar.");
    }

    @Override public void onDisable() {
        if (watchdog != null) watchdog.cancel();
        end(false);
        saveScores();
    }

    private String color(String input) {
        if (input == null) return "";
        Matcher m = Pattern.compile("&#([A-Fa-f0-9]{6})").matcher(input);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            StringBuilder translated = new StringBuilder("§x");
            for (char c : m.group(1).toCharArray()) translated.append('§').append(c);
            m.appendReplacement(out, Matcher.quoteReplacement(translated.toString()));
        }
        m.appendTail(out);
        return ChatColor.translateAlternateColorCodes('&', out.toString());
    }
    private void msg(Player p, String key, String... vars) {
        String result = getConfig().getString("messages." + key, key);
        for (int i = 0; i + 1 < vars.length; i += 2) result = result.replace("%" + vars[i] + "%", vars[i + 1]);
        p.sendMessage(color(getConfig().getString("messages.prefix", "") + result));
    }
    private void saveScores() {
        try { if (!getDataFolder().exists()) getDataFolder().mkdirs(); scores.save(scoresFile); }
        catch (IOException e) { getLogger().severe("No se pudieron guardar records.yml: " + e.getMessage()); }
    }
    private int best(Player p) { return scores.getInt("players." + p.getUniqueId() + ".best", 0); }
    private void updateBest(Player p) {
        if (score <= best(p)) return;
        scores.set("players." + p.getUniqueId() + ".best", score);
        scores.set("players." + p.getUniqueId() + ".name", p.getName());
        saveScores();
        msg(p, "record", "best", "" + score);
    }
    private Location parsePosition(String key) {
        String raw = getConfig().getString("area." + key, "");
        World w = Bukkit.getWorld(getConfig().getString("area.world", "Void"));
        if (w == null || raw == null || raw.isBlank()) return null;
        try {
            String[] parts = raw.split(",");
            if (parts.length < 3) return null;
            return new Location(w, Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim()), Double.parseDouble(parts[2].trim()));
        } catch (NumberFormatException e) { return null; }
    }
    private boolean inArea(Location p) {
        Location a = parsePosition("pos1"), b = parsePosition("pos2");
        if (a == null || b == null || p == null || p.getWorld() != a.getWorld()) return false;
        return p.getBlockX() >= Math.min(a.getBlockX(), b.getBlockX()) + 2 && p.getBlockX() <= Math.max(a.getBlockX(), b.getBlockX()) - 2
            && p.getBlockZ() >= Math.min(a.getBlockZ(), b.getBlockZ()) + 2 && p.getBlockZ() <= Math.max(a.getBlockZ(), b.getBlockZ()) - 2
            && p.getBlockY() >= Math.min(a.getBlockY(), b.getBlockY()) + 3
            && p.getBlockY() <= Math.min(p.getWorld().getMaxHeight() - 4,
                    Math.max(a.getBlockY(), b.getBlockY()) - 4
                    + Math.max(0, Math.min(128, getConfig().getInt("game.auto-elevate-max-blocks", 50))));
    }
    private String column(Location l) { return l.getBlockX() + ":" + l.getBlockZ(); }
    private boolean emptySpace(Location candidate) {
        if (!inArea(candidate) || visitedColumns.contains(column(candidate))) return false;
        Block base = candidate.getBlock();
        if (!base.getType().isAir() || !base.getRelative(0,1,0).getType().isAir() || !base.getRelative(0,2,0).getType().isAir()) return false;
        // No se permiten columnas ocupadas por plataformas anteriores, aunque tengan otra Y.
        for (Location l : platforms) if (l.getBlockX() == candidate.getBlockX() && l.getBlockZ() == candidate.getBlockZ()) return false;
        return true;
    }
    private String level(int points) {
        int easy = getConfig().getInt("game.levels.easy-until", 20);
        int medium = getConfig().getInt("game.levels.medium-until", 35);
        int hard = getConfig().getInt("game.levels.hard-until", 50);
        if (points < easy) return "FÁCIL";
        if (points < medium) return "MEDIO";
        if (points < hard) return "DIFÍCIL";
        return "EXPERTO";
    }
    private int distanceForScore() {
        int easy = getConfig().getInt("game.levels.easy-until", 20);
        int medium = getConfig().getInt("game.levels.medium-until", 35);
        int hard = getConfig().getInt("game.levels.hard-until", 50);
        if (score < easy) return 2 + random.nextInt(2); // 1 o 2 bloques de hueco
        if (score < medium) return 3;
        if (score < hard) return 3 + random.nextInt(2);
        return 4;
    }
    // Ruta circular PREVALIDADA: no se decide el proximo salto al llegar al bloque.
    // El circuito se inspecciona completo antes de iniciar y se reutiliza por vuelta.
    // Es una pista virtual (sin tocar bloques fuera de las plataformas activas).
    private List<Location> makePerimeter(World world, int minX, int maxX, int minZ, int maxZ, int y) {
        List<Location> result = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) result.add(new Location(world, x, y, minZ));
        for (int z = minZ + 1; z <= maxZ; z++) result.add(new Location(world, maxX, y, z));
        for (int x = maxX - 1; x >= minX; x--) result.add(new Location(world, x, y, maxZ));
        for (int z = maxZ - 1; z > minZ; z--) result.add(new Location(world, minX, y, z));
        return result;
    }
    private boolean spaceAvailable(Location candidate) {
        Block base = candidate.getBlock();
        return base.getType().isAir() && base.getRelative(0,1,0).getType().isAir()
                && base.getRelative(0,2,0).getType().isAir();
    }
    private boolean circuitValid(List<Location> proposed) {
        if (proposed.size() < 28) return false;
        for (Location loc : proposed) if (!spaceAvailable(loc)) return false;
        return true;
    }
    private boolean prepareCircuit(Location a, Location b) {
        circuit.clear();
        int minX = Math.min(a.getBlockX(), b.getBlockX()) + 4;
        int maxX = Math.max(a.getBlockX(), b.getBlockX()) - 4;
        int minZ = Math.min(a.getBlockZ(), b.getBlockZ()) + 4;
        int maxZ = Math.max(a.getBlockZ(), b.getBlockZ()) - 4;
        int minY = Math.min(a.getBlockY(), b.getBlockY()) + 3;
        int maxY = Math.min(a.getWorld().getMaxHeight() - 4,
                Math.max(a.getBlockY(), b.getBlockY()) - 4
                + Math.max(0, Math.min(128, getConfig().getInt("game.auto-elevate-max-blocks", 50))));
        if (maxX - minX < 9 || maxZ - minZ < 9 || minY > maxY) return false;
        // Buscar primero niveles altos: la pista debe estar TOTALMENTE libre.
        for (int y = maxY; y >= minY; y--) {
            List<Location> proposed = makePerimeter(a.getWorld(), minX, maxX, minZ, maxZ, y);
            if (!circuitValid(proposed)) continue;
            circuit.addAll(proposed);
            // Varía el punto de inicio entre partidas, sin teletransportar durante ellas.
            circuitNextIndex = random.nextInt(circuit.size());
            getLogger().info("Circuito preparado con " + circuit.size() + " posiciones en Y=" + y);
            return true;
        }
        return false;
    }
    private int strideForScore() {
        int easy = getConfig().getInt("game.levels.easy-until", 20);
        int medium = getConfig().getInt("game.levels.medium-until", 35);
        int hard = getConfig().getInt("game.levels.hard-until", 50);
        if (score < easy) return 2 + random.nextInt(2);
        if (score < medium) return 3;
        if (score < hard) return 3 + random.nextInt(2);
        return 4;
    }
    private Location nextCircuitLocation() {
        if (circuit.isEmpty()) return null;
        circuitNextIndex = (circuitNextIndex + strideForScore()) % circuit.size();
        return circuit.get(circuitNextIndex);
    }
    private boolean seedPlatforms() {
        int ahead = Math.max(1, Math.min(4, getConfig().getInt("game.jumps-ahead", 2)));
        Location first = circuit.get(circuitNextIndex);
        if (!place(first)) return false;
        for (int i = 0; i < ahead; i++) {
            Location next = nextCircuitLocation();
            if (next == null || !place(next)) return false;
        }
        return true;
    }

    private Location lobbySpawn(Player player) {
        String saved = getConfig().getString("area.return-spawn", "");
        if (saved == null || saved.isBlank()) saved = getConfig().getString("area.lobby-spawn", "");
        if (saved != null && !saved.isBlank()) {
            try {
                String[] values = saved.split(",");
                World world = Bukkit.getWorld(values[0].trim());
                if (world != null && values.length >= 6) {
                    return new Location(world, Double.parseDouble(values[1]), Double.parseDouble(values[2]),
                        Double.parseDouble(values[3]), Float.parseFloat(values[4]), Float.parseFloat(values[5]));
                }
            } catch (NumberFormatException ex) {
                getLogger().warning("area.lobby-spawn inválido; se usará la posición de entrada.");
            }
        }
        if (startReturnLocation != null && startReturnLocation.getWorld() != null) return startReturnLocation.clone();
        return player.getWorld().getSpawnLocation().clone().add(.5, 0, .5);
    }

    private void returnToLobby(Player player) {
        Location destination = lobbySpawn(player);
        String command = getConfig().getString("game.fall-command", "").trim();
        // Si el administrador fijó /ajparkour set lobbyspawn, se usa ese destino preciso.
        // En caso contrario, se admite un comando de spawn de otro plugin.
        if (getConfig().getString("area.return-spawn", "").isBlank()
                && getConfig().getString("area.lobby-spawn", "").isBlank() && !command.isBlank()) {
            player.performCommand(command.startsWith("/") ? command.substring(1) : command);
        } else {
            player.teleport(destination);
            player.setFallDistance(0);
        }
    }

    private void fall(Player player) {
        if (runner != player) return;
        msg(player, "fell", "score", "" + score);
        end(true);
        Bukkit.getScheduler().runTask(this, () -> { if (player.isOnline()) returnToLobby(player); });
    }

    private void diagnose(Player p) {
        Location a = parsePosition("pos1"), b = parsePosition("pos2");
        if (a == null || b == null) { msg(p, "no-area"); return; }
        int minX = Math.min(a.getBlockX(), b.getBlockX()) + 2;
        int maxX = Math.max(a.getBlockX(), b.getBlockX()) - 2;
        int minZ = Math.min(a.getBlockZ(), b.getBlockZ()) + 2;
        int maxZ = Math.max(a.getBlockZ(), b.getBlockZ()) - 2;
        int minY = Math.min(a.getBlockY(), b.getBlockY()) + 3;
        int maxY = Math.min(a.getWorld().getMaxHeight()-4,
                Math.max(a.getBlockY(), b.getBlockY()) - 4
                + Math.max(0, Math.min(128, getConfig().getInt("game.auto-elevate-max-blocks", 50))));
        long free = 0;
        int sampleY = -1;
        if (minX <= maxX && minZ <= maxZ && minY <= maxY) {
            for (int y = maxY; y >= minY; y -= 2) for (int x = minX; x <= maxX; x += 3)
                for (int z = minZ; z <= maxZ; z += 3) {
                    Location test = new Location(a.getWorld(), x, y, z);
                    if (emptySpace(test)) { free++; if (sampleY < 0) sampleY = y; }
                }
        }
        p.sendMessage(color("&#6BFF80&lBASTION PARKOUR &8» &fDiagnóstico: &e" + free
                + " &fposiciones libres muestreadas; mundo &e" + a.getWorld().getName()
                + "&f, Y &e" + minY + "&7-&e" + maxY
                + "&f, ejemplo Y=&e" + sampleY + "&f."));
        if (free == 0) p.sendMessage(color("&cNo hay posiciones libres: aumenta la altura máxima de la arena o libera espacio."));
        else p.sendMessage(color("&7Si no inicia, revisa que haya varias posiciones cercanas entre sí para formar una ruta."));
    }

    private boolean place(Location p) {
        if (!emptySpace(p)) return false;
        Block block = p.getBlock();
        originals.put(block, block.getBlockData().clone());
        List<String> materials = getConfig().getStringList("game.materials");
        List<Material> allowed = new ArrayList<>();
        for (String name : materials) {
            Material material = Material.matchMaterial(name);
            if (material != null && material.isBlock() && material.isSolid()) allowed.add(material);
        }
        if (allowed.isEmpty()) allowed.add(Material.LIME_CONCRETE);
        block.setType(allowed.get(random.nextInt(allowed.size())), false);
        platforms.addLast(p);
        visitedColumns.add(column(p));
        return true;
    }
    private void cleanup() {
        for (Map.Entry<Block,BlockData> e : originals.entrySet()) e.getKey().setBlockData(e.getValue(), false);
        originals.clear(); platforms.clear(); visitedColumns.clear(); circuit.clear(); circuitNextIndex = 0;
    }
    private void end(boolean tell) {
        if (runner == null) { cleanup(); return; }
        Player p = runner;
        runner = null;
        updateBest(p);
        int achieved = score;
        cleanup();
        if (tell && p.isOnline()) msg(p, "finish", "score", "" + achieved, "best", "" + Math.max(best(p), achieved));
    }
    private void start(Player p) {
        if (runner != null) { msg(p, "busy"); return; }
        Location a = parsePosition("pos1"), b = parsePosition("pos2");
        if (a == null || b == null || a.getWorld() != b.getWorld()) {
            msg(p, "no-area");
            getLogger().warning("Arena inválida: mundo='" + getConfig().getString("area.world")
                    + "', pos1='" + getConfig().getString("area.pos1")
                    + "', pos2='" + getConfig().getString("area.pos2") + "'.");
            return;
        }
        if (!prepareCircuit(a, b)) {
            msg(p, "no-space");
            getLogger().warning("No se encontró un perímetro completamente libre en " + a.getWorld().getName()
                    + ": " + getConfig().getString("area.pos1") + " -> " + getConfig().getString("area.pos2")
                    + ". Sube game.auto-elevate-max-blocks o usa otra arena.");
            return;
        }
        score = 0; runner = p; lastProgress = System.currentTimeMillis();
        startReturnLocation = p.getLocation().clone();
        if (!seedPlatforms()) { end(false); msg(p, "no-space"); return; }
        Location first = platforms.getFirst();
        p.teleport(first.clone().add(.5, 1.05, .5));
        p.setFallDistance(0);
        msg(p, "start");
    }
    private void advance(Player p) {
        if (runner != p || platforms.size() < 2) return;
        Location next = platforms.toArray(new Location[0])[1];
        Location pos = p.getLocation();
        if (Math.abs(pos.getX() - (next.getBlockX() + .5)) > .72 ||
            Math.abs(pos.getZ() - (next.getBlockZ() + .5)) > .72 ||
            Math.abs(pos.getY() - (next.getBlockY() + 1.0)) > .18 ||
            !p.isOnGround()) return;
        if (!next.getBlock().getType().isSolid()) return;

        // Añadir la proxima ANTES de sumar puntos o retirar bloques previos.
        // El circuito esta validado al iniciar y permite avanzar indefinidamente.
        Location generated = nextCircuitLocation();
        if (generated == null || !place(generated)) {
            getLogger().warning("Circuito obstruido durante partida en " +
                    (generated == null ? "desconocido" : generated.toVector().toString()));
            msg(p, "no-route");
            // Nunca teletransportar al inicio ni eliminar la plataforma bajo sus pies.
            return;
        }
        Location previous = platforms.removeFirst();
        Block old = previous.getBlock();
        BlockData data = originals.remove(old);
        if (data != null) old.setBlockData(data, false);
        visitedColumns.remove(column(previous));
        score++;
        lastProgress = System.currentTimeMillis();
        msg(p, "score", "score", "" + score, "level", level(score));
        if (score > best(p)) updateBest(p);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void move(PlayerMoveEvent e) {
        if (runner != e.getPlayer() || e.getTo() == null) return;
        if (e.getFrom().getWorld() == e.getTo().getWorld() && e.getFrom().distanceSquared(e.getTo()) < 0.0000001) return;
        if (platforms.isEmpty()) return;
        if (e.getTo().getWorld() != platforms.getFirst().getWorld()) { end(true); return; }
        double fallLimit = Math.max(3, Math.min(20, getConfig().getDouble("game.fall-distance", 5.0)));
        if (e.getTo().getY() < platforms.getFirst().getY() - fallLimit) {
            fall(e.getPlayer()); return;
        }
        advance(e.getPlayer());
    }
    @EventHandler public void quit(PlayerQuitEvent e) { if (runner == e.getPlayer()) end(false); }
    @EventHandler public void death(PlayerDeathEvent e) {
        if (runner == e.getEntity()) {
            pendingRespawns.put(e.getEntity().getUniqueId(), lobbySpawn(e.getEntity()));
            end(true);
        }
    }
    @EventHandler public void respawn(PlayerRespawnEvent e) {
        Location destination = pendingRespawns.remove(e.getPlayer().getUniqueId());
        if (destination != null) e.setRespawnLocation(destination);
    }
    @EventHandler public void teleport(PlayerTeleportEvent e) {
        if (runner == e.getPlayer() && e.getTo() != null && e.getTo().getWorld() != e.getFrom().getWorld()) end(true);
    }
    @EventHandler(ignoreCancelled = true) public void breakBlock(org.bukkit.event.block.BlockBreakEvent e) {
        if (originals.containsKey(e.getBlock())) e.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true) public void placeBlock(org.bukkit.event.block.BlockPlaceEvent e) {
        if (runner != null && inArea(e.getBlockPlaced().getLocation())) e.setCancelled(true);
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) { if (sender instanceof Player p) msg(p, "usage"); else sender.sendMessage("/ajparkour start|salir|top|set posicion 1|2|set spawn|set inicio|reload"); return true; }
        String option = args[0].toLowerCase(Locale.ROOT);
        if (option.equals("start") || option.equals("iniciar")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Solo jugadores."); return true; }
            start(p); return true;
        }
        if (option.equals("salir") || option.equals("stop") || option.equals("leave")) {
            if (sender instanceof Player p && runner == p) { end(true); msg(p, "left"); } return true;
        }
        if (option.equals("top")) {
            sender.sendMessage(color(getConfig().getString("messages.top-header", "TOP PARKOUR")));
            List<Map.Entry<String,Integer>> top = new ArrayList<>();
            if (scores.isConfigurationSection("players")) for (String uuid : scores.getConfigurationSection("players").getKeys(false))
                top.add(Map.entry(uuid, scores.getInt("players." + uuid + ".best", 0)));
            top.sort((x,y) -> Integer.compare(y.getValue(), x.getValue()));
            for (int i = 0; i < Math.min(10, top.size()); i++) {
                Map.Entry<String,Integer> entry = top.get(i);
                String name = scores.getString("players." + entry.getKey() + ".name", "Jugador");
                sender.sendMessage(color(getConfig().getString("messages.top-line", "&e%rank%. %name% - %score%")
                        .replace("%rank%", "" + (i + 1)).replace("%name%", name).replace("%score%", "" + entry.getValue())));
            }
            return true;
        }
        if (!option.equals("set") && !option.equals("reload") && !option.equals("diagnostico")) { if (sender instanceof Player p) msg(p, "usage"); return true; }
        if (!sender.hasPermission("ajparkourbastion.admin")) { if (sender instanceof Player p) msg(p, "no-permission"); return true; }
        if (option.equals("diagnostico")) { if (sender instanceof Player p) diagnose(p); return true; }
        if (option.equals("reload")) { if (runner != null) end(true); reloadConfig(); if (sender instanceof Player p) msg(p, "reloaded"); return true; }
        if (!(sender instanceof Player p)) { sender.sendMessage("Solo jugadores pueden marcar posiciones."); return true; }
        if (runner != null) { msg(p, "busy"); return true; }
        if (args.length >= 3 && args[1].equalsIgnoreCase("posicion") && (args[2].equals("1") || args[2].equals("2"))) {
            String key = args[2].equals("1") ? "pos1" : "pos2";
            getConfig().set("area.world", p.getWorld().getName());
            String coord = p.getLocation().getBlockX() + "," + p.getLocation().getBlockY() + "," + p.getLocation().getBlockZ();
            getConfig().set("area." + key, coord); saveConfig();
            msg(p, "set-corner", "corner", args[2], "position", coord); return true;
        }
        if (args.length >= 2 && (args[1].equalsIgnoreCase("lobbyspawn") || args[1].equalsIgnoreCase("spawn"))) {
            // /ajparkour set spawn = lugar de REGRESO al caerse, como solicitó el administrador.
            Location l = p.getLocation();
            getConfig().set("area.return-spawn", l.getWorld().getName() + "," + l.getX() + "," + l.getY()
                    + "," + l.getZ() + "," + l.getYaw() + "," + l.getPitch());
            saveConfig(); msg(p, "set-lobby-spawn"); return true;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("inicio")) {
            Location l = p.getLocation();
            getConfig().set("area.world", l.getWorld().getName());
            getConfig().set("area.start", l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ());
            saveConfig(); msg(p, "set-start"); return true;
        }
        msg(p, "usage"); return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>(List.of("start", "salir", "top"));
            if (sender.hasPermission("ajparkourbastion.admin")) options.addAll(List.of("set", "reload", "diagnostico"));
            return options.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (!sender.hasPermission("ajparkourbastion.admin")) return List.of();
        if (args.length == 2 && args[0].equalsIgnoreCase("set")) return List.of("posicion", "spawn", "inicio", "lobbyspawn").stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 3 && args[0].equalsIgnoreCase("set") && args[1].equalsIgnoreCase("posicion")) return List.of("1", "2");
        return List.of();
    }
}
