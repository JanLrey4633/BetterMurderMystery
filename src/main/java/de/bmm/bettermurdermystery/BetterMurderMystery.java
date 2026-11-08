package de.bmm.bettermurdermystery;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.*;

public final class BetterMurderMystery extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private enum Role { MURDERER, SHERIFF, INNOCENT }

    private final Set<UUID> queue = new LinkedHashSet<>();
    private final Set<UUID> participants = new HashSet<>();
    private final Set<UUID> alive = new HashSet<>();
    private final Map<UUID, Role> roles = new HashMap<>();
    private final Map<UUID, Integer> coins = new HashMap<>();
    private final Map<UUID, PlayerBackup> backups = new HashMap<>();
    private final List<Location> arenaSpawns = new ArrayList<>();
    private final List<Location> coinSpawns = new ArrayList<>();
    private NamespacedKey coinKey;
    private NamespacedKey knifeKey;
    private boolean running = false;
    private BukkitTask countdownTask;
    private BukkitTask coinTask;
    private int countdown;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        coinKey = new NamespacedKey(this, "round_coin");
        knifeKey = new NamespacedKey(this, "murder_knife");
        loadLocations();
        Objects.requireNonNull(getCommand("bmm")).setExecutor(this);
        Objects.requireNonNull(getCommand("bmm")).setTabCompleter(this);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("BetterMurderMystery wurde aktiviert.");
    }

    @Override
    public void onDisable() {
        cancelTasks();
        for (UUID id : new HashSet<>(backups.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) restorePlayer(player);
        }
        removeRoundCoins();
    }

    private void loadLocations() {
        arenaSpawns.clear();
        coinSpawns.clear();
        arenaSpawns.addAll(getConfig().getLocationList("arena-spawns"));
        coinSpawns.addAll(getConfig().getLocationList("coin-spawn-points"));
    }

    private void saveLocations() {
        getConfig().set("arena-spawns", arenaSpawns);
        getConfig().set("coin-spawn-points", coinSpawns);
        getConfig().set("lobby", getConfig().getLocation("lobby"));
        saveConfig();
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private void msg(CommandSender sender, String text) {
        sender.sendMessage(color(getConfig().getString("messages.prefix", "&8[&6BMM&8] ") + text));
    }

    private boolean isAdmin(CommandSender sender) {
        if (sender.hasPermission("bettermurdermystery.admin")) return true;
        msg(sender, getConfig().getString("messages.no-permission", "&cKeine Berechtigung."));
        return false;
    }

    private boolean isPlayer(CommandSender sender) {
        if (sender instanceof Player) return true;
        sender.sendMessage("Dieser Befehl kann nur ein Spieler ausfuehren.");
        return false;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            msg(sender, "&eBefehle: &f/bmm join, leave, info");
            if (isAdmin(sender)) msg(sender, "&6Admin: &fstart, stop, setlobby, setspawn, setcoinspawn, clearcoinspawns, setminplayers, reload");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "gui" -> { if (sender instanceof Player p) openGui(p, "main"); else sender.sendMessage("Nur ingame."); }
            case "join" -> {
                if (!isPlayer(sender)) return true;
                Player p = (Player) sender;
                if (running) { msg(sender, "&cEine Runde laeuft bereits."); return true; }
                if (queue.contains(p.getUniqueId())) { msg(sender, "&eDu bist bereits in der Warteschlange."); return true; }
                queue.add(p.getUniqueId());
                Location lobby = getConfig().getLocation("lobby");
                if (lobby != null) p.teleport(lobby);
                msg(sender, "&aDu bist der Warteschlange beigetreten. (&f" + queue.size() + "&a/" + minPlayers() + ")");
                broadcast("&e" + p.getName() + " &7ist beigetreten. &8(" + queue.size() + "/" + minPlayers() + ")");
                maybeStartCountdown();
            }
            case "leave" -> {
                if (!isPlayer(sender)) return true;
                Player p = (Player) sender;
                if (queue.remove(p.getUniqueId())) {
                    msg(sender, "&cDu hast die Warteschlange verlassen.");
                    broadcast("&e" + p.getName() + " &7hat die Warteschlange verlassen.");
                    if (queue.size() < minPlayers()) cancelCountdown();
                } else if (participants.contains(p.getUniqueId())) {
                    finishRound("&cDie Runde wurde durch einen Admin/Spieler-Abbruch beendet.");
                } else msg(sender, "&cDu bist in keiner Warteschlange.");
            }
            case "start" -> {
                if (!isAdmin(sender)) return true;
                if (running) { msg(sender, "&cEs laeuft bereits eine Runde."); return true; }
                if (queue.size() < minPlayers()) {
                    msg(sender, getConfig().getString("messages.not-enough-players", "&cZu wenige Spieler.").replace("%min%", String.valueOf(minPlayers())));
                    return true;
                }
                startRound();
            }
            case "stop" -> {
                if (!isAdmin(sender)) return true;
                if (!running && queue.isEmpty()) { msg(sender, "&cEs laeuft keine Runde."); return true; }
                finishRound("&cDie Runde wurde von einem Admin beendet.");
                queue.clear();
            }
            case "setlobby" -> {
                if (!isAdmin(sender) || !isPlayer(sender)) return true;
                getConfig().set("lobby", ((Player) sender).getLocation());
                saveConfig();
                msg(sender, "&aLobby-Spawn gespeichert.");
            }
            case "setspawn" -> {
                if (!isAdmin(sender) || !isPlayer(sender)) return true;
                arenaSpawns.add(((Player) sender).getLocation());
                saveLocations();
                msg(sender, "&aArena-Spawn hinzugefuegt. (&f" + arenaSpawns.size() + "&a)");
            }
            case "setcoinspawn" -> {
                if (!isAdmin(sender) || !isPlayer(sender)) return true;
                coinSpawns.add(((Player) sender).getLocation());
                saveLocations();
                msg(sender, "&aCoin-Spawn hinzugefuegt. (&f" + coinSpawns.size() + "&a)");
            }
            case "clearcoinspawns" -> {
                if (!isAdmin(sender)) return true;
                coinSpawns.clear();
                saveLocations();
                msg(sender, "&aAlle Coin-Spawnpunkte entfernt.");
            }
            case "setminplayers" -> {
                if (!isAdmin(sender)) return true;
                if (args.length < 2) { msg(sender, "&cNutze: /bmm setminplayers <4+>"); return true; }
                try {
                    int amount = Integer.parseInt(args[1]);
                    if (amount < 4) { msg(sender, "&cMindestens 4 Spieler sind erforderlich."); return true; }
                    getConfig().set("min-players", amount);
                    saveConfig();
                    msg(sender, "&aMindestspielerzahl: &f" + amount);
                } catch (NumberFormatException ex) { msg(sender, "&cBitte gib eine Zahl ein."); }
            }
            case "reload" -> {
                if (!isAdmin(sender)) return true;
                reloadConfig();
                loadLocations();
                msg(sender, "&aKonfiguration neu geladen.");
            }
            case "info" -> {
                msg(sender, "&6Status: " + (running ? "&cRunde laeuft" : "&aWartelobby") +
                        " &7| Warteschlange: &f" + queue.size() + " &7| Arena-Spawns: &f" + arenaSpawns.size() +
                        " &7| Coin-Spawns: &f" + coinSpawns.size());
            }
            default -> msg(sender, "&cUnbekannter Befehl. Nutze /bmm.");
        }
        return true;
    }

    private int minPlayers() { return Math.max(4, getConfig().getInt("min-players", 4)); }

    private void maybeStartCountdown() {
        if (running || countdownTask != null || queue.size() < minPlayers()) return;
        countdown = Math.max(3, getConfig().getInt("countdown-seconds", 10));
        countdownTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (queue.size() < minPlayers()) { cancelCountdown(); return; }
            if (countdown <= 0) {
                cancelCountdown();
                startRound();
                return;
            }
            if (countdown <= 5 || countdown % 5 == 0) broadcast("&eDie Runde startet in &6" + countdown + " &eSekunden!");
            countdown--;
        }, 0L, 20L);
    }

    private void cancelCountdown() {
        if (countdownTask != null) countdownTask.cancel();
        countdownTask = null;
    }

    private void startRound() {
        cancelCountdown();
        List<Player> players = queue.stream().map(Bukkit::getPlayer).filter(Objects::nonNull).toList();
        queue.clear();
        if (players.size() < minPlayers()) { broadcast("&cNicht genuegend Spieler online."); return; }
        if (arenaSpawns.isEmpty()) { broadcast("&cKeine Arena-Spawns gesetzt! Admin: /bmm setspawn"); queue.addAll(players.stream().map(Player::getUniqueId).toList()); return; }
        if (coinSpawns.isEmpty()) broadcast("&eHinweis: Es gibt noch keine Coin-Spawnpunkte. /bmm setcoinspawn");
        running = true;
        participants.clear(); alive.clear(); roles.clear(); coins.clear();
        participants.addAll(players.stream().map(Player::getUniqueId).toList());
        alive.addAll(participants);
        for (Player p : players) {
            backups.put(p.getUniqueId(), new PlayerBackup(p));
            clearInventory(p);
            p.setGameMode(GameMode.SURVIVAL);
            p.setHealth(20.0);
            p.setFoodLevel(20);
            p.setFireTicks(0);
            p.setFallDistance(0);
            p.setExp(0);
            p.setLevel(0);
            p.setVelocity(new Vector(0, 0, 0));
            p.teleport(randomArenaSpawn());
            coins.put(p.getUniqueId(), 0);
        }
        List<UUID> shuffled = new ArrayList<>(participants);
        Collections.shuffle(shuffled);
        roles.put(shuffled.get(0), Role.MURDERER);
        roles.put(shuffled.get(1), Role.SHERIFF);
        for (int i = 2; i < shuffled.size(); i++) roles.put(shuffled.get(i), Role.INNOCENT);
        for (Player p : players) {
            Role role = roles.get(p.getUniqueId());
            if (role == Role.MURDERER) p.getInventory().addItem(createKnife());
            if (role == Role.SHERIFF) {
                p.getInventory().addItem(createBow());
                p.getInventory().addItem(new ItemStack(Material.ARROW, 1));
            }
            p.sendTitle(color("&6MURDER MYSTERY"), color(roleName(role)), 10, 60, 10);
            p.sendMessage(color("&7Deine Rolle: " + roleColor(role) + roleName(role)));
        }
        broadcast("&aDie Runde hat begonnen! &7Spieler: &f" + players.size());
        startCoinTask();
    }

    private Location randomArenaSpawn() {
        return arenaSpawns.get(new Random().nextInt(arenaSpawns.size())).clone();
    }

    private void clearInventory(Player p) {
        p.getInventory().clear();
        p.getInventory().setArmorContents(null);
        p.getInventory().setExtraContents(null);
        p.getEnderChest().clear();
    }

    private ItemStack createKnife() {
        ItemStack item = new ItemStack(Material.IRON_SWORD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Murder Knife", NamedTextColor.RED));
        meta.lore(List.of(Component.text("Nur fuer den Murderer", NamedTextColor.GRAY)));
        meta.getPersistentDataContainer().set(knifeKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createBow() {
        ItemStack item = new ItemStack(Material.BOW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Sheriff-Bogen", NamedTextColor.AQUA));
        item.setItemMeta(meta);
        return item;
    }

    private String roleName(Role role) {
        return switch (role) { case MURDERER -> "Murderer"; case SHERIFF -> "Sheriff"; case INNOCENT -> "Innocent"; };
    }

    private String roleColor(Role role) {
        return switch (role) { case MURDERER -> "&c"; case SHERIFF -> "&b"; case INNOCENT -> "&a"; };
    }

    private void startCoinTask() {
        long interval = Math.max(2, getConfig().getLong("coin-spawn-interval-seconds", 8)) * 20L;
        coinTask = Bukkit.getScheduler().runTaskTimer(this, this::spawnCoin, interval, interval);
    }

    private void spawnCoin() {
        if (!running || coinSpawns.isEmpty()) return;
        long onGround = Bukkit.getWorlds().stream().flatMap(w -> w.getEntitiesByClass(Item.class).stream())
                .filter(this::isRoundCoin).count();
        if (onGround >= getConfig().getInt("max-coins-on-ground", 12)) return;
        Location loc = coinSpawns.get(new Random().nextInt(coinSpawns.size())).clone();
        World world = loc.getWorld();
        if (world == null) return;
        ItemStack stack = new ItemStack(Material.GOLD_NUGGET);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text("Mystery-Coin", NamedTextColor.GOLD));
        meta.getPersistentDataContainer().set(coinKey, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        Item dropped = world.dropItemNaturally(loc, stack);
        dropped.setPickupDelay(10);
        dropped.setGlowing(true);
    }

    private boolean isRoundCoin(Item item) {
        return item.getItemStack().getItemMeta().getPersistentDataContainer().has(coinKey, PersistentDataType.BYTE);
    }

    @EventHandler
    public void onCoinPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player) || !isRoundCoin(event.getItem())) return;
        if (!running || !participants.contains(player.getUniqueId()) || !alive.contains(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        event.setCancelled(true);
        event.getItem().remove();
        int total = coins.merge(player.getUniqueId(), 1, Integer::sum);
        player.sendActionBar(Component.text("Coins: " + total, NamedTextColor.GOLD));
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.5f);
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (!running || !participants.contains(victim.getUniqueId())) return;
        Player attacker = null;
        boolean sheriffShot = false;
        if (event.getDamager() instanceof Player p) attacker = p;
        else if (event.getDamager() instanceof Projectile projectile) {
            ProjectileSource source = projectile.getShooter();
            if (source instanceof Player p) { attacker = p; sheriffShot = projectile instanceof Arrow; }
        }
        if (attacker == null || !participants.contains(attacker.getUniqueId())) { event.setCancelled(true); return; }
        event.setCancelled(true);
        if (!alive.contains(attacker.getUniqueId()) || !alive.contains(victim.getUniqueId())) return;
        Role attackerRole = roles.get(attacker.getUniqueId());
        if (sheriffShot && attackerRole == Role.SHERIFF && roles.get(victim.getUniqueId()) == Role.MURDERER) {
            eliminate(victim, "&aDer Sheriff hat den Murderer erwischt!");
            return;
        }
        if (attackerRole == Role.MURDERER && attacker.getInventory().getItemInMainHand().getType() == Material.IRON_SWORD
                && attacker.getInventory().getItemInMainHand().getItemMeta() != null
                && attacker.getInventory().getItemInMainHand().getItemMeta().getPersistentDataContainer().has(knifeKey, PersistentDataType.BYTE)) {
            eliminate(victim, "&cEin Spieler wurde vom Murderer erwischt!");
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player p = event.getEntity();
        if (!running || !participants.contains(p.getUniqueId())) return;
        event.getDrops().clear();
        event.setDroppedExp(0);
        event.setKeepInventory(true);
        Bukkit.getScheduler().runTask(this, () -> {
            if (p.isOnline() && running) p.setGameMode(GameMode.SPECTATOR);
        });
        eliminate(p, "&7" + p.getName() + " ist ausgeschieden.");
    }

    private void eliminate(Player player, String message) {
        if (!alive.remove(player.getUniqueId())) return;
        broadcast(message);
        if (player.isOnline() && player.getHealth() > 0.0) player.setHealth(0.0);
        checkWin();
    }

    private void checkWin() {
        if (!running) return;
        long murderers = alive.stream().filter(id -> roles.get(id) == Role.MURDERER).count();
        long others = alive.stream().filter(id -> roles.get(id) != Role.MURDERER).count();
        if (murderers == 0) finishRound("&aDie Innocents gewinnen! Der Murderer wurde besiegt.");
        else if (others == 0) finishRound("&cDer Murderer gewinnt!");
    }

    private void finishRound(String message) {
        if (!running && backups.isEmpty()) return;
        running = false;
        cancelTasks();
        removeRoundCoins();
        broadcast(message);
        for (UUID id : new HashSet<>(backups.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && !p.isDead()) restorePlayer(p);
        }
        participants.clear(); alive.clear(); roles.clear(); coins.clear();
    }

    private void restorePlayer(Player p) {
        PlayerBackup backup = backups.remove(p.getUniqueId());
        if (backup == null) return;
        p.setGameMode(backup.gameMode);
        p.getInventory().setContents(backup.contents);
        p.getInventory().setArmorContents(backup.armor);
        p.getInventory().setExtraContents(backup.extra);
        p.getEnderChest().setContents(backup.enderChest);
        p.setLevel(backup.level);
        p.setExp(backup.exp);
        p.setTotalExperience(backup.totalExperience);
        p.setHealth(Math.min(backup.health, p.getMaxHealth()));
        p.setFoodLevel(backup.food);
        p.setSaturation(backup.saturation);
        p.setFireTicks(backup.fireTicks);
        p.teleport(backup.location);
        p.updateInventory();
    }

    private void removeRoundCoins() {
        for (World world : Bukkit.getWorlds()) {
            for (Item item : world.getEntitiesByClass(Item.class)) if (isRoundCoin(item)) item.remove();
        }
    }

    private void cancelTasks() {
        cancelCountdown();
        if (coinTask != null) coinTask.cancel();
        coinTask = null;
    }

    private void broadcast(String message) {
        String formatted = color(getConfig().getString("messages.prefix", "&8[&6BMM&8] ") + message);
        for (UUID id : participants) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.sendMessage(formatted);
        }
        for (UUID id : queue) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.sendMessage(formatted);
        }
        getLogger().info(ChatColor.stripColor(formatted));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (queue.remove(id)) {
            if (queue.size() < minPlayers()) cancelCountdown();
        }
        if (participants.contains(id)) {
            if (alive.remove(id)) broadcast("&7" + event.getPlayer().getName() + " hat die Runde verlassen.");
            restorePlayer(event.getPlayer());
            checkWin();
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (running && participants.contains(event.getPlayer().getUniqueId()) && !alive.contains(event.getPlayer().getUniqueId())) {
            event.getPlayer().setGameMode(GameMode.SPECTATOR);
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (backups.containsKey(id)) {
            Bukkit.getScheduler().runTaskLater(this, () -> restorePlayer(event.getPlayer()), 1L);
        } else if (running && participants.contains(id) && !alive.contains(id)) {
            Bukkit.getScheduler().runTaskLater(this, () -> event.getPlayer().setGameMode(GameMode.SPECTATOR), 1L);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = args.length == 1
                ? List.of("join", "leave", "info", "gui", "start", "stop", "setlobby", "setspawn", "setcoinspawn", "clearcoinspawns", "setminplayers", "reload")
                : Collections.emptyList();
        if (args.length == 1) return options.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        return Collections.emptyList();
    }


    private void openGui(Player p, String page) {
        int size = page.equals("admin") ? 45 : page.equals("settings") ? 27 : 27;
        Inventory inv = Bukkit.createInventory(new GuiHolder(page), size, Component.text("BMM » " + page));
        if (page.equals("main")) {
            inv.setItem(10, gui(Material.LIME_DYE, "&aBeitreten"));
            inv.setItem(12, gui(Material.RED_DYE, "&cVerlassen"));
            inv.setItem(14, gui(Material.BOOK, "&eSpielinfo", "&7Warteschlange: " + queue.size(), "&7Min.: " + minPlayers()));
            if (p.hasPermission("bettermurdermystery.admin")) inv.setItem(16, gui(Material.COMPARATOR, "&6Admin-Menü"));
        } else if (page.equals("admin")) {
            inv.setItem(10, gui(Material.LIME_CONCRETE, "&aRunde starten"));
            inv.setItem(11, gui(Material.RED_CONCRETE, "&cRunde stoppen"));
            inv.setItem(13, gui(Material.BEACON, "&bLobby-Spawn setzen"));
            inv.setItem(14, gui(Material.IRON_SWORD, "&eArena-Spawn hinzufügen", "&7Gesetzt: " + arenaSpawns.size()));
            inv.setItem(15, gui(Material.GOLD_NUGGET, "&6Coin-Spawn hinzufügen", "&7Gesetzt: " + coinSpawns.size()));
            inv.setItem(16, gui(Material.BARRIER, "&cCoin-Spawns löschen"));
            inv.setItem(28, gui(Material.REPEATER, "&dEinstellungen"));
            inv.setItem(30, gui(Material.WRITABLE_BOOK, "&eConfig neu laden"));
            inv.setItem(40, gui(Material.ARROW, "&7Zurück"));
        } else {
            inv.setItem(10, gui(Material.PLAYER_HEAD, "&eMindestspieler: " + minPlayers(), "&7Links +1 | Rechts -1"));
            inv.setItem(12, gui(Material.CLOCK, "&eCountdown: " + getConfig().getInt("countdown-seconds", 10), "&7Links +5 | Rechts -5"));
            inv.setItem(14, gui(Material.GOLD_INGOT, "&6Coin-Intervall: " + getConfig().getInt("coin-spawn-interval-seconds", 8), "&7Links +2 | Rechts -2"));
            inv.setItem(16, gui(Material.CHEST, "&bMax. Coins: " + getConfig().getInt("max-coins-on-ground", 12), "&7Links +2 | Rechts -2"));
            inv.setItem(22, gui(Material.ARROW, "&7Zurück"));
        }
        p.openInventory(inv);
    }

    private ItemStack gui(Material type, String title, String... lore) {
        ItemStack item = new ItemStack(type);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(color(title));
        meta.setLore(Arrays.stream(lore).map(this::color).toList());
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onGuiClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || !(e.getView().getTopInventory().getHolder() instanceof GuiHolder holder)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        int slot = e.getRawSlot();
        if (holder.page.equals("main")) {
            if (slot == 10) p.performCommand("bmm join");
            if (slot == 12) p.performCommand("bmm leave");
            if (slot == 14) p.performCommand("bmm info");
            if (slot == 16 && p.hasPermission("bettermurdermystery.admin")) openGui(p, "admin");
        } else if (holder.page.equals("admin")) {
            if (!p.hasPermission("bettermurdermystery.admin")) { p.closeInventory(); return; }
            switch (slot) {
                case 10 -> p.performCommand("bmm start");
                case 11 -> p.performCommand("bmm stop");
                case 13 -> p.performCommand("bmm setlobby");
                case 14 -> p.performCommand("bmm setspawn");
                case 15 -> p.performCommand("bmm setcoinspawn");
                case 16 -> p.performCommand("bmm clearcoinspawns");
                case 28 -> { openGui(p, "settings"); return; }
                case 30 -> p.performCommand("bmm reload");
                case 40 -> { openGui(p, "main"); return; }
                default -> { return; }
            }
            Bukkit.getScheduler().runTask(this, () -> openGui(p, "admin"));
        } else if (holder.page.equals("settings")) {
            if (!p.hasPermission("bettermurdermystery.admin")) { p.closeInventory(); return; }
            if (slot == 22) { openGui(p, "admin"); return; }
            String path = switch (slot) { case 10 -> "min-players"; case 12 -> "countdown-seconds"; case 14 -> "coin-spawn-interval-seconds"; case 16 -> "max-coins-on-ground"; default -> null; };
            if (path == null) return;
            int step = path.equals("min-players") ? 1 : path.equals("max-coins-on-ground") ? 2 : 5;
            int min = path.equals("min-players") ? 4 : 1;
            getConfig().set(path, Math.max(min, getConfig().getInt(path) + (e.isRightClick() ? -step : step)));
            saveConfig();
            openGui(p, "settings");
        }
    }

    private static final class GuiHolder implements InventoryHolder {
        private final String page;
        private GuiHolder(String page) { this.page = page; }
        @Override public Inventory getInventory() { return null; }
    }

    private static final class PlayerBackup {
        private final ItemStack[] contents, armor, extra, enderChest;
        private final GameMode gameMode;
        private final Location location;
        private final int level, totalExperience, food, fireTicks;
        private final float exp, saturation;
        private final double health;

        private PlayerBackup(Player p) {
            contents = p.getInventory().getContents().clone();
            armor = p.getInventory().getArmorContents().clone();
            extra = p.getInventory().getExtraContents().clone();
            enderChest = p.getEnderChest().getContents().clone();
            gameMode = p.getGameMode();
            location = p.getLocation().clone();
            level = p.getLevel();
            totalExperience = p.getTotalExperience();
            exp = p.getExp();
            food = p.getFoodLevel();
            saturation = p.getSaturation();
            health = p.getHealth();
            fireTicks = p.getFireTicks();
        }
    }
}