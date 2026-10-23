package com.example.checkplugin;

import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerChatEvent;
import cn.nukkit.event.player.PlayerCommandPreprocessEvent;
import cn.nukkit.event.player.PlayerJoinEvent;
import cn.nukkit.event.player.PlayerMoveEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.form.element.ElementButton;
import cn.nukkit.form.handler.FormResponseHandler;
import cn.nukkit.form.window.FormWindowSimple;
import cn.nukkit.level.Level;
import cn.nukkit.level.Position;
import cn.nukkit.plugin.PluginBase;
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
        public String claimedBy; // Кто забрал дело (если null - видят все)
        
        public Report(String reporter, String target, String reason, String time) {
            this.reporter = reporter; this.target = target; this.reason = reason; this.time = time;
            this.claimedBy = null;
        }
    }

    private final Map<String, P> mutedPlayers = new HashMap<>();
    private final Map<String, P> bannedPlayers = new HashMap<>();
    private final List<Report> reports = new ArrayList<>();
    private Config punishConfig, checkConfig;
    private File punishFile, logFile, checkFile;
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

        logFile = new File(getDataFolder(), "actions.log");
        if (!logFile.exists()) { logFile.getParentFile().mkdirs(); try { logFile.createNewFile(); } catch (Exception e) {} }

        this.getServer().getPluginManager().registerEvents(this, this);
        this.getServer().getScheduler().scheduleRepeatingTask(this, this::updateTimers, 20);
        this.getLogger().info("CheckPlugin включен!");
    }

    @Override
    public void onDisable() { savePunishmentsToConfig(); saveChecksToConfig(); }

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
            default:         return false;
        }
    }

    // ==================== ТАЙМЕР ====================
    private void updateTimers() {
        for (String playerName : new ArrayList<>(checkingPlayers)) {
            Player p = getServer().getPlayer(playerName);
            if (p == null) continue;
            String checkerName = checkerOfPlayer.get(playerName);
            Player checker = checkerName != null ? getServer().getPlayer(checkerName) : null;
            
            // Таймер идет, только если оба онлайн и нет паузы
            boolean isPaused = (checker == null) || pausedTimers.contains(playerName);
            Long remaining = checkTimers.get(playerName);
            if (remaining == null) continue;

            if (!isPaused) {
                remaining -= 1000;
                if (remaining <= 0) {
                    remaining = 0L;
                    checkTimers.put(playerName, remaining);
                    p.sendMessage("§cВремя проверки истекло. Ожидайте решения модератора.");
                    if (checker != null) checker.sendMessage("§eВремя проверки у " + playerName + " истекло.");
                    continue;
                }
                checkTimers.put(playerName, remaining);
            }

            // Красивое оформление таймера
            String timeStr = formatTime(remaining);
            String bar = "§e⏳ Проверка: §f" + timeStr + (isPaused ? " §7(Пауза)" : "");
            p.sendActionBar(bar);
            if (checker != null) checker.sendActionBar("§eПроверка §f" + playerName + "§e: §f" + timeStr + (isPaused ? " §7(Пауза)" : ""));
        }
    }

    private String formatTime(long ms) { long sec = ms / 1000; return String.format("%02d:%02d", sec / 60, sec % 60); }

    // ==================== РЕПОРТЫ ====================
    private boolean handleReport(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) return true;
        if (args.length < 2) { sender.sendMessage("§cИспользование: /report <ник> <причина>"); return true; }
        
        String target = args[0];
        String reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        String time = new SimpleDateFormat("HH:mm").format(new Date());
        
        reports.add(new Report(sender.getName(), target, reason, time));
        sender.sendMessage("§a✔ Репорт на §e" + target + " §aуспешно отправлен.");
        getLogger().info("[REPORT] " + sender.getName() + " -> " + target + " | " + reason);

        String alert = LINE + "\n §c§lНОВЫЙ РЕПОРТ\n" + LINE + "\n §7На игрока: §e" + target + "\n §7Причина: §f" + reason + "\n §7От: §f" + sender.getName() + "\n" + LINE;
        for (Player online : getServer().getOnlinePlayers().values()) {
            if (online.hasPermission("checkplugin.reports")) online.sendMessage(alert);
        }
        return true;
    }

    private boolean handleReports(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) return true;
        
        // Фильтруем: показываем только свободные или те, что забрал этот модератор
        List<Report> visible = new ArrayList<>();
        for (Report r : reports) {
            if (r.claimedBy == null || r.claimedBy.equals(sender.getName())) visible.add(r);
        }

        if (visible.isEmpty()) { sender.sendMessage("§eДоступных репортов нет."); return true; }

        Player player = (Player) sender;
        FormWindowSimple listForm = new FormWindowSimple("§cРепорты (" + visible.size() + ")", "§7Выберите дело:");
        for (Report r : visible) {
            String status = r.claimedBy != null ? " §a[Ваше]" : "";
            listForm.addButton(new ElementButton("§c" + r.target + " §7| от " + r.reporter + status));
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
        FormWindowSimple form = new FormWindowSimple("§cРепорт на " + r.target, "§7От: §f" + r.reporter + "\n§7Причина: §f" + r.reason);
        form.addButton(new ElementButton("§aВызвать на проверку"));
        form.addButton(new ElementButton("§eПроспекать (GM3)"));
        if (r.claimedBy == null) {
            form.addButton(new ElementButton("§6Забрать дело себе"));
        } else {
            form.addButton(new ElementButton("§cЗакрыть дело"));
        }
        
        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed()) return;
            int button = form.getResponse().getClickedButtonId();
            Player target = getServer().getPlayer(r.target);
            
            if (button == 0) { // Вызвать
                if (target != null) startCheck(player, target);
                else player.sendMessage("§cИгрок не в сети.");
                reports.remove(r);
            } else if (button == 1) { // Проспек
                if (target != null) { player.setGamemode(3); player.teleport(target); player.sendMessage("§aВы в режиме наблюдателя."); }
                else player.sendMessage("§cИгрок не в сети.");
                reports.remove(r);
            } else if (button == 2) { // Забрать или Закрыть
                if (r.claimedBy == null) {
                    r.claimedBy = player.getName();
                    player.sendMessage("§aВы забрали дело себе. Теперь его видите только вы.");
                } else {
                    reports.remove(r);
                    player.sendMessage("§aДело закрыто.");
                }
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
                Player p = getServer().getPlayer(t); if (p != null) { p.setImmobile(false); p.setGamemode(0); p.sendMessage("§aПроверка завершена. Спасибо!"); }
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
                sender.sendMessage("§aИгроку разрешено выйти (таймер на паузе)."); 
                Player p = getServer().getPlayer(t);
                if (p != null) {
                    p.sendMessage(LINE + "\n§a✔ Вам разрешили выйти с сервера!\n§7Таймер проверки остановлен.\n" + LINE);
                }
            } else sender.sendMessage("§cИгрок не на проверке.");
            return true;
        }
        
        if (args.length == 1) {
            Player target = getServer().getPlayer(args[0]);
            if (target == null) { sender.sendMessage("§cИгрок не найден."); return true; }
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

        // Новый красивый текст
        target.sendMessage(LINE);
        target.sendMessage("§c§l     ПРОВЕРКА");
        target.sendMessage(LINE);
        target.sendMessage("§7Здравствуйте, уважаемый игрок. Вы были вызваны на проверку на наличие запрещенного ПО.");
        target.sendMessage("");
        target.sendMessage("§eВключите запись экрана. У вас есть 5 минут, чтобы связаться с проверяющим в Discord.");
        target.sendMessage("§eВыход из игры строго с разрешения проверяющего — самостоятельный выход приравнивается к бану.");
        target.sendMessage("§eПризнание сокращает срок блокировки. Ожидайте инструкций.");
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
        
        announce("БАН", "§c", new String[][]{{"Игрок", targetName}, {"Забанил", sender.getName()}, {"Срок", formatDuration(durationMs)}, {"Причина", reason}});
        savePunishmentsToConfig();
        return true;
    }

    // Табличка бана
    private String banScreen(String target, String banner, String reason, long duration, long expire) {
        return LINE + "\n" +
                "§c§lВЫ ЗАБАНЕНЫ\n" +
                LINE + "\n" +
                "§fВаш ник: §e" + target + "\n" +
                "§fЗабанил: §e" + banner + "\n" +
                "§fПричина: §e" + reason + "\n" +
                "§fОсталось: §e" + getRemainingTime(expire) + "\n" +
                LINE;
    }

    private boolean handleMute(CommandSender sender, String[] args) {
        if (args.length < 2) return true;
        String targetName = args[0]; long durationMs = parseDuration(args[1]);
        if (durationMs <= 0) return true;
        String reason = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "Не указана";
        long expireTime = System.currentTimeMillis() + durationMs;
        mutedPlayers.put(targetName.toLowerCase(), new P(expireTime, durationMs, reason, sender.getName()));
        announce("МУТ", "§e", new String[][]{{"Игрок", targetName}, {"Замутил", sender.getName()}, {"Срок", formatDuration(durationMs)}, {"Причина", reason}});
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
                // Задержка 20 тиков (1 сек) для прогрузки экрана
                getServer().getScheduler().scheduleDelayedTask(this, () -> { if (p.isOnline()) p.kick(kickMessage, false); }, 20);
                return;
            } else bannedPlayers.remove(name.toLowerCase());
        }

        // Возврат на проверку
        if (checkingPlayers.contains(name)) {
            Level checkLevel = getServer().getLevelByName(CHECK_WORLD);
            if (checkLevel != null) getServer().getScheduler().scheduleDelayedTask(this, () -> {
                if (p.isOnline() && checkingPlayers.contains(name)) { p.teleport(new Position(CHECK_X, CHECK_Y, CHECK_Z, checkLevel)); p.setImmobile(true); p.sendMessage("§cВы всё еще на проверке."); }
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
        // Если выходит проверяющий, ставим паузу таймеру
        if (checkerOfPlayer.containsValue(name)) {
            for (Map.Entry<String, String> e : checkerOfPlayer.entrySet()) {
                if (e.getValue().equals(name)) pausedTimers.add(e.getKey());
            }
        }
        
        // Если выходит проверяемый и ему НЕ разрешали (нет в pausedTimers) — бан
        if (checkingPlayers.contains(name) && !pausedTimers.contains(name)) {
            long durationMs = 14L * 86_400_000L; long expireTime = System.currentTimeMillis() + durationMs;
            String reason = "Отказ от проверки (выход)";
            bannedPlayers.put(name.toLowerCase(), new P(expireTime, durationMs, reason, "Система"));
            getServer().getNameBans().addBan(name, reason, new Date(expireTime));
            checkingPlayers.remove(name); checkTimers.remove(name); checkerOfPlayer.remove(name);
            announce("БАН ЗА ОТКАЗ", "§4", new String[][]{{"Игрок", name}, {"Срок", "14д"}, {"Причина", reason}});
            savePunishmentsToConfig(); saveChecksToConfig();
        }
    }

    // ==================== ВСПОМОГАТЕЛЬНЫЕ ====================
    private void announce(String header, String color, String[][] fields) {
        StringBuilder sb = new StringBuilder(); sb.append(LINE).append("\n").append(color).append("§l     ").append(header).append("\n").append(LINE).append("\n");
        for (String[] f : fields) sb.append(" §7").append(f[0]).append(": §f").append(f[1]).append("\n");
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