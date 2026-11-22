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
    private Location findNext(Location from) {
        int attempts = Math.max(30, getConfig().getInt("game.max-placement-attempts", 220));
        List<Location> options = new ArrayList<>();
        int d = distanceForScore();
        for (int n = 0; n < attempts; n++) {
            int dist = n < attempts / 2 ? d : (n < attempts * 3 / 4 ? Math.min(d, 3) : 2);
            int direction = random.nextInt(4);
            int dx = direction == 0 ? dist : direction == 1 ? -dist : 0;
            int dz = direction == 2 ? dist : direction == 3 ? -dist : 0;
            int dy = dist >= 4 ? (random.nextBoolean() ? 0 : -1) : random.nextInt(3) - 1;
            Location next = from.clone().add(dx,dy,dz);
            if (emptySpace(next)) options.add(next);
        }
        // Búsqueda exhaustiva de emergencia: nunca reciclar la misma columna ni poner bloques apilados.
        if (options.isEmpty()) {
            for (int dist = 2; dist <= 3; dist++) for (int dir = 0; dir < 4; dir++) for (int y = -1; y <= 1; y++) {
                Location next = from.clone().add(dir == 0 ? dist : dir == 1 ? -dist : 0, y,
                        dir == 2 ? dist : dir == 3 ? -dist : 0);
                if (emptySpace(next)) options.add(next);
            }
        }
        if (options.isEmpty()) return null;
        // Favorecer posiciones con más espacio a futuro, no devolver una mala ubicación como válida.
        Location a = parsePosition("pos1"), b = parsePosition("pos2");
        double cx = (a.getBlockX() + b.getBlockX()) / 2.0;
        double cz = (a.getBlockZ() + b.getBlockZ()) / 2.0;
        options.sort(Comparator.comparingDouble((Location l) -> Math.hypot(l.getX() - cx, l.getZ() - cz) + random.nextDouble() * 5));
        return options.get(0);
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
    private Location findStart(Location a, Location b) {
        World world = a.getWorld();
        int minX = Math.min(a.getBlockX(), b.getBlockX()) + 2;
        int maxX = Math.max(a.getBlockX(), b.getBlockX()) - 2;
        int minZ = Math.min(a.getBlockZ(), b.getBlockZ()) + 2;
        int maxZ = Math.max(a.getBlockZ(), b.getBlockZ()) - 2;
        int minY = Math.min(a.getBlockY(), b.getBlockY()) + 3;
        int maxY = Math.max(a.getBlockY(), b.getBlockY()) - 4;
        int cx = (minX + maxX) / 2, cz = (minZ + maxZ) / 2;
        int preferredY = Math.min(a.getBlockY(), b.getBlockY()) + 6;
        // Search outward from the center, preferring the requested starting height.
        // Never overwrite existing structures or select a location with insufficient headroom.
        Location best = null;
        double bestCost = Double.POSITIVE_INFINITY;
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
            for (int y = minY; y <= maxY; y++) {
                Location candidate = new Location(world, x, y, z);
                if (!emptySpace(candidate)) continue;
                double cost = Math.abs(x - cx) + Math.abs(z - cz) + Math.abs(y - preferredY) * 1.7;
                if (cost < bestCost) { bestCost = cost; best = candidate; }
            }
        }
        return best;
    }

    private void start(Player p) {
        if (runner != null) { msg(p, "busy"); return; }
        Location a = parsePosition("pos1"), b = parsePosition("pos2");
        if (a == null || b == null || a.getWorld() != b.getWorld()) {
            msg(p, "no-area");
            getLogger().warning("Arena no disponible: world='" + getConfig().getString("area.world")
                    + "', pos1='" + getConfig().getString("area.pos1")
                    + "', pos2='" + getConfig().getString("area.pos2")
                    + "'. Mundos cargados: " + Bukkit.getWorlds().stream().map(World::getName).toList());
            return;
        }
        Location start = parsePosition("start");
        if (start != null) start = start.getBlock().getLocation();
        if (start == null || !emptySpace(start)) {
            start = findStart(a, b);
            if (start != null) getLogger().info("Inicio automático libre elegido: "
                    + start.getBlockX() + "," + start.getBlockY() + "," + start.getBlockZ());
        }
        if (start == null) {
            msg(p, "no-space");
            getLogger().warning("No hay ninguna posición libre dentro de la arena para colocar el primer bloque. "
                    + "Marca un inicio en una zona con al menos 3 bloques verticales de aire con /ajparkour set spawn.");
            return;
        }
        score = 0; runner = p; lastProgress = System.currentTimeMillis();
        if (!place(start)) { end(false); msg(p, "no-area"); return; }
        int ahead = Math.max(1, Math.min(4, getConfig().getInt("game.jumps-ahead", 2)));
        for (int i = 0; i < ahead; i++) {
            Location next = findNext(platforms.getLast());
            if (next == null || !place(next)) { end(false); msg(p, "no-route"); return; }
        }
        p.teleport(start.clone().add(.5, 1.05, .5));
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
        if (generated == null || !place(generated)) { msg(p, "no-route"); end(true); }
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
