package mc.bastion.parkour;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitTask;
import java.io.*;
import java.util.*;
import java.util.regex.*;

public final class AjParkourBastion extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private final Random random = new Random();
    private final Map<UUID, Session> sessions = new LinkedHashMap<>();
    private final Map<Block, BlockData> originals = new HashMap<>();
    private final Map<UUID, Location> respawns = new HashMap<>();
    private final Map<UUID, String> selectedBlocks = new HashMap<>();
    private YamlConfiguration records, preferences;
    private File recordsFile, preferencesFile;
    private BukkitTask watchdog;

    private static final class Session {
        final UUID id;
        final int lane;
        final Location returnLocation;
        final ItemStack[] inventory;
        final Collection<PotionEffect> effects;
        final List<Location> circuit = new ArrayList<>();
        final Deque<Location> platforms = new ArrayDeque<>();
        final Set<String> columns = new HashSet<>();
        int index, score;
        long lastProgress = System.currentTimeMillis();
        Session(Player p, int lane) {
            id = p.getUniqueId(); this.lane = lane;
            returnLocation = p.getLocation().clone();
            inventory = p.getInventory().getContents().clone();
            effects = new ArrayList<>(p.getActivePotionEffects());
        }
    }
    @Override public void onEnable() {
        saveDefaultConfig();
        recordsFile = new File(getDataFolder(), "records.yml");
        preferencesFile = new File(getDataFolder(), "bloques.yml");
        records = YamlConfiguration.loadConfiguration(recordsFile);
        preferences = YamlConfiguration.loadConfiguration(preferencesFile);
        if (preferences.isConfigurationSection("players")) {
            for (String id : preferences.getConfigurationSection("players").getKeys(false)) {
                try { selectedBlocks.put(UUID.fromString(id), preferences.getString("players." + id)); }
                catch (IllegalArgumentException ignored) { }
            }
        }
        Bukkit.getPluginManager().registerEvents(this, this);
        PluginCommand cmd = getCommand("ajparkour");
        if (cmd != null) { cmd.setExecutor(this); cmd.setTabCompleter(this); }
        watchdog = Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Session s : new ArrayList<>(sessions.values())) {
                Player p = Bukkit.getPlayer(s.id);
                if (p == null || !p.isOnline() || p.isDead()) { end(s, false); continue; }
                if (System.currentTimeMillis() - s.lastProgress > Math.max(15, getConfig().getInt("game.idle-seconds",120))*1000L) {
                    end(s, true); returnLobby(p, s.returnLocation);
                }
            }
        }, 20L, 20L);
    }
    @Override public void onDisable() {
        if (watchdog != null) watchdog.cancel();
        for (Session s : new ArrayList<>(sessions.values())) end(s, false);
        for (Map.Entry<Block, BlockData> entry : new ArrayList<>(originals.entrySet())) entry.getKey().setBlockData(entry.getValue(), false);
        originals.clear(); saveYaml(records, recordsFile); saveYaml(preferences, preferencesFile);
    }
    private void saveYaml(YamlConfiguration yaml, File file) {
        try { if (!getDataFolder().exists()) getDataFolder().mkdirs(); yaml.save(file); }
        catch (IOException ex) { getLogger().severe("No se pudo guardar " + file.getName() + ": " + ex.getMessage()); }
    }
    private String color(String input) {
        if (input == null) return "";
        Matcher m = Pattern.compile("&#([A-Fa-f0-9]{6})").matcher(input);
        StringBuffer buffer = new StringBuffer();
        while (m.find()) {
            StringBuilder b = new StringBuilder("§x");
            for (char c : m.group(1).toCharArray()) b.append('§').append(c);
            m.appendReplacement(buffer, Matcher.quoteReplacement(b.toString()));
        }
        m.appendTail(buffer);
        return ChatColor.translateAlternateColorCodes('&', buffer.toString());
    }
    private void msg(Player p, String key, String... params) {
        String str = getConfig().getString("messages."+key,key);
        for (int i=0;i+1<params.length;i+=2) str=str.replace("%"+params[i]+"%",params[i+1]);
        p.sendMessage(color(getConfig().getString("messages.prefix","")+str));
    }
    private Location parsePosition(String key) {
        World w = Bukkit.getWorld(getConfig().getString("area.world","Void"));
        String raw = getConfig().getString("area."+key,"");
        if (w == null || raw.isBlank()) return null;
        try {
            String[] a=raw.split(",");
            return new Location(w,Double.parseDouble(a[0].trim()),Double.parseDouble(a[1].trim()),Double.parseDouble(a[2].trim()));
        } catch (RuntimeException ex) { return null; }
    }
    private Location lobbySpawn(Location fallback) {
        String raw = getConfig().getString("area.return-spawn", "");
        if (raw.isBlank()) raw=getConfig().getString("area.lobby-spawn", "");
        try {
            String[] v=raw.split(",");
            World world=Bukkit.getWorld(v[0].trim());
            if (world != null && v.length>=6) return new Location(world,Double.parseDouble(v[1]),Double.parseDouble(v[2]),Double.parseDouble(v[3]),Float.parseFloat(v[4]),Float.parseFloat(v[5]));
        } catch (RuntimeException ignored) { }
        return fallback.clone();
    }
    private void returnLobby(Player p, Location fallback) {
        if (!p.isOnline() || p.isDead()) return;
        p.teleport(lobbySpawn(fallback)); p.setFallDistance(0);
    }
    private String column(Location l) { return l.getBlockX()+":"+l.getBlockZ(); }
    private boolean open(Block b) { return b.getType().isAir() || b.getType()==Material.LIGHT; }
    private boolean space(Location l) {
        Block b=l.getBlock();
        return !originals.containsKey(b) && open(b) && open(b.getRelative(0,1,0)) && open(b.getRelative(0,2,0));
    }
    private List<Location> perimeter(World world,int x1,int x2,int z1,int z2,int y) {
        List<Location> result=new ArrayList<>();
        for(int x=x1;x<=x2;x++) result.add(new Location(world,x,y,z1));
        for(int z=z1+1;z<=z2;z++) result.add(new Location(world,x2,y,z));
        for(int x=x2-1;x>=x1;x--) result.add(new Location(world,x,y,z2));
        for(int z=z2-1;z>z1;z--) result.add(new Location(world,x1,y,z));
        return result;
    }
    // Cuatro cuadrantes independientes; ningún circuito comparte columnas con otro.
    private boolean createCircuit(Session s, Location a, Location b) {
        int minX=Math.min(a.getBlockX(),b.getBlockX())+4, maxX=Math.max(a.getBlockX(),b.getBlockX())-4;
        int minZ=Math.min(a.getBlockZ(),b.getBlockZ())+4, maxZ=Math.max(a.getBlockZ(),b.getBlockZ())-4;
        int middleX=(minX+maxX)/2, middleZ=(minZ+maxZ)/2;
        int laneX=s.lane%2, laneZ=s.lane/2;
        int x1=laneX==0?minX:middleX+2, x2=laneX==0?middleX-2:maxX;
        int z1=laneZ==0?minZ:middleZ+2, z2=laneZ==0?middleZ-2:maxZ;
        if (x2-x1<7 || z2-z1<7) return false;
        int minY=Math.min(a.getBlockY(),b.getBlockY())+3;
        int maxY=Math.min(a.getWorld().getMaxHeight()-4,Math.max(a.getBlockY(),b.getBlockY())-4+Math.max(0,Math.min(128,getConfig().getInt("game.auto-elevate-max-blocks",50))));
        int preferred=Math.max(minY,Math.min(maxY,getConfig().getInt("game.preferred-platform-y",148)));
        for(int delta=0;delta<=Math.max(preferred-minY,maxY-preferred);delta++) {
            for(int sign:new int[]{1,-1}) {
                if(delta==0 && sign==-1) continue;
                int y=preferred+delta*sign;
                if(y<minY||y>maxY) continue;
                List<Location> test=perimeter(a.getWorld(),x1,x2,z1,z2,y);
                boolean valid=true;
                for(Location loc:test) if(!space(loc)) {valid=false;break;}
                if(valid) {s.circuit.addAll(test);s.index=random.nextInt(test.size());return true;}
            }
        }
        return false;
    }
    private int stride(Session s) {
        int easy=getConfig().getInt("game.levels.easy-until",20),medium=getConfig().getInt("game.levels.medium-until",35),hard=getConfig().getInt("game.levels.hard-until",50);
        if(s.score<easy) return 2+random.nextInt(2);
        if(s.score<medium) return 3;
        if(s.score<hard) return 3+random.nextInt(2);
        return 4;
    }
    private Location next(Session s) {
        if(s.circuit.isEmpty()) return null;
        s.index=(s.index+stride(s))%s.circuit.size();
        return s.circuit.get(s.index);
    }
    private List<Material> materials() {
        List<Material> list=new ArrayList<>();
        for(String name:getConfig().getStringList("game.materials")) {
            Material m=Material.matchMaterial(name);
            if(m!=null && m.isBlock() && m.isSolid() && !list.contains(m)) list.add(m);
        }
        if(list.isEmpty()) list.add(Material.LIME_CONCRETE);
        return list;
    }
    private Material selected(Player p) {
        Material m=Material.matchMaterial(selectedBlocks.getOrDefault(p.getUniqueId(), ""));
        return m!=null && materials().contains(m)?m:null;
    }
    private boolean place(Session s,Location l) {
        if(l==null || !space(l) || s.columns.contains(column(l))) return false;
        Block block=l.getBlock();
        originals.put(block,block.getBlockData().clone());
        Player p=Bukkit.getPlayer(s.id);
        Material forced=p==null?null:selected(p);
        List<Material> options=materials();
        block.setType(forced==null?options.get(random.nextInt(options.size())):forced,false);
        s.platforms.addLast(l);s.columns.add(column(l));
        return true;
    }
    private void unplace(Session s,Location loc) {
        Block block=loc.getBlock();
        BlockData original=originals.remove(block);
        if(original!=null) block.setBlockData(original,false);
        s.columns.remove(column(loc));
    }
    private void end(Session s,boolean announce) {
        if(sessions.remove(s.id)!=s) return;
        Player p=Bukkit.getPlayer(s.id);
        int achieved=s.score;
        if(p!=null) {
            int best=records.getInt("players."+s.id+".best",0);
            if(achieved>best) {
                records.set("players."+s.id+".best",achieved);
                records.set("players."+s.id+".name",p.getName());
                saveYaml(records,recordsFile);
                msg(p,"record","best",""+achieved);
            }
            p.closeInventory();
            p.getInventory().setContents(s.inventory);
            for(PotionEffect effect:new ArrayList<>(p.getActivePotionEffects())) p.removePotionEffect(effect.getType());
            if(getConfig().getBoolean("game.restore-effects-on-exit",true)) for(PotionEffect effect:s.effects) p.addPotionEffect(effect);
            p.updateInventory();
            if(announce && p.isOnline()) msg(p,"finish","score",""+achieved,"best",""+Math.max(best,achieved));
        }
        for(Location l:s.platforms) unplace(s,l);
        s.platforms.clear();s.columns.clear();s.circuit.clear();
    }
    private void giveSelector(Player p) {
        for(PotionEffect effect:new ArrayList<>(p.getActivePotionEffects())) p.removePotionEffect(effect.getType());
        p.getInventory().clear();
        ItemStack chest=new ItemStack(Material.CHEST);
        ItemMeta meta=chest.getItemMeta();
        meta.setDisplayName(color(getConfig().getString("selector.item-name","&#6BFF80&lPersonalizar bloques")));
        meta.setLore(List.of(color(getConfig().getString("selector.item-lore","&7Clic derecho para elegir tus bloques"))));
        chest.setItemMeta(meta);
        p.getInventory().setItem(4,chest);p.getInventory().setHeldItemSlot(4);p.updateInventory();
    }
    private void start(Player p) {
        if(sessions.containsKey(p.getUniqueId())) {msg(p,"already-playing");return;}
        int max=Math.max(1,Math.min(4,getConfig().getInt("game.max-players",4)));
        if(sessions.size()>=max) {msg(p,"busy");return;}
        Location a=parsePosition("pos1"),b=parsePosition("pos2");
        if(a==null||b==null||a.getWorld()!=b.getWorld()) {msg(p,"no-area");return;}
        Set<Integer> used=new HashSet<>();
        for(Session current:sessions.values()) used.add(current.lane);
        Session candidate=null;
        for(int lane=0;lane<4;lane++) {
            if(used.contains(lane)) continue;
            Session test=new Session(p,lane);
            if(createCircuit(test,a,b)) {candidate=test;break;}
        }
        if(candidate==null) {msg(p,"no-space");return;}
        Session s=candidate;
        sessions.put(s.id,s);
        Location first=s.circuit.get(s.index);
        if(!place(s,first)) {end(s,false);msg(p,"no-space");return;}
        int ahead=Math.max(1,Math.min(4,getConfig().getInt("game.jumps-ahead",2)));
        boolean ok=true;
        for(int n=0;n<ahead;n++) if(!place(s,next(s))) {ok=false;break;}
        if(!ok) {end(s,false);msg(p,"no-space");return;}
        if(!p.teleport(first.clone().add(.5,1.05,.5))) {end(s,false);msg(p,"no-space");return;}
        p.setFallDistance(0);giveSelector(p);msg(p,"start");
    }
    private String level(int score) {
        if(score<getConfig().getInt("game.levels.easy-until",20)) return "FÁCIL";
        if(score<getConfig().getInt("game.levels.medium-until",35)) return "MEDIO";
        if(score<getConfig().getInt("game.levels.hard-until",50)) return "DIFÍCIL";
        return "EXPERTO";
    }
    private void advance(Player p,Session s) {
        if(s.platforms.size()<2) return;
        Location next=s.platforms.stream().skip(1).findFirst().orElse(null);
        if(next==null) return;
        Location pos=p.getLocation();
        if(Math.abs(pos.getX()-next.getBlockX()-.5)>.72 || Math.abs(pos.getZ()-next.getBlockZ()-.5)>.72 || Math.abs(pos.getY()-next.getBlockY()-1.0)>.18 || !p.isOnGround()) return;
        if(!next.getBlock().getType().isSolid()) return;
        Location future=next(s);
        if(future==null || !place(s,future)) {msg(p,"no-route");return;}
        Location previous=s.platforms.removeFirst();unplace(s,previous);
        s.score++;s.lastProgress=System.currentTimeMillis();
        msg(p,"score","score",""+s.score,"level",level(s.score));
        int best=records.getInt("players."+s.id+".best",0);
        if(s.score>best) {
            records.set("players."+s.id+".best",s.score);
            records.set("players."+s.id+".name",p.getName());
            saveYaml(records,recordsFile);
        }
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void move(PlayerMoveEvent e) {
        Session s=sessions.get(e.getPlayer().getUniqueId());
        if(s==null||e.getTo()==null||s.platforms.isEmpty()) return;
        if(e.getFrom().getWorld()==e.getTo().getWorld() && e.getFrom().distanceSquared(e.getTo())<0.0000001) return;
        if(e.getTo().getWorld()!=s.platforms.getFirst().getWorld()) {end(s,true);return;}
        double limit=Math.max(3,Math.min(20,getConfig().getDouble("game.fall-distance",5.0)));
        if(e.getTo().getY()<s.platforms.getFirst().getY()-limit) {
            msg(e.getPlayer(),"fell","score",""+s.score);
            end(s,true);
            Bukkit.getScheduler().runTask(this,()->returnLobby(e.getPlayer(),s.returnLocation));
            return;
        }
        advance(e.getPlayer(),s);
    }
    @EventHandler public void quit(PlayerQuitEvent e) {
        Session s=sessions.get(e.getPlayer().getUniqueId());if(s!=null)end(s,false);
    }
    @EventHandler public void death(PlayerDeathEvent e) {
        Session s=sessions.get(e.getEntity().getUniqueId());
        if(s!=null) {respawns.put(s.id,lobbySpawn(s.returnLocation));end(s,true);}
    }
    @EventHandler public void respawn(PlayerRespawnEvent e) {
        Location l=respawns.remove(e.getPlayer().getUniqueId());if(l!=null)e.setRespawnLocation(l);
    }
    @EventHandler public void teleport(PlayerTeleportEvent e) {
        Session s=sessions.get(e.getPlayer().getUniqueId());
        if(s==null||e.getTo()==null||s.platforms.isEmpty()) return;
        if(e.getTo().getWorld()!=s.platforms.getFirst().getWorld()) end(s,true);
    }
    @EventHandler(ignoreCancelled=true) public void breakBlock(BlockBreakEvent e) {if(originals.containsKey(e.getBlock()))e.setCancelled(true);}
    @EventHandler(ignoreCancelled=true) public void placeBlock(BlockPlaceEvent e) {if(sessions.containsKey(e.getPlayer().getUniqueId()))e.setCancelled(true);}
    @EventHandler public void drop(PlayerDropItemEvent e) {if(sessions.containsKey(e.getPlayer().getUniqueId()))e.setCancelled(true);}
    @EventHandler public void swap(PlayerSwapHandItemsEvent e) {if(sessions.containsKey(e.getPlayer().getUniqueId()))e.setCancelled(true);}
    @EventHandler public void pickup(EntityPickupItemEvent e) {if(e.getEntity() instanceof Player p && sessions.containsKey(p.getUniqueId()))e.setCancelled(true);}
    @EventHandler public void interact(PlayerInteractEvent e) {
        Player p=e.getPlayer();if(!sessions.containsKey(p.getUniqueId()))return;
        e.setCancelled(true);
        if(e.getHand()!=org.bukkit.inventory.EquipmentSlot.HAND) return;
        if(e.getAction()!=Action.RIGHT_CLICK_AIR && e.getAction()!=Action.RIGHT_CLICK_BLOCK)return;
        ItemStack item=p.getInventory().getItemInMainHand();
        if(item.getType()!=Material.CHEST || p.getInventory().getHeldItemSlot()!=4)return;
        Inventory menu=Bukkit.createInventory(null,27,color(getConfig().getString("selector.menu-title","&#6BFF80&lTus bloques de parkour")));
        int slot=0;
        for(Material mat:materials()) {
            if(slot>=26)break;
            ItemStack icon=new ItemStack(mat);ItemMeta meta=icon.getItemMeta();
            meta.setDisplayName(color("&f"+mat.name().toLowerCase(Locale.ROOT).replace('_',' ')));
            if(mat==selected(p))meta.setLore(List.of(color("&a✓ Seleccionado")));
            else meta.setLore(List.of(color("&eClic para seleccionar")));
            icon.setItemMeta(meta);menu.setItem(slot++,icon);
        }
        ItemStack randomItem=new ItemStack(Material.BARRIER);ItemMeta meta=randomItem.getItemMeta();
        meta.setDisplayName(color(getConfig().getString("selector.random-name","&eBloques aleatorios")));
        randomItem.setItemMeta(meta);menu.setItem(26,randomItem);
        p.openInventory(menu);
    }
    @EventHandler public void inventoryClick(InventoryClickEvent e) {
        if(!(e.getWhoClicked() instanceof Player p)||!sessions.containsKey(p.getUniqueId()))return;
        e.setCancelled(true);
        if(e.getClickedInventory()!=e.getView().getTopInventory() || e.getView().getTopInventory().getSize()!=27)return;
        if(!e.getView().getTitle().equals(color(getConfig().getString("selector.menu-title","&#6BFF80&lTus bloques de parkour"))))return;
        int slot=e.getRawSlot();if(slot<0||slot>=27)return;
        if(slot==26) selectedBlocks.remove(p.getUniqueId());
        else {
            ItemStack icon=e.getCurrentItem();
            if(icon==null || !materials().contains(icon.getType()))return;
            selectedBlocks.put(p.getUniqueId(),icon.getType().name());
        }
        preferences.set("players."+p.getUniqueId(),selectedBlocks.get(p.getUniqueId()));
        saveYaml(preferences,preferencesFile);msg(p,"block-selected","block",selectedBlocks.getOrDefault(p.getUniqueId(),"ALEATORIOS"));
        p.closeInventory();
    }
    @EventHandler public void inventoryDrag(InventoryDragEvent e) {
        if(sessions.containsKey(e.getWhoClicked().getUniqueId()))e.setCancelled(true);
    }
    @Override public boolean onCommand(CommandSender sender,Command cmd,String label,String[] args) {
        if(args.length==0) {if(sender instanceof Player p)msg(p,"usage");else sender.sendMessage("/ajparkour start|salir|top|set|reload");return true;}
        String option=args[0].toLowerCase(Locale.ROOT);
        if(option.equals("start")||option.equals("iniciar")) {if(sender instanceof Player p)start(p);return true;}
        if(option.equals("salir")||option.equals("stop")||option.equals("leave")) {
            if(sender instanceof Player p) {
                Session s=sessions.get(p.getUniqueId());
                if(s!=null) {end(s,true);returnLobby(p,s.returnLocation);msg(p,"left");}
            }return true;
        }
        if(option.equals("top")) {
            sender.sendMessage(color(getConfig().getString("messages.top-header","TOP PARKOUR")));
            List<String> ids=new ArrayList<>();
            if(records.isConfigurationSection("players"))ids.addAll(records.getConfigurationSection("players").getKeys(false));
            ids.sort((a,b)->Integer.compare(records.getInt("players."+b+".best"),records.getInt("players."+a+".best")));
            for(int i=0;i<Math.min(10,ids.size());i++) {
                String id=ids.get(i);
                sender.sendMessage(color(getConfig().getString("messages.top-line","&e%rank%. %name% - %score%")
                    .replace("%rank%",""+(i+1)).replace("%name%",records.getString("players."+id+".name","Jugador"))
                    .replace("%score%",""+records.getInt("players."+id+".best"))));
            }return true;
        }
        if(!option.equals("set")&&!option.equals("reload")&&!option.equals("diagnostico")) {if(sender instanceof Player p)msg(p,"usage");return true;}
        if(!sender.hasPermission("ajparkourbastion.admin")) {if(sender instanceof Player p)msg(p,"no-permission");return true;}
        if(option.equals("diagnostico")) {
            if(sender instanceof Player p) {
                Location a=parsePosition("pos1"),b=parsePosition("pos2");
                if(a==null||b==null)msg(p,"no-area");
                else {int viable=0;for(int lane=0;lane<4;lane++) {Session s=new Session(p,lane);if(createCircuit(s,a,b))viable++;}
                    p.sendMessage(color("&#6BFF80&lBASTION PARKOUR &8» &fCarriles disponibles: &a"+viable+"&7/4"));}
            }return true;
        }
        if(option.equals("reload")) {
            for(Session s:new ArrayList<>(sessions.values())) {Player p=Bukkit.getPlayer(s.id);end(s,true);if(p!=null)returnLobby(p,s.returnLocation);}
            reloadConfig();if(sender instanceof Player p)msg(p,"reloaded");return true;
        }
        if(!(sender instanceof Player p)) {sender.sendMessage("Solo jugadores pueden definir posiciones.");return true;}
        if(!sessions.isEmpty()) {msg(p,"busy");return true;}
        if(args.length>=3 && args[1].equalsIgnoreCase("posicion")&&(args[2].equals("1")||args[2].equals("2"))) {
            String key=args[2].equals("1")?"pos1":"pos2";
            getConfig().set("area.world",p.getWorld().getName());
            String location=p.getLocation().getBlockX()+","+p.getLocation().getBlockY()+","+p.getLocation().getBlockZ();
            getConfig().set("area."+key,location);saveConfig();msg(p,"set-corner","corner",args[2],"position",location);return true;
        }
        if(args.length>=2&&(args[1].equalsIgnoreCase("spawn")||args[1].equalsIgnoreCase("lobbyspawn"))) {
            Location l=p.getLocation();getConfig().set("area.return-spawn",l.getWorld().getName()+","+l.getX()+","+l.getY()+","+l.getZ()+","+l.getYaw()+","+l.getPitch());
            saveConfig();msg(p,"set-lobby-spawn");return true;
        }
        if(args.length>=2&&args[1].equalsIgnoreCase("inicio")) {
            Location l=p.getLocation();getConfig().set("area.world",l.getWorld().getName());
            getConfig().set("area.start",l.getBlockX()+","+l.getBlockY()+","+l.getBlockZ());saveConfig();msg(p,"set-start");return true;
        }
        msg(p,"usage");return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args) {
        if(args.length==1) {List<String> options=new ArrayList<>(List.of("start","salir","top"));if(sender.hasPermission("ajparkourbastion.admin"))options.addAll(List.of("set","reload","diagnostico"));
            return options.stream().filter(s->s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();}
        if(!sender.hasPermission("ajparkourbastion.admin"))return List.of();
        if(args.length==2&&args[0].equalsIgnoreCase("set"))return List.of("posicion","spawn","inicio","lobbyspawn").stream().filter(s->s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if(args.length==3&&args[0].equalsIgnoreCase("set")&&args[1].equalsIgnoreCase("posicion"))return List.of("1","2");
        return List.of();
    }
}
