package com.example.checkplugin;

import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.block.BlockBreakEvent;
import cn.nukkit.event.block.BlockPlaceEvent;
import cn.nukkit.event.entity.EntityDamageEvent;
import cn.nukkit.event.player.PlayerChatEvent;
import cn.nukkit.event.player.PlayerCommandPreprocessEvent;
import cn.nukkit.event.player.PlayerJoinEvent;
import cn.nukkit.event.player.PlayerMoveEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.event.server.DataPacketReceiveEvent;
import cn.nukkit.form.element.ElementButton;
import cn.nukkit.form.handler.FormResponseHandler;
import cn.nukkit.form.window.FormWindowSimple;
import cn.nukkit.level.Level;
import cn.nukkit.level.Position;
import cn.nukkit.network.protocol.LoginPacket;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.utils.ClientChainData;
import cn.nukkit.utils.Config;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CheckPlugin extends PluginBase implements Listener {

    private static CheckPlugin instance;
    public static CheckPlugin getInstance() { return instance; }

    private final Map<String, Long> checkTimers = new HashMap<>();
    private final Map<String, String> checkerOfPlayer = new HashMap<>();
    private final Set<String> pausedTimers = ConcurrentHashMap.newKeySet();
    private final Set<String> checkingPlayers = ConcurrentHashMap.newKeySet();
    private final Map<String, String> playerDevices = new HashMap<>();
    
    private static final String CHECK_WORLD = "dowtest";
    private static final double CHECK_X = -2, CHECK_Y = 98, CHECK_Z = -1;
    private static final double CHECKER_X = -5, CHECKER_Y = 98, CHECKER_Z = 1;
    private static final String LINE = "§8§m──────────────────────────────";
    private static final long CHECK_DURATION_MS = 10 * 60 * 1000L;

    private static class P {
        long expire, duration;
        String reason, banner;
        P(long e, long d, String r, String b) { expire = e; duration = d; reason = r; banner = b; }
    }

    public static class Report {
        public String reporter, target, reason, time;
        public String claimedBy;
        public String device;
        
        public Report(String reporter, String target, String reason, String time, String device) {
            this.reporter = reporter; this.target = target; this.reason = reason; this.time = time;
            this.claimedBy = null; this.device = device;
        }
    }

    private final Map<String, P> mutedPlayers = new HashMap<>();
    private final Map<String, P> bannedPlayers = new HashMap<>();
    private final List<Report> reports = new ArrayList<>();
    private Config punishConfig, checkConfig, bioConfig;
    private File punishFile, logFile, checkFile, bioFile;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    @Override
    public void onEnable() {
        instance = this;
        punishFile = new File(getDataFolder(), "punishments.yml");
        if (!punishFile.exists()) { punishFile.getParentFile().mkdirs(); try { punishFile.createNewFile(); } catch (Exception e) {} }
        punishConfig = new Config(punishFile, Config.YAML);
        loadPunishmentsFromConfig();

        checkFile = new File(getDataFolder(), "checks.yml");
        if (!checkFile.exists()) { checkFile.getParentFile().mkdirs(); try { checkFile.createNewFile(); } catch (Exception e) {} }
        checkConfig = new Config(checkFile, Config.YAML);
        loadChecksFromConfig();

        bioFile = new File(getDataFolder(), "bio.yml");
        if (!bioFile.exists()) { bioFile.getParentFile().mkdirs(); try { bioFile.createNewFile(); } catch (Exception e) {} }
        bioConfig = new Config(bioFile, Config.YAML);

        logFile = new File(getDataFolder(), "actions.log");
        if (!logFile.exists()) { logFile.getParentFile().mkdirs(); try { logFile.createNewFile(); } catch (Exception e) {} }

        this.getServer().getPluginManager().registerEvents(this, this);
        this.getServer().getScheduler().scheduleRepeatingTask(this, this::updateTimers, 20);
        this.getLogger().info("CheckPlugin включен!");
    }

    @Override
    public void onDisable() { savePunishmentsToConfig(); saveChecksToConfig(); bioConfig.save(); }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName().toLowerCase()) {
            case "check":    return handleCheck(sender, args);
            case "ban":      return handleBan(sender, args);
            case "mute":     return handleMute(sender, args);
            case "unban":    return handleUnban(sender, args);
            case "unmute":   return handleUnmute(sender, args);
            case "checklogs":return handleLogs(sender, args);
            case "report":   return handleReport(sender, args);
            case "reports":  return handleReports(sender, args);
            case "bio":      return handleBio(sender, args);
            default:         return false;
        }
    }

    // ==================== /bio ====================
    private boolean handleBio(CommandSender sender, String[] args) {
        if (args.length != 1) { sender.sendMessage("§cИспользование: /bio <ник>"); return true; }
        String targetName = args[0];
        Player target = getServer().getPlayer(targetName);
        
        String displayName = targetName;
        String device = playerDevices.getOrDefault(targetName, "Неизвестно");
        String firstPlayedStr = "Неизвестно";
        
        if (target != null) {
            displayName = target.getName();
            device = playerDevices.getOrDefault(target.getName(), "Неизвестно");
            Long firstPlayed = target.getFirstPlayed();
            if (firstPlayed != null && firstPlayed > 0) {
                SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy HH:mm");
                firstPlayedStr = sdf.format(new Date(firstPlayed * 1000L));
            }
        }
        
        int broken = bioConfig.getInt(targetName + ".blocks_broken", 0);
        int placed = bioConfig.getInt(targetName + ".blocks_placed", 0);
        int messages = bioConfig.getInt(targetName + ".messages", 0);
        int deaths = bioConfig.getInt(targetName + ".deaths", 0);
        
        sender.sendMessage(LINE);
        sender.sendMessage("§c§l     ДОСЬЕ НА ИГРОКА");
        sender.sendMessage(LINE);
        sender.sendMessage("§fНик: §c" + displayName);
        sender.sendMessage("§fУстройство: §c" + device);
        sender.sendMessage("§fПервый вход: §c" + firstPlayedStr);
        sender.sendMessage("");
        sender.sendMessage("§fСломал блоков: §c" + broken);
        sender.sendMessage("§fПоставил блоков: §c" + placed);
        sender.sendMessage("§fСообщений в чат: §c" + messages);
        sender.sendMessage("§fСмертей: §c" + deaths);
        sender.sendMessage(LINE);
        return true;
    }

    // ==================== УСТРОЙСТВО ====================
    @EventHandler
    public void onDataPacketReceive(DataPacketReceiveEvent event) {
        if (event.getPacket() instanceof LoginPacket) {
            try {
                ClientChainData data = ClientChainData.read((LoginPacket) event.getPacket());
                String playerName = data.getUsername();
                int os = data.getDeviceOS();
                String osName;
                switch (os) {
                    case 1: osName = "Android"; break;
                    case 2: osName = "iOS"; break;
                    case 7: osName = "Windows"; break;
                    case 3: osName = "macOS"; break;
                    case 8: osName = "Windows (Win32)"; break;
                    default: osName = "OS (" + os + ")"; break;
                }
                playerDevices.put(playerName, osName);
            } catch (Exception e) { }
        }
    }

    // ==================== СТАТИСТИКА ДЛЯ BIO ====================
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        String name = event.getPlayer().getName();
        bioConfig.set(name + ".blocks_broken", bioConfig.getInt(name + ".blocks_broken", 0) + 1);
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        String name = event.getPlayer().getName();
        bioConfig.set(name + ".blocks_placed", bioConfig.getInt(name + ".blocks_placed", 0) + 1);
    }

    @EventHandler
    public void onChatStat(PlayerChatEvent event) {
        String name = event.getPlayer().getName();
        bioConfig.set(name + ".messages", bioConfig.getInt(name + ".messages", 0) + 1);
    }

    // ИСПРАВЛЕНО: EntityDamageEvent вместо PlayerDeathEvent
    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();

        // Проверяем, умрет ли игрок от этого урона
        if (player.getHealth() - event.getFinalDamage() <= 0) {
            String name = player.getName();
            bioConfig.set(name + ".deaths", bioConfig.getInt(name + ".deaths", 0) + 1);
        }
    }

    // ==================== ТАЙМЕР ====================
    private void updateTimers() {
        for (String playerName : new ArrayList<>(checkingPlayers)) {
            Player p = getServer().getPlayer(playerName);
            if (p == null) continue;
            String checkerName = checkerOfPlayer.get(playerName);
            Player checker = checkerName != null ? getServer().getPlayer(checkerName) : null;
            
            boolean isPaused = (checker == null) || pausedTimers.contains(playerName);
            Long remaining = checkTimers.get(playerName);
            if (remaining == null) continue;

            if (!isPaused) {
                remaining -= 1000;
                if (remaining <= 0) {
                    remaining = 0L;
                    checkTimers.put(playerName, remaining);
                    p.sendMessage(LINE + "\n§c§lВРЕМЯ ИСТЕКЛО\n§fОжидайте решения модератора.\n" + LINE);
                    if (checker != null) checker.sendMessage("§eВремя проверки у " + playerName + " истекло.");
                    continue;
                }
                checkTimers.put(playerName, remaining);
            }

            String timeStr = formatTime(remaining);
            p.sendActionBar("§c⏳ §f" + timeStr + (isPaused ? " §7(Пауза)" : ""));
            if (checker != null) checker.sendActionBar("§cПроверка §f" + playerName + "§c: §f" + timeStr);
        }
    }

    private String formatTime(long ms) { long sec = ms / 1000; return String.format("%02d:%02d", sec / 60, sec % 60); }

    // ==================== РЕПОРТЫ ====================
    private boolean handleReport(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) return true;
        if (args.length < 2) { sender.sendMessage("§c/report <ник> <причина>"); return true; }
        
        String target = args[0];
        String reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        String time = new SimpleDateFormat("HH:mm").format(new Date());
        String targetDevice = playerDevices.getOrDefault(target, "Неизвестно");
        
        reports.add(new Report(sender.getName(), target, reason, time, targetDevice));
        sender.sendMessage("§a✔ Репорт на §f" + target + " §aотправлен.");
        
        String alert = LINE + "\n §c§lНОВЫЙ РЕПОРТ\n" + LINE + "\n §fНа игрока: §c" + target + "\n §fУстройство: §c" + targetDevice + "\n §fПричина: §c" + reason + "\n §fОт: §c" + sender.getName() + "\n" + LINE;
        for (Player online : getServer().getOnlinePlayers().values()) {
            if (online.hasPermission("checkplugin.reports")) online.sendMessage(alert);
        }
        return true;
    }

    private boolean handleReports(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) return true;
        List<Report> visible = new ArrayList<>();
        for (Report r : reports) {
            if (r.claimedBy == null || r.claimedBy.equals(sender.getName())) visible.add(r);
        }
        if (visible.isEmpty()) { sender.sendMessage("§cДоступных репортов нет."); return true; }

        Player player = (Player) sender;
        FormWindowSimple listForm = new FormWindowSimple("§cРепорты (" + visible.size() + ")", "§fВыберите дело:");
        for (Report r : visible) {
            String status = r.claimedBy != null ? " §a[Ваше]" : "";
            listForm.addButton(new ElementButton("§c" + r.target + " §f| от " + r.reporter + "\n§7Устройство: " + r.device + status));
        }
        listForm.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (listForm.wasClosed()) return;
            int index = listForm.getResponse().getClickedButtonId();
            if (index >= 0 && index < visible.size()) openActionForm(player, visible.get(index));
        }));
        player.showFormWindow(listForm);
        return true;
    }

    private void openActionForm(Player player, Report r) {
        FormWindowSimple form = new FormWindowSimple("§cРепорт на " + r.target, "§fОт: " + r.reporter + "\n§fУстройство: " + r.device + "\n§fПричина: " + r.reason);
        form.addButton(new ElementButton("§aВызвать на проверку"));
        form.addButton(new ElementButton("§eПроспекать (GM3)"));
        if (r.claimedBy == null) form.addButton(new ElementButton("§6Забрать дело себе"));
        else form.addButton(new ElementButton("§cЗакрыть дело"));
        
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed()) return;
            int button = form.getResponse().getClickedButtonId();
            Player target = getServer().getPlayer(r.target);
            if (button == 0) {
                if (target != null) startCheck(player, target);
                else player.sendMessage("§cИгрок не в сети.");
                reports.remove(r);
            } else if (button == 1) {
                if (target != null) { player.setGamemode(3); player.teleport(target); player.sendMessage("§aВы в режиме наблюдателя."); }
                else player.sendMessage("§cИгрок не в сети.");
                reports.remove(r);
            } else if (button == 2) {
                if (r.claimedBy == null) { r.claimedBy = player.getName(); player.sendMessage("§aВы забрали дело."); }
                else { reports.remove(r); player.sendMessage("§aДело закрыто."); }
            }
        }));
        player.showFormWindow(form);
    }

    // ==================== ПРОВЕРКИ ====================
    private boolean handleCheck(CommandSender sender, String[] args) {
        if (args.length == 0) { sender.sendMessage("§c/check <ник> | /check done <ник> | /check leave <ник>"); return true; }
        
        if (args.length == 2 && args[0].equalsIgnoreCase("done")) {
            String t = args[1];
            if (checkingPlayers.remove(t)) {
                Player p = getServer().getPlayer(t); if (p != null) { p.setImmobile(false); p.setGamemode(0); p.sendMessage(LINE + "\n§a§lПРОВЕРКА ЗАВЕРШЕНА\n§fСпасибо за сотрудничество.\n" + LINE); }
                Player ch = getServer().getPlayer(sender.getName()); if (ch != null) ch.setGamemode(0);
                checkTimers.remove(t); checkerOfPlayer.remove(t); pausedTimers.remove(t); saveChecksToConfig();
                sender.sendMessage("§aИгрок снят с проверки.");
            } else sender.sendMessage("§cИгрок не на проверке.");
            return true;
        }
        
        if (args.length == 2 && args[0].equalsIgnoreCase("leave")) {
            String t = args[1];
            if (checkingPlayers.contains(t)) { 
                pausedTimers.add(t); 
                sender.sendMessage("§aИгроку разрешено выйти.");
                Player p = getServer().getPlayer(t);
                if (p != null) p.sendMessage(LINE + "\n§a✔ ВАМ РАЗРЕШИЛИ ВЫЙТИ\n§fТаймер проверки остановлен.\n" + LINE);
            } else sender.sendMessage("§cИгрок не на проверке.");
            return true;
        }
        
        if (args.length == 1) {
            Player target = getServer().getPlayer(args[0]);
            if (target == null) { sender.sendMessage("§cИгрок не найден."); return true; }
            if (checkingPlayers.contains(target.getName())) { sender.sendMessage("§cИгрок уже находится на проверке!"); return true; }
            if (sender instanceof Player && ((Player) sender).getGamemode() == 3) ((Player) sender).setGamemode(0);
            startCheck((Player) sender, target);
            return true;
        }
        return true;
    }

    private void startCheck(Player checker, Player target) {
        Level lvl = getServer().getLevelByName(CHECK_WORLD);
        if (lvl == null && getServer().loadLevel(CHECK_WORLD)) lvl = getServer().getLevelByName(CHECK_WORLD);
        if (lvl == null) { checker.sendMessage("§cМир не существует!"); return; }
        
        target.teleport(new Position(CHECK_X, CHECK_Y, CHECK_Z, lvl)); 
        target.setImmobile(true);
        checkingPlayers.add(target.getName()); 
        checkTimers.put(target.getName(), CHECK_DURATION_MS); 
        checkerOfPlayer.put(target.getName(), checker.getName()); 
        pausedTimers.remove(target.getName());
        checker.teleport(new Position(CHECKER_X, CHECKER_Y, CHECKER_Z, lvl)); 
        saveChecksToConfig();

        target.sendMessage(LINE);
        target.sendMessage("§c§l     ПРОВЕРКА");
        target.sendMessage(LINE);
        target.sendMessage("§fЗдравствуйте, уважаемый игрок. Вы были вызваны на проверку на наличие запрещенного ПО.");
        target.sendMessage("");
        target.sendMessage("§cВключите запись экрана. §fУ вас есть §c5 минут§f, чтобы связаться с проверяющим в Discord.");
        target.sendMessage("§cВыход из игры строго с разрешения проверяющего — самостоятельный выход приравнивается к бану.");
        target.sendMessage("§cПризнание сокращает срок блокировки. §fОжидайте инструкций.");
        target.sendMessage(LINE);
    }

    // ==================== НАКАЗАНИЯ ====================
    private boolean handleBan(CommandSender sender, String[] args) {
        if (args.length < 2) return true;
        String targetName = args[0]; long durationMs = parseDuration(args[1]);
        if (durationMs <= 0) return true;
        String reason = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "Не указана";
        long expireTime = System.currentTimeMillis() + durationMs;
        bannedPlayers.put(targetName.toLowerCase(), new P(expireTime, durationMs, reason, sender.getName()));
        getServer().getNameBans().addBan(targetName, reason, new Date(expireTime));
        
        Player target = getServer().getPlayer(targetName);
        if (target != null) target.kick(banScreen(targetName, sender.getName(), reason, durationMs, expireTime), false);
        
        announce("БАН", new String[][]{{"Игрок", targetName}, {"Забанил", sender.getName()}, {"Срок", formatDuration(durationMs)}, {"Причина", reason}});
        savePunishmentsToConfig();
        return true;
    }

    private String banScreen(String target, String banner, String reason, long duration, long expire) {
        return LINE + "\n" +
                "§c§lВЫ ЗАБАНЕНЫ\n" +
                LINE + "\n" +
                "§fВаш ник: §c" + target + "\n" +
                "§fЗабанил: §c" + banner + "\n" +
                "§fПричина: §c" + reason + "\n" +
                "§fОсталось: §c" + getRemainingTime(expire) + "\n" +
                LINE;
    }

    private boolean handleMute(CommandSender sender, String[] args) {
        if (args.length < 2) return true;
        String targetName = args[0]; long durationMs = parseDuration(args[1]);
        if (durationMs <= 0) return true;
        String reason = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "Не указана";
        long expireTime = System.currentTimeMillis() + durationMs;
        mutedPlayers.put(targetName.toLowerCase(), new P(expireTime, durationMs, reason, sender.getName()));
        announce("МУТ", new String[][]{{"Игрок", targetName}, {"Замутил", sender.getName()}, {"Срок", formatDuration(durationMs)}, {"Причина", reason}});
        savePunishmentsToConfig(); return true;
    }

    private boolean handleUnban(CommandSender sender, String[] args) {
        if (args.length != 1) return true;
        if (bannedPlayers.remove(args[0].toLowerCase()) != null) { try { getServer().getNameBans().remove(args[0]); } catch (Exception e) {} sender.sendMessage("§aРазбанен."); savePunishmentsToConfig(); }
        return true;
    }

    private boolean handleUnmute(CommandSender sender, String[] args) {
        if (args.length != 1) return true;
        if (mutedPlayers.remove(args[0].toLowerCase()) != null) { sender.sendMessage("§aРазмучен."); savePunishmentsToConfig(); }
        return true;
    }

    private boolean handleLogs(CommandSender sender, String[] args) { return true; }

    // ==================== СОБЫТИЯ ====================
    @EventHandler
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) { if (checkingPlayers.contains(event.getPlayer().getName())) { event.setCancelled(true); event.getPlayer().sendMessage("§cКоманды запрещены."); } }
    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) { if (checkingPlayers.contains(event.getPlayer().getName())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayerChat(PlayerChatEvent event) { P p = mutedPlayers.get(event.getPlayer().getName().toLowerCase()); if (p != null && System.currentTimeMillis() < p.expire) { event.setCancelled(true); event.getPlayer().sendMessage("§cВы замучены."); } }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        String name = event.getPlayer().getName(); Player p = event.getPlayer();
        P ban = bannedPlayers.get(name.toLowerCase());
        if (ban != null) {
            if (System.currentTimeMillis() < ban.expire) {
                final String kickMessage = banScreen(name, ban.banner, ban.reason, ban.duration, ban.expire);
                getServer().getScheduler().scheduleDelayedTask(this, () -> { if (p.isOnline()) p.kick(kickMessage, false); }, 20);
                return;
            } else bannedPlayers.remove(name.toLowerCase());
        }

        if (checkingPlayers.contains(name)) {
            Level checkLevel = getServer().getLevelByName(CHECK_WORLD);
            if (checkLevel != null) getServer().getScheduler().scheduleDelayedTask(this, () -> {
                if (p.isOnline() && checkingPlayers.contains(name)) { p.teleport(new Position(CHECK_X, CHECK_Y, CHECK_Z, checkLevel)); p.setImmobile(true); p.sendMessage(LINE + "\n§cВы всё еще на проверке.\n" + LINE); }
            }, 20);
        }
        if (checkerOfPlayer.containsValue(name)) {
            Level checkLevel = getServer().getLevelByName(CHECK_WORLD);
            if (checkLevel != null) getServer().getScheduler().scheduleDelayedTask(this, () -> {
                if (p.isOnline()) { p.teleport(new Position(CHECKER_X, CHECKER_Y, CHECKER_Z, checkLevel)); p.sendMessage("§aВы вернулись на проверку."); }
            }, 20);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        String name = event.getPlayer().getName();
        if (checkerOfPlayer.containsValue(name)) {
            for (Map.Entry<String, String> e : checkerOfPlayer.entrySet()) {
                if (e.getValue().equals(name)) pausedTimers.add(e.getKey());
            }
        }
        if (checkingPlayers.contains(name) && !pausedTimers.contains(name)) {
            long durationMs = 14L * 86_400_000L; long expireTime = System.currentTimeMillis() + durationMs;
            String reason = "Отказ от проверки (выход)";
            bannedPlayers.put(name.toLowerCase(), new P(expireTime, durationMs, reason, "Система"));
            getServer().getNameBans().addBan(name, reason, new Date(expireTime));
            checkingPlayers.remove(name); checkTimers.remove(name); checkerOfPlayer.remove(name);
            announce("БАН ЗА ОТКАЗ", new String[][]{{"Игрок", name}, {"Срок", "14д"}, {"Причина", reason}});
            savePunishmentsToConfig(); saveChecksToConfig();
        }
    }

    // ==================== ВСПОМОГАТЕЛЬНЫЕ ====================
    private void announce(String header, String[][] fields) {
        StringBuilder sb = new StringBuilder(); sb.append(LINE).append("\n").append("§c§l     ").append(header).append("\n").append(LINE).append("\n");
        for (String[] f : fields) sb.append(" §f").append(f[0]).append(": §c").append(f[1]).append("\n");
        sb.append(LINE); getServer().broadcastMessage(sb.toString());
    }

    private long parseDuration(String input) {
        Pattern pattern = Pattern.compile("(\\d+)([dhms])"); Matcher matcher = pattern.matcher(input.toLowerCase());
        long totalMs = 0; boolean found = false;
        while (matcher.find()) { found = true; long value = Long.parseLong(matcher.group(1));
            switch (matcher.group(2)) { case "d": totalMs += value * 86_400_000L; break; case "h": totalMs += value * 3_600_000L; break; case "m": totalMs += value * 60_000L; break; case "s": totalMs += value * 1_000L; break; } }
        return found ? totalMs : 0;
    }

    private String formatDuration(long ms) {
        long days = ms / 86_400_000L; ms %= 86_400_000L; long hours = ms / 3_600_000L; ms %= 3_600_000L; long minutes = ms / 60_000L;
        StringBuilder sb = new StringBuilder(); if (days > 0) sb.append(days).append("д "); if (hours > 0) sb.append(hours).append("ч "); if (minutes > 0) sb.append(minutes).append("м");
        return sb.toString().trim().isEmpty() ? "0м" : sb.toString().trim();
    }

    private String getRemainingTime(long expireTimestamp) { long remaining = expireTimestamp - System.currentTimeMillis(); return remaining <= 0 ? "0м" : formatDuration(remaining); }

    private void loadPunishmentsFromConfig() {
        if (punishConfig.isSection("bans")) for (String key : punishConfig.getSection("bans").getKeys(false)) { String[] parts = punishConfig.getString("bans." + key, "").split("\\|", 4); if (parts.length == 4) try { bannedPlayers.put(key, new P(Long.parseLong(parts[0]), Long.parseLong(parts[1]), parts[3], parts[2])); } catch (Exception ignored) {} }
        if (punishConfig.isSection("mutes")) for (String key : punishConfig.getSection("mutes").getKeys(false)) { String[] parts = punishConfig.getString("mutes." + key, "").split("\\|", 4); if (parts.length == 4) try { mutedPlayers.put(key, new P(Long.parseLong(parts[0]), Long.parseLong(parts[1]), parts[3], parts[2])); } catch (Exception ignored) {} }
    }

    private void savePunishmentsToConfig() {
        punishConfig.set("bans", null); for (Map.Entry<String, P> e : bannedPlayers.entrySet()) { P p = e.getValue(); punishConfig.set("bans." + e.getKey(), p.expire + "|" + p.duration + "|" + p.banner + "|" + p.reason); }
        punishConfig.set("mutes", null); for (Map.Entry<String, P> e : mutedPlayers.entrySet()) { P p = e.getValue(); punishConfig.set("mutes." + e.getKey(), p.expire + "|" + p.duration + "|" + p.banner + "|" + p.reason); }
        punishConfig.save();
    }

    private void loadChecksFromConfig() {
        if (checkConfig.isSection("checks")) for (String key : checkConfig.getSection("checks").getKeys(false)) { String[] parts = checkConfig.getString("checks." + key, "").split("\\|", 3); if (parts.length == 3) try { checkingPlayers.add(key); checkTimers.put(key, Long.parseLong(parts[0])); checkerOfPlayer.put(key, parts[1]); if (parts[2].equals("true")) pausedTimers.add(key); } catch (Exception ignored) {} }
    }

    private void saveChecksToConfig() {
        checkConfig.set("checks", null);
        for (String pName : checkingPlayers) { Long time = checkTimers.get(pName); String chk = checkerOfPlayer.get(pName); if (time != null && chk != null) checkConfig.set("checks." + pName, time + "|" + chk + "|" + pausedTimers.contains(pName)); }
        checkConfig.save();
    }
}