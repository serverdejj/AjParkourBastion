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
    private final Set<String> visitedColumns = new HashSet<>();
    private Player runner;
    private int score;
    private long lastProgress;
    private BukkitTask watchdog;
    private YamlConfiguration scores;
    private File scoresFile;

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
            && p.getBlockY() >= Math.min(a.getBlockY(), b.getBlockY()) + 3 && p.getBlockY() <= Math.max(a.getBlockY(), b.getBlockY()) - 4;
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
    // Search in all horizontal directions (including diagonals), with safe height changes.
    // The old implementation only used 4 cardinal directions and could get stuck by a wall.
    // Preferimos saltos horizontales: los cambios de altura creaban rutas complicadas
    // y la arena está construida alrededor de estructuras del Lobby.
    private List<Location> candidates(Location from, Set<String> reserved) {
        List<Location> result = new ArrayList<>();
        int target = distanceForScore();
        for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
            double distance = Math.hypot(dx, dz);
            if (distance < 1.95 || distance > 4.15) continue;
            Location next = from.clone().add(dx, 0, dz);
            if (reserved.contains(column(next)) || !emptySpace(next)) continue;
            result.add(next);
        }
        Collections.shuffle(result, random);
        result.sort(Comparator.comparingDouble(l ->
                Math.abs(Math.hypot(l.getBlockX() - from.getBlockX(), l.getBlockZ() - from.getBlockZ()) - target)));
        return result;
    }

    private Location findNext(Location from) {
        List<Location> possible = candidates(from, Set.of());
        if (possible.isEmpty()) return null;
        Location selected = null;
        double bestQuality = Double.NEGATIVE_INFINITY;
        int checked = 0;
        for (Location option : possible) {
            if (checked++ >= 80) break;
            int continuations = candidates(option, Set.of(column(option))).size();
            double dist = Math.hypot(option.getBlockX() - from.getBlockX(), option.getBlockZ() - from.getBlockZ());
            double quality = Math.min(continuations, 30) * 2.0
                    - Math.abs(dist - distanceForScore()) * 3.0 + random.nextDouble() * 2;
            if (quality > bestQuality) { bestQuality = quality; selected = option; }
        }
        return selected;
    }

    // Planeamos toda la secuencia inicial ANTES de colocar bloques. El antiguo
    // algoritmo elegía un inicio sin salida y después cancelaba la partida.
    private List<Location> planStart(Location a, Location b) {
        if (a == null || b == null) return List.of();
        int minX = Math.min(a.getBlockX(), b.getBlockX()) + 2;
        int maxX = Math.max(a.getBlockX(), b.getBlockX()) - 2;
        int minZ = Math.min(a.getBlockZ(), b.getBlockZ()) + 2;
        int maxZ = Math.max(a.getBlockZ(), b.getBlockZ()) - 2;
        int minY = Math.min(a.getBlockY(), b.getBlockY()) + 3;
        int maxY = Math.max(a.getBlockY(), b.getBlockY()) - 4;
        if (minX > maxX || minZ > maxZ || minY > maxY) return List.of();
        int ahead = Math.max(1, Math.min(4, getConfig().getInt("game.jumps-ahead", 2)));
        Location preferred = parsePosition("start");
        List<Location> starts = new ArrayList<>();
        if (preferred != null && emptySpace(preferred)) starts.add(preferred.getBlock().getLocation());
        // Zona alta primero: la mayoría de edificios de la plaza quedan debajo.
        // Un Y constante garantiza saltos sin desnivel y evita plataformas apiladas.
        int centerX = (minX + maxX) / 2, centerZ = (minZ + maxZ) / 2;
        for (int y = maxY; y >= minY; y--) {
            List<Location> layer = new ArrayList<>();
            for (int x = minX; x <= maxX; x += 2) for (int z = minZ; z <= maxZ; z += 2) {
                Location loc = new Location(a.getWorld(), x, y, z);
                if (emptySpace(loc)) layer.add(loc);
            }
            Collections.shuffle(layer, random);
            layer.sort(Comparator.comparingInt(loc -> Math.abs(loc.getBlockX()-centerX) + Math.abs(loc.getBlockZ()-centerZ)));
            starts.addAll(layer.subList(0, Math.min(layer.size(), 12)));
            if (starts.size() >= 110) break;
        }
        for (Location first : starts) {
            List<Location> route = new ArrayList<>(); route.add(first);
            if (extendPlan(route, ahead, 0)) return route;
        }
        return List.of();
    }

    // Búsqueda con retroceso: comprueba que haya 2 plataformas adelantadas
    // y no pone nada en el mundo hasta tener la ruta completa.
    private boolean extendPlan(List<Location> route, int remaining, int depth) {
        if (remaining <= 0) return true;
        if (depth > 4) return false;
        Set<String> reserved = new HashSet<>();
        for (Location loc : route) reserved.add(column(loc));
        List<Location> options = candidates(route.get(route.size()-1), reserved);
        int tries = 0;
        for (Location candidate : options) {
            if (++tries > 75) break;
            route.add(candidate);
            if (extendPlan(route, remaining-1, depth+1)) return true;
            route.remove(route.size()-1);
        }
        return false;
    }

    // Si una ruta llega a una zona sin salida, regenerar a otra altura/zona
    // conservando puntos; nunca destruir bloques originales del Lobby.
    private boolean recoverCourse(Player player) {
        cleanup();
        Location a = parsePosition("pos1"), b = parsePosition("pos2");
        List<Location> route = planStart(a, b);
        if (route.isEmpty()) {
            getLogger().warning("No se encontró una cadena de plataformas libres dentro del área. "
                    + "Comprueba que hay aire en los niveles altos y que las esquinas contienen el espacio libre.");
            return false;
        }
        for (Location loc : route) if (!place(loc)) { cleanup(); return false; }
        Location first = route.get(0);
        player.teleport(first.clone().add(.5, 1.05, .5));
        player.setFallDistance(0);
        lastProgress = System.currentTimeMillis();
        msg(player, "relocated", "score", "" + score);
        return true;
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
        originals.clear(); platforms.clear(); visitedColumns.clear();
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
        List<Location> route = planStart(a, b);
        if (route.isEmpty()) {
            msg(p, "no-space");
            getLogger().warning("Imposible planear las primeras plataformas. Área: "
                    + getConfig().getString("area.pos1") + " -> " + getConfig().getString("area.pos2")
                    + ", mundo=" + a.getWorld().getName() + ".");
            return;
        }
        score = 0; runner = p; lastProgress = System.currentTimeMillis();
        for (Location loc : route) {
            if (!place(loc)) { end(false); msg(p, "no-space"); return; }
        }
        Location first = route.get(0);
        p.teleport(first.clone().add(.5, 1.05, .5));
        p.setFallDistance(0);
        msg(p, "start");
    }
    private void advance(Player p) {
        if (runner != p || platforms.size() < 2) return;
        Location next = platforms.toArray(new Location[0])[1];
        Location pos = p.getLocation();
        // Validación completa de altura y posición, evitando puntos por estar sobre otro bloque de la misma columna.
        if (Math.abs(pos.getX() - (next.getBlockX() + .5)) > .72 ||
            Math.abs(pos.getZ() - (next.getBlockZ() + .5)) > .72 ||
            Math.abs(pos.getY() - (next.getBlockY() + 1.0)) > .18 ||
            !p.isOnGround()) return;
        if (!next.getBlock().getType().isSolid()) return;
        Location previous = platforms.removeFirst();
        Block old = previous.getBlock();
        BlockData data = originals.remove(old);
        if (data != null) old.setBlockData(data, false);
        visitedColumns.remove(column(previous));
        score++;
        lastProgress = System.currentTimeMillis();
        msg(p, "score", "score", "" + score, "level", level(score));
        if (score > best(p)) updateBest(p);
        Location generated = findNext(platforms.getLast());
        if (generated == null || !place(generated)) {
            if (!recoverCourse(p)) { msg(p, "no-route"); end(true); }
        }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void move(PlayerMoveEvent e) {
        if (runner != e.getPlayer() || e.getTo() == null) return;
        if (e.getFrom().getWorld() == e.getTo().getWorld() && e.getFrom().distanceSquared(e.getTo()) < 0.0000001) return;
        if (e.getTo().getWorld() != platforms.getFirst().getWorld() || e.getTo().getY() < platforms.getFirst().getY() - 12) {
            end(true); return;
        }
        advance(e.getPlayer());
    }
    @EventHandler public void quit(PlayerQuitEvent e) { if (runner == e.getPlayer()) end(false); }
    @EventHandler public void death(PlayerDeathEvent e) { if (runner == e.getEntity()) end(false); }
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
        if (args.length == 0) { if (sender instanceof Player p) msg(p, "usage"); else sender.sendMessage("/ajparkour start|salir|top|set posicion 1|2|set spawn|reload"); return true; }
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
        if (!option.equals("set") && !option.equals("reload")) { if (sender instanceof Player p) msg(p, "usage"); return true; }
        if (!sender.hasPermission("ajparkourbastion.admin")) { if (sender instanceof Player p) msg(p, "no-permission"); return true; }
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
        if (args.length >= 2 && args[1].equalsIgnoreCase("spawn")) {
            Location l = p.getLocation();
            getConfig().set("area.world", l.getWorld().getName());
            getConfig().set("area.start", l.getBlockX() + "," + (l.getBlockY() - 1) + "," + l.getBlockZ());
            saveConfig(); msg(p, "set-start"); return true;
        }
        msg(p, "usage"); return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>(List.of("start", "salir", "top"));
            if (sender.hasPermission("ajparkourbastion.admin")) options.addAll(List.of("set", "reload"));
            return options.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (!sender.hasPermission("ajparkourbastion.admin")) return List.of();
        if (args.length == 2 && args[0].equalsIgnoreCase("set")) return List.of("posicion", "spawn").stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 3 && args[0].equalsIgnoreCase("set") && args[1].equalsIgnoreCase("posicion")) return List.of("1", "2");
        return List.of();
    }
}
