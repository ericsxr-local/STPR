package com.redmoore.stpr;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.command.CommandExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.Sound;

import java.io.File;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class STPRPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private final Map<String, STPRMap> maps = new HashMap<>();
    private final Map<UUID, Match> playerMatches = new HashMap<>();
    private final Map<UUID, Vote> votes = new HashMap<>();
    private final List<Match> matches = new ArrayList<>();
    private BukkitTask ticker;
    private long tickCounter = 0L;
    private final Map<UUID, Match> pendingRespawn = new HashMap<>();
    private final Set<UUID> pendingElimination = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        File dir = new File(getDataFolder(), "maps");
        if (!dir.exists() && !dir.mkdirs()) {
            getLogger().warning("无法创建 maps 文件夹。");
        }
        loadMaps();

        getServer().getPluginManager().registerEvents(this, this);
        PluginCommandAccessor.register(this);

        ticker = Bukkit.getScheduler().runTaskTimer(this, () -> {
            tickCounter++;
            tick();
        }, 1L, 1L);

        getLogger().info("STPR 已启动，地图数量：" + maps.size());
    }

    @Override
    public void onDisable() {
        if (ticker != null) ticker.cancel();
        matches.clear();
        playerMatches.clear();
        votes.clear();
        pendingRespawn.clear();
        pendingElimination.clear();
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Match match : new ArrayList<>(matches)) {
            if (match.state == State.WAITING) {
                tickWaiting(match);
            } else {
                tickActive(match, now);
            }
        }
    }

    private void tickWaiting(Match match) {
        if (match.players.isEmpty()) {
            matches.remove(match);
            return;
        }

        if (match.map.startWhenFull && match.players.size() >= match.map.maxPlayers) {
            startMatch(match);
            return;
        }

        if (match.players.size() < match.map.minPlayers) {
            match.prompted = false;
            return;
        }

        match.prompted = true;

        // 达到最低人数后每 1 秒重复显示一次，防止玩家漏看。
        if (tickCounter % 20L == 0L) {
            broadcastActionBar(match, "&a人数已到齐 " + match.players.size() + "/" + match.map.minPlayers
                    + "  &7是否现在传送？输入 &e/stpr start &7，等待 &c/stpr wait");
        }

        int start = 0;
        int wait = 0;
        for (UUID id : match.players) {
            Vote v = votes.getOrDefault(id, Vote.NONE);
            if (v == Vote.START) start++;
            if (v == Vote.WAIT) wait++;
        }

        if (start > wait && start + wait > 0) {
            broadcast(match, "vote-start-more");
            startMatch(match);
        } else if (wait > start && start + wait > 0) {
            if (tickCounter % 40 == 0) broadcast(match, "vote-wait-more");
        } else if (start == wait && start > 0 && tickCounter % 40 == 0) {
            broadcast(match, "vote-tie");
        }
    }

    private void tickActive(Match match, long now) {
        long remaining = match.endAt - now;

        if (remaining <= 0) {
            endByTimeout(match);
            return;
        }

        // 对局剩余时间
        if (tickCounter % 20 == 0) {
            String time = formatTimeMillis(remaining);
            for (UUID id : new ArrayList<>(match.players)) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    p.sendActionBar(color("&7本局剩余时间：&e" + time));
                }
            }
        }

        // 处理撤离倒计时
        for (UUID id : new ArrayList<>(match.players)) {
            ActiveInfo info = match.active.get(id);
            if (info == null) continue;

            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;

            if (info.extractStartTick >= 0) {
                if (!isAtExtraction(match.map, p.getLocation().getBlock())) {
                    info.extractStartTick = -1;
                    p.sendActionBar(color("&c撤离已取消"));
                    continue;
                }

                long passed = tickCounter - info.extractStartTick;
                long left = info.extractTicks - passed;
                if (left <= 0) {
                    completeExtraction(match, p);
                } else if (tickCounter % 5 == 0) {
                    int seconds = Math.max(1, (int)((left + 19) / 20));
                    p.sendActionBar(color("&b撤离倒计时：&e" + seconds + "秒"));
                }
            }
        }
    }

    private void startMatch(Match match) {
        if (match.map.spawnPoints.isEmpty()) {
            broadcast(match, "start-no-spawn");
            return;
        }

        match.state = State.ACTIVE;
        match.startedAt = System.currentTimeMillis();
        match.endAt = match.startedAt + match.map.matchSeconds * 1000L;
        match.active.clear();
        match.prompted = false;

        List<Location> shuffled = new ArrayList<>(match.map.spawnPoints);
        Collections.shuffle(shuffled);

        List<UUID> ids = new ArrayList<>(match.players);
        for (int i = 0; i < ids.size(); i++) {
            UUID id = ids.get(i);
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                continue;
            }

            Location spawn = shuffled.get(i % shuffled.size()).clone();
            p.teleport(spawn);
            ActiveInfo info = new ActiveInfo(match.map.extractTicks);
            match.active.put(id, info);
            votes.remove(id);

            p.sendTitle(color("&a对局开始"), color("&f" + match.map.name), 10, 40, 10);
            p.playSound(p.getLocation(), Sound.BLOCK_PORTAL_TRAVEL, 1.0f, 0.8f);
        }

        broadcast(match, "game-started");
    }

    private void completeExtraction(Match match, Player player) {
        UUID id = player.getUniqueId();
        if (!match.players.contains(id)) return;

        match.players.remove(id);
        match.active.remove(id);
        playerMatches.remove(id);

        player.sendTitle(
                color(getConfig().getString("messages.extract-success-title", "&a撤离成功")),
                color(getConfig().getString("messages.extract-success-subtitle", "&f你已安全离开本局。")),
                10, 50, 10
        );
        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_LAND, 1.0f, 1.1f);
        msg(player, "extract-success-chat", "%player%", player.getName());

        if (match.map.postExtraction != null) {
            player.teleport(match.map.postExtraction);
        } else {
            msg(player, "postpoint-missing");
        }

        if (match.players.isEmpty()) {
            matches.remove(match);
        }
    }

    private void endByTimeout(Match match) {
        for (UUID id : new ArrayList<>(match.players)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.getInventory().clear();
                p.getEnderChest().clear();
                p.sendTitle(
                        color("&c你已被淘汰"),
                        color("&7未能在规定时间内撤离。"),
                        0, 50, 10
                );
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_DEATH, 1.0f, 1.0f);
                msg(p, "timeout-chat", "%player%", p.getName());
                p.setHealth(0.0);
            }
            playerMatches.remove(id);
            votes.remove(id);
        }
        matches.remove(match);
    }

    private boolean join(Player player, STPRMap map) {
        UUID id = player.getUniqueId();

        if (playerMatches.containsKey(id)) {
            msg(player, "already-in-match");
            return false;
        }

        Match active = findActiveMatch(map);
        if (active != null) {
            if (!map.allowMidJoin) {
                long left = Math.max(0, active.endAt - System.currentTimeMillis());
                msg(player, "midjoin-disabled", "%time%", formatTimeMillis(left));
                return false;
            }
            if (active.players.size() >= map.maxPlayers) {
                msg(player, "full");
                return false;
            }

            Location spawn = randomSpawn(map);
            if (spawn == null) {
                msg(player, "start-no-spawn");
                return false;
            }

            active.players.add(id);
            active.active.put(id, new ActiveInfo(map.extractTicks));
            playerMatches.put(id, active);
            player.teleport(spawn);
            player.sendTitle(color("&a加入对局"), color("&f" + map.name), 10, 40, 10);
            msg(player, "midjoin-enabled", "%map%", map.name);
            return true;
        }

        Match waiting = findWaitingMatch(map);
        if (waiting == null) {
            waiting = new Match(map);
            matches.add(waiting);
        }

        if (waiting.players.size() >= map.maxPlayers) {
            msg(player, "full");
            return false;
        }

        waiting.players.add(id);
        playerMatches.put(id, waiting);
        msg(player, "join-success", "%map%", map.name);

        if (waiting.players.size() >= map.minPlayers) {
            waiting.prompted = true;
            broadcastActionBar(waiting, "&a人数已到齐 " + waiting.players.size() + "/" + waiting.map.minPlayers
                    + "  &7是否现在传送？输入 &e/stpr start &7，等待 &c/stpr wait");
        }
        return true;
    }

    private void leave(Player player) {
        UUID id = player.getUniqueId();
        Match match = playerMatches.remove(id);
        votes.remove(id);
        if (match == null) {
            msg(player, "not-in-match");
            return;
        }

        match.players.remove(id);
        match.active.remove(id);
        msg(player, "left");

        if (match.players.isEmpty()) {
            matches.remove(match);
        }
    }

    private Match findWaitingMatch(STPRMap map) {
        for (Match match : matches) {
            if (match.map.name.equalsIgnoreCase(map.name) && match.state == State.WAITING
                    && match.players.size() < map.maxPlayers) {
                return match;
            }
        }
        return null;
    }

    private Match findActiveMatch(STPRMap map) {
        for (Match match : matches) {
            if (match.map.name.equalsIgnoreCase(map.name) && match.state == State.ACTIVE) {
                return match;
            }
        }
        return null;
    }

    private Location randomSpawn(STPRMap map) {
        if (map.spawnPoints.isEmpty()) return null;
        return map.spawnPoints.get(ThreadLocalRandom.current().nextInt(map.spawnPoints.size())).clone();
    }

    private boolean isAtExtraction(STPRMap map, Block block) {
        if (block.getType() != Material.LIME_CARPET) return false;
        if (getConfig().getBoolean("settings.require-bedrock-under-carpet", true)
                && block.getRelative(BlockFace.DOWN).getType() != Material.BEDROCK) {
            return false;
        }

        for (Location loc : map.extractionPoints) {
            if (loc.getWorld() == null || block.getWorld() == null) continue;
            if (!loc.getWorld().equals(block.getWorld())) continue;
            if (loc.getBlockX() == block.getX()
                    && loc.getBlockY() == block.getY()
                    && loc.getBlockZ() == block.getZ()) {
                return true;
            }
        }
        return false;
    }


    /**
     * 对局内死亡：
     * 1. 正常死亡并产生掉落物；
     * 2. 不保留背包；
     * 3. 玩家立即从本局名单移除，视为淘汰；
     * 4. 等 PlayerRespawnEvent 后再传送到本地图专属撤离后传送点。
     */
    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
    public void onDeath(org.bukkit.event.entity.PlayerDeathEvent event) {
        Player player = event.getEntity();
        UUID id = player.getUniqueId();

        Match match = playerMatches.get(id);
        if (match == null || match.state != State.ACTIVE) return;

        // 明确保持正常死亡，不清空掉落物。
        event.setKeepInventory(false);

        // 成为淘汰玩家：从当前对局名单移除。
        match.players.remove(id);
        match.active.remove(id);
        playerMatches.remove(id);
        votes.remove(id);

        // 保存本局引用，等玩家复活后传送。
        pendingRespawn.put(id, match);
    }

    @EventHandler
    public void onRespawn(org.bukkit.event.player.PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();

        Match match = pendingRespawn.remove(id);
        if (match == null) return;

        Location destination = match.map.postExtraction;

        if (destination != null && destination.getWorld() != null) {
            event.setRespawnLocation(destination);
        }

        Bukkit.getScheduler().runTask(this, () -> {
            if (!player.isOnline()) return;

            if (destination != null && destination.getWorld() != null) {
                player.teleport(destination);
            }

            player.sendTitle(
                    color("&c你已被淘汰"),
                    color("&7本局未成功撤离"),
                    0, 50, 10
            );
            player.playSound(
                    player.getLocation(),
                    Sound.ENTITY_PLAYER_DEATH,
                    1.0f,
                    1.0f
            );

            // 若该玩家是最后一个未撤离者，本局现在结束。
            if (match.players.isEmpty()) {
                matches.remove(match);
            }
        });
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Player player = e.getPlayer();
        Match match = playerMatches.get(player.getUniqueId());
        if (match == null || match.state != State.ACTIVE) return;
        if (e.getTo() == null) return;

        ActiveInfo info = match.active.get(player.getUniqueId());
        if (info == null) return;

        Block block = e.getTo().getBlock();
        boolean atExit = isAtExtraction(match.map, block);

        if (atExit) {
            if (info.extractStartTick < 0) {
                info.extractStartTick = tickCounter;
                player.sendActionBar(color("&b开始撤离：&e" + Math.max(1, (info.extractTicks + 19) / 20) + "秒"));
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 1.0f);
            }
        } else if (info.extractStartTick >= 0) {
            info.extractStartTick = -1;
            player.sendActionBar(color("&c撤离已取消"));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        // 保留名字/UUID在本局名单中，不把退出视为成功撤离。
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Match match = playerMatches.get(e.getPlayer().getUniqueId());
        if (match != null && match.state == State.ACTIVE) {
            e.getPlayer().sendMessage(color("&e你仍在 " + match.map.name + " 对局中。"));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Match match = playerMatches.get(e.getPlayer().getUniqueId());
        if (match == null || match.state != State.ACTIVE) return;
        if (isAtExtraction(match.map, e.getBlock())) {
            e.setCancelled(true);
        }
    }

    private boolean admin(CommandSender sender) {
        if (!sender.hasPermission("stpr.admin")) {
            msg(sender, "no-permission");
            return false;
        }
        return true;
    }

    private boolean handleAdmin(CommandSender sender, String[] args) {
        if (!admin(sender)) return true;
        if (args.length < 2) {
            msg(sender, "syntax");
            return true;
        }

        String sub = args[1].toLowerCase(Locale.ROOT);

        if (sub.equals("create")) {
            if (!(sender instanceof Player p) || args.length != 3 || !validName(args[2])) {
                msg(sender, "syntax");
                return true;
            }
            String name = args[2];
            if (maps.containsKey(name.toLowerCase(Locale.ROOT))) {
                msg(sender, "map-exists", "%map%", name);
                return true;
            }
            STPRMap map = new STPRMap(name, getConfig());
            map.postExtraction = p.getLocation().clone();
            maps.put(name.toLowerCase(Locale.ROOT), map);
            saveMap(map);
            msg(sender, "map-created", "%map%", name);
            return true;
        }

        if (sub.equals("delete")) {
            if (args.length != 3) {
                msg(sender, "syntax");
                return true;
            }
            STPRMap map = maps.remove(args[2].toLowerCase(Locale.ROOT));
            if (map == null) {
                msg(sender, "map-not-found", "%map%", args[2]);
                return true;
            }
            for (Match match : new ArrayList<>(matches)) {
                if (match.map == map) {
                    for (UUID id : new ArrayList<>(match.players)) {
                        playerMatches.remove(id);
                        votes.remove(id);
                    }
                    matches.remove(match);
                }
            }
            File file = new File(getDataFolder(), "maps/" + map.name + ".yml");
            if (file.exists()) file.delete();
            msg(sender, "map-deleted", "%map%", map.name);
            return true;
        }

        if (args.length < 3) {
            msg(sender, "syntax");
            return true;
        }

        STPRMap map = maps.get(args[2].toLowerCase(Locale.ROOT));
        if (map == null) {
            msg(sender, "map-not-found", "%map%", args[2]);
            return true;
        }

        switch (sub) {
            case "min" -> {
                if (args.length != 4) { msg(sender, "syntax"); return true; }
                int n = integer(args[3]);
                if (n < 1 || n > map.maxPlayers) { msg(sender, "syntax"); return true; }
                map.minPlayers = n;
            }
            case "max" -> {
                if (args.length != 4) { msg(sender, "syntax"); return true; }
                int n = integer(args[3]);
                if (n < map.minPlayers) { msg(sender, "syntax"); return true; }
                map.maxPlayers = n;
            }
            case "time" -> {
                if (args.length != 4) { msg(sender, "syntax"); return true; }
                long n = longValue(args[3]);
                if (n <= 0) { msg(sender, "syntax"); return true; }
                map.matchSeconds = n;
            }
            case "allowjoin" -> {
                if (args.length != 4) { msg(sender, "syntax"); return true; }
                map.allowMidJoin = Boolean.parseBoolean(args[3]);
            }
            case "fullstart" -> {
                if (args.length != 4) { msg(sender, "syntax"); return true; }
                map.startWhenFull = Boolean.parseBoolean(args[3]);
            }
            case "exitticks" -> {
                if (args.length != 4) { msg(sender, "syntax"); return true; }
                int n = integer(args[3]);
                if (n < 1) { msg(sender, "syntax"); return true; }
                map.extractTicks = n;
            }
            case "spawnadd" -> {
                if (!(sender instanceof Player p)) { msg(sender, "player-only"); return true; }
                map.spawnPoints.add(p.getLocation().clone());
                msg(sender, "point-added", "%type%", "出生", "%index%", String.valueOf(map.spawnPoints.size()));
            }
            case "spawndelete" -> {
                if (args.length != 4) { msg(sender, "syntax"); return true; }
                int i = integer(args[3]) - 1;
                if (i < 0 || i >= map.spawnPoints.size()) { msg(sender, "syntax"); return true; }
                map.spawnPoints.remove(i);
            }
            case "spawnclear" -> map.spawnPoints.clear();
            case "postset" -> {
                if (!(sender instanceof Player p)) { msg(sender, "player-only"); return true; }
                map.postExtraction = p.getLocation().clone();
            }
            case "exitadd" -> {
                if (!(sender instanceof Player p)) { msg(sender, "player-only"); return true; }
                Block b = p.getLocation().getBlock();
                if (!isExitMaterialValid(b)) {
                    msg(sender, "exit-invalid");
                    return true;
                }
                map.extractionPoints.add(b.getLocation());
                msg(sender, "point-added", "%type%", "撤离", "%index%", String.valueOf(map.extractionPoints.size()));
            }
            case "exitdelete" -> {
                if (args.length != 4) { msg(sender, "syntax"); return true; }
                int i = integer(args[3]) - 1;
                if (i < 0 || i >= map.extractionPoints.size()) { msg(sender, "syntax"); return true; }
                map.extractionPoints.remove(i);
            }
            case "exitclear" -> map.extractionPoints.clear();
            default -> {
                msg(sender, "syntax");
                return true;
            }
        }

        saveMap(map);
        msg(sender, "map-configured", "%map%", map.name);
        return true;
    }

    private boolean isExitMaterialValid(Block b) {
        if (b.getType() != Material.LIME_CARPET) return false;
        return !getConfig().getBoolean("settings.require-bedrock-under-carpet", true)
                || b.getRelative(BlockFace.DOWN).getType() == Material.BEDROCK;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            msg(sender, "syntax");
            return true;
        }

        if (args[0].equalsIgnoreCase("admin")) {
            return handleAdmin(sender, args);
        }

        if (!(sender instanceof Player player)) {
            msg(sender, "player-only");
            return true;
        }

        if (args[0].equalsIgnoreCase("start")) {
            Match match = playerMatches.get(player.getUniqueId());
            if (match == null || match.state != State.WAITING) {
                msg(player, "no-match");
                return true;
            }
            votes.put(player.getUniqueId(), Vote.START);
            msg(player, "vote-start");
            return true;
        }

        if (args[0].equalsIgnoreCase("wait")) {
            Match match = playerMatches.get(player.getUniqueId());
            if (match == null || match.state != State.WAITING) {
                msg(player, "no-match");
                return true;
            }
            votes.put(player.getUniqueId(), Vote.WAIT);
            msg(player, "vote-wait");
            return true;
        }

        if (args[0].equalsIgnoreCase("leave")) {
            leave(player);
            return true;
        }

        STPRMap map = maps.get(args[0].toLowerCase(Locale.ROOT));
        if (map == null) {
            msg(player, "map-not-found", "%map%", args[0]);
            return true;
        }

        join(player, map);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> list = new ArrayList<>(List.of("start", "wait", "leave", "admin"));
            list.addAll(maps.keySet());
            return startsWith(list, args[0]);
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("admin")) {
            return startsWith(List.of("create", "delete", "min", "max", "time",
                    "allowjoin", "fullstart", "exitticks", "spawnadd",
                    "spawndelete", "spawnclear", "postset", "exitadd",
                    "exitdelete", "exitclear"), args[1]);
        }
        return Collections.emptyList();
    }

    private static List<String> startsWith(Collection<String> values, String prefix) {
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
                result.add(value);
            }
        }
        return result;
    }

    private void broadcast(Match match, String key, String... replacement) {
        for (UUID id : new ArrayList<>(match.players)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) msg(p, key, replacement);
        }
    }

    private void broadcastActionBar(Match match, String text) {
        for (UUID id : new ArrayList<>(match.players)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.sendActionBar(color(text));
        }
    }

    private void msg(CommandSender sender, String key, String... replacements) {
        String text = getConfig().getString("messages." + key, "&c未配置消息：" + key);
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            text = text.replace(replacements[i], replacements[i + 1]);
        }
        sender.sendMessage(color(getConfig().getString("messages.prefix", "") + text));
    }

    private static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private static int integer(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return -1; }
    }

    private static long longValue(String s) {
        try { return Long.parseLong(s); } catch (Exception e) { return -1L; }
    }

    private static boolean validName(String name) {
        return name != null && name.matches("[A-Za-z0-9_-]{1,32}");
    }

    private static String formatTimeMillis(long millis) {
        long sec = Math.max(0L, (millis + 999L) / 1000L);
        long min = sec / 60L;
        long s = sec % 60L;
        long hour = min / 60L;
        min %= 60L;
        if (hour > 0) return hour + "小时" + min + "分" + s + "秒";
        if (min > 0) return min + "分" + s + "秒";
        return s + "秒";
    }

    private void loadMaps() {
        maps.clear();
        File dir = new File(getDataFolder(), "maps");
        File[] files = dir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null) return;

        for (File file : files) {
            try {
                STPRMap map = STPRMap.load(file, getConfig());
                if (map != null) maps.put(map.name.toLowerCase(Locale.ROOT), map);
            } catch (Exception ex) {
                getLogger().warning("地图加载失败 " + file.getName() + "：" + ex.getMessage());
            }
        }
    }

    private void saveMap(STPRMap map) {
        File file = new File(getDataFolder(), "maps/" + map.name + ".yml");
        YamlConfiguration cfg = new YamlConfiguration();

        cfg.set("name", map.name);
        cfg.set("min-players", map.minPlayers);
        cfg.set("max-players", map.maxPlayers);
        cfg.set("match-seconds", map.matchSeconds);
        cfg.set("allow-mid-join", map.allowMidJoin);
        cfg.set("start-when-full", map.startWhenFull);
        cfg.set("extract-ticks", map.extractTicks);

        if (map.postExtraction != null) writeLocation(cfg, "post-extraction", map.postExtraction);

        List<Map<String, Object>> spawns = new ArrayList<>();
        for (Location loc : map.spawnPoints) spawns.add(toMap(loc));
        cfg.set("spawn-points", spawns);

        List<Map<String, Object>> exits = new ArrayList<>();
        for (Location loc : map.extractionPoints) exits.add(toMap(loc));
        cfg.set("extraction-points", exits);

        try {
            cfg.save(file);
        } catch (Exception ex) {
            getLogger().warning("地图保存失败 " + map.name + "：" + ex.getMessage());
        }
    }

    private static void writeLocation(YamlConfiguration cfg, String path, Location loc) {
        cfg.set(path + ".world", loc.getWorld().getName());
        cfg.set(path + ".x", loc.getX());
        cfg.set(path + ".y", loc.getY());
        cfg.set(path + ".z", loc.getZ());
        cfg.set(path + ".yaw", loc.getYaw());
        cfg.set(path + ".pitch", loc.getPitch());
    }

    private static Map<String, Object> toMap(Location loc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("world", loc.getWorld().getName());
        m.put("x", loc.getX());
        m.put("y", loc.getY());
        m.put("z", loc.getZ());
        m.put("yaw", loc.getYaw());
        m.put("pitch", loc.getPitch());
        return m;
    }

    private static final class STPRMap {
        final String name;
        int minPlayers;
        int maxPlayers;
        long matchSeconds;
        boolean allowMidJoin;
        boolean startWhenFull;
        int extractTicks;
        Location postExtraction;
        final List<Location> spawnPoints = new ArrayList<>();
        final List<Location> extractionPoints = new ArrayList<>();
        STPRMap(String name, org.bukkit.configuration.file.FileConfiguration config) {
            this.name = name;
            this.minPlayers = config.getInt("settings.default-min-players", 2);
            this.maxPlayers = config.getInt("settings.default-max-players", 8);
            this.matchSeconds = config.getLong("settings.default-match-seconds", 1200L);
            this.allowMidJoin = config.getBoolean("settings.default-allow-mid-join", false);
            this.startWhenFull = config.getBoolean("settings.start-when-full-default", false);
            this.extractTicks = config.getInt("settings.default-extract-ticks", 100);
        }

        static STPRMap load(File file, org.bukkit.configuration.file.FileConfiguration defaults) {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
            STPRMap map = new STPRMap(cfg.getString("name", file.getName().replace(".yml", "")), defaults);
            map.minPlayers = Math.max(1, cfg.getInt("min-players", map.minPlayers));
            map.maxPlayers = Math.max(map.minPlayers, cfg.getInt("max-players", map.maxPlayers));
            map.matchSeconds = Math.max(1L, cfg.getLong("match-seconds", map.matchSeconds));
            map.allowMidJoin = cfg.getBoolean("allow-mid-join", map.allowMidJoin);
            map.startWhenFull = cfg.getBoolean("start-when-full", map.startWhenFull);
            map.extractTicks = Math.max(1, cfg.getInt("extract-ticks", map.extractTicks));

            map.postExtraction = readSingleLocation(cfg, "post-extraction");

            for (Map<?, ?> raw : cfg.getMapList("spawn-points")) {
                Location loc = readMapLocation(raw);
                if (loc != null) map.spawnPoints.add(loc);
            }
            for (Map<?, ?> raw : cfg.getMapList("extraction-points")) {
                Location loc = readMapLocation(raw);
                if (loc != null) map.extractionPoints.add(loc);
            }
            return map;
        }

        private static Location readSingleLocation(YamlConfiguration cfg, String path) {
            String worldName = cfg.getString(path + ".world");
            if (worldName == null) return null;
            World world = Bukkit.getWorld(worldName);
            if (world == null) return null;

            return new Location(
                    world,
                    cfg.getDouble(path + ".x"),
                    cfg.getDouble(path + ".y"),
                    cfg.getDouble(path + ".z"),
                    (float) cfg.getDouble(path + ".yaw"),
                    (float) cfg.getDouble(path + ".pitch")
            );
        }

        private static Location readMapLocation(Map<?, ?> raw) {
            Object worldValue = raw.get("world");
            if (worldValue == null) return null;
            World world = Bukkit.getWorld(String.valueOf(worldValue));
            if (world == null) return null;

            return new Location(
                    world,
                    number(raw.get("x")),
                    number(raw.get("y")),
                    number(raw.get("z")),
                    (float) number(raw.get("yaw")),
                    (float) number(raw.get("pitch"))
            );
        }

        private static double number(Object value) {
            if (value instanceof Number n) return n.doubleValue();
            try { return Double.parseDouble(String.valueOf(value)); }
            catch (Exception e) { return 0.0; }
        }
    }

    private static final class Match {
        final STPRMap map;
        final Set<UUID> players = new LinkedHashSet<>();
        final Map<UUID, ActiveInfo> active = new HashMap<>();
        State state = State.WAITING;
        long startedAt;
        long endAt;
        boolean prompted;

        Match(STPRMap map) {
            this.map = map;
        }
    }

    private static final class ActiveInfo {
        final int extractTicks;
        long extractStartTick = -1;

        ActiveInfo(int extractTicks) {
            this.extractTicks = extractTicks;
        }
    }

    private enum State { WAITING, ACTIVE }
    private enum Vote { NONE, START, WAIT }

    // 解决“PluginCommand 获取”时的 Java 编译依赖问题，直接调用 Bukkit API。
    private static final class PluginCommandAccessor {
        static void register(STPRPlugin plugin) {
            if (plugin.getCommand("stpr") == null) {
                throw new IllegalStateException("plugin.yml 没有 stpr 命令");
            }
            plugin.getCommand("stpr").setExecutor(plugin);
            plugin.getCommand("stpr").setTabCompleter(plugin);
        }
    }
}
