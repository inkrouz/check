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

    private final Set<String> checkingPlayers = ConcurrentHashMap.newKeySet();
    private static final String CHECK_WORLD = "dowtest";
    private static final double CHECK_X = -2, CHECK_Y = 98, CHECK_Z = -1;
    private static final double CHECKER_X = -5, CHECKER_Y = 98, CHECKER_Z = 1;
    private static final String LINE = "§8§m──────────────────────────────";

    // Таймер: сколько миллисекунд осталось у каждого игрока на проверке
    private final Map<String, Long> checkTimers = new HashMap<>();
    private final Map<String, String> checkerOfPlayer = new HashMap<>(); // кто проверяет игрока
    private static final long CHECK_DURATION_MS = 10 * 60 * 1000L; // 10 минут

    private static class P {
        long expire, duration;
        String reason, banner;
        P(long e, long d, String r, String b) { expire = e; duration = d; reason = r; banner = b; }
    }

    public static class Report {
        public String reporter, target, reason, time;
        public Report(String reporter, String target, String reason, String time) {
            this.reporter = reporter; this.target = target; this.reason = reason; this.time = time;
        }
    }

    private final Map<String, P> mutedPlayers = new HashMap<>();
    private final Map<String, P> bannedPlayers = new HashMap<>();
    private final List<Report> reports = new ArrayList<>();
    private Config punishConfig;
    private File punishFile, logFile;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    @Override
    public void onEnable() {
        instance = this;

        punishFile = new File(getDataFolder(), "punishments.yml");
        if (!punishFile.exists()) {
            punishFile.getParentFile().mkdirs();
            try { punishFile.createNewFile(); } catch (Exception e) { getLogger().error("Не удалось создать файл наказаний!", e); }
        }
        punishConfig = new Config(punishFile, Config.YAML);
        loadPunishmentsFromConfig();

        logFile = new File(getDataFolder(), "actions.log");
        if (!logFile.exists()) {
            logFile.getParentFile().mkdirs();
            try { logFile.createNewFile(); } catch (Exception e) { getLogger().error("Не удалось создать файл логов!", e); }
        }

        this.getServer().getPluginManager().registerEvents(this, this);

        // Запускаем таймер обновления Action Bar каждую секунду (20 тиков)
        this.getServer().getScheduler().scheduleRepeatingTask(this, this::updateTimers, 20);

        this.getLogger().info("CheckPlugin включен! Система проверок и наказаний активна.");
    }

    @Override
    public void onDisable() { savePunishmentsToConfig(); }

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

            // Проверяем, не вышел ли проверяющий
            String checkerName = checkerOfPlayer.get(playerName);
            Player checker = checkerName != null ? getServer().getPlayer(checkerName) : null;
            
            // Таймер идет ТОЛЬКО если онлайн И игрок, И проверяющий
            boolean paused = (checker == null);

            Long remaining = checkTimers.get(playerName);
            if (remaining == null) continue;

            if (!paused) {
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

            // Отправляем Action Bar игроку и проверяющему
            String timeStr = formatTime(remaining);
            String bar = "§eПроверка: §f" + timeStr + (paused ? " §7(пауза)" : "");
            p.sendActionBar(bar);
            if (checker != null) checker.sendActionBar("§eПроверка " + playerName + ": §f" + timeStr + (paused ? " §7(пауза)" : ""));
        }
    }

    private String formatTime(long ms) {
        long sec = ms / 1000;
        return String.format("%02d:%02d", sec / 60, sec % 60);
    }

    // ==================== /report ====================
    private boolean handleReport(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage("§cТолько для игроков."); return true; }
        if (args.length < 2) { sender.sendMessage("§c/report <ник> <причина>"); return true; }
        String target = args[0];
        String reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        String time = new SimpleDateFormat("HH:mm dd.MM").format(new Date());
        reports.add(new Report(sender.getName(), target, reason, time));
        sender.sendMessage("§aРепорт на " + target + " отправлен.");
        getLogger().info("[REPORT] " + sender.getName() + " -> " + target + " | " + reason);
        return true;
    }

    // ==================== /reports ====================
    private boolean handleReports(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage("§cТолько для игроков."); return true; }
        if (reports.isEmpty()) { sender.sendMessage("§eРепортов нет."); return true; }

        Player player = (Player) sender;
        FormWindowSimple listForm = new FormWindowSimple("§cРепорты (" + reports.size() + ")", "§7Выберите репорт:");
        for (Report r : reports) {
            listForm.addButton(new ElementButton("§c" + r.target + " §7| от " + r.reporter));
        }
        listForm.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (listForm.wasClosed()) return;
            int index = listForm.getResponse().getClickedButtonId();
            if (index < 0 || index >= reports.size()) return;
            openActionForm(player, reports.get(index));
        }));
        player.showFormWindow(listForm);
        return true;
    }

    private void openActionForm(Player player, Report r) {
        FormWindowSimple form = new FormWindowSimple(
                "§cРепорт на " + r.target,
                "§7От: §f" + r.reporter + "\n§7Причина: §f" + r.reason + "\n§7Время: §f" + r.time
        );
        form.addButton(new ElementButton("§aВызвать на проверку"));
        form.addButton(new ElementButton("§eПроспекать (телепорт + GM3)"));
        form.addButton(new ElementButton("§cЗакрыть дело"));

        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed()) return;
            int button = form.getResponse().getClickedButtonId();
            switch (button) {
                case 0: {
                    Player target = getServer().getPlayer(r.target);
                    if (target != null) {
                        startCheck(player, target);
                        player.sendMessage("§aИгрок " + r.target + " вызван на проверку.");
                    } else {
                        player.sendMessage("§cИгрок " + r.target + " не в сети.");
                    }
                    reports.remove(r);
                    logAction(player.getName(), "РЕПОРТ: ВЫЗОВ", r.target, "Причина: " + r.reason);
                    break;
                }
                case 1: {
                    Player target = getServer().getPlayer(r.target);
                    if (target != null) {
                        // Проспек: телепорт к игроку + GM3
                        player.setGamemode(3);
                        player.teleport(target);
                        player.sendMessage("§aВы телепортированы к " + r.target + " в режиме наблюдателя.");
                    } else {
                        player.sendMessage("§cИгрок не в сети.");
                    }
                    reports.remove(r);
                    logAction(player.getName(), "РЕПОРТ: СПЕК", r.target, "Причина: " + r.reason);
                    break;
                }
                case 2: {
                    reports.remove(r);
                    player.sendMessage("§7Репорт на " + r.target + " закрыт.");
                    logAction(player.getName(), "РЕПОРТ: ЗАКРЫТ", r.target, "Причина: " + r.reason);
                    break;
                }
            }
        }));

        player.showFormWindow(form);
    }

    // ==================== /check ====================
    private boolean handleCheck(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§c/check <ник> §7— вызвать на проверку");
            sender.sendMessage("§c/check done <ник> §7— снять с проверки");
            sender.sendMessage("§c/check leave <ник> §7— разрешить выйти без бана");
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("done")) {
            String targetName = args[1];
            if (checkingPlayers.remove(targetName)) {
                Player target = getServer().getPlayer(targetName);
                if (target != null) {
                    target.setImmobile(false);
                    target.setGamemode(0);
                    target.sendMessage("§aПроверка завершена.");
                }
                // Снимаем гм3 с проверяющего, если он онлайн
                Player checker = getServer().getPlayer(sender.getName());
                if (checker != null) checker.setGamemode(0);

                checkTimers.remove(targetName);
                checkerOfPlayer.remove(targetName);

                sender.sendMessage("§aИгрок " + targetName + " снят с проверки.");
                logAction(sender.getName(), "СНЯЛ С ПРОВЕРКИ", targetName, "");
                announce("СНЯТ С ПРОВЕРКИ", "§a", new String[][]{{"Игрок", targetName}, {"Снял", sender.getName()}}, "§a§lСНЯТ С ПРОВЕРКИ", "§f" + targetName);
            } else sender.sendMessage("§cИгрок " + targetName + " не на проверке.");
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("leave")) {
            String targetName = args[1];
            if (checkingPlayers.remove(targetName)) {
                checkTimers.remove(targetName);
                checkerOfPlayer.remove(targetName);
                sender.sendMessage("§aИгроку " + targetName + " разрешено выйти без бана. Проверка сброшена.");
                logAction(sender.getName(), "РАЗРЕШИЛ ВЫХОД", targetName, "");
            } else sender.sendMessage("§cИгрок " + targetName + " не на проверке.");
            return true;
        }
        if (args.length == 1) {
            Player target = getServer().getPlayer(args[0]);
            if (target == null) { sender.sendMessage("§cИгрок " + args[0] + " не найден."); return true; }
            startCheck((Player) sender, target);
            return true;
        }
        sender.sendMessage("§cНеверное использование. /check для справки.");
        return true;
    }

    private void startCheck(Player checker, Player target) {
        Level checkLevel = getServer().getLevelByName(CHECK_WORLD);
        if (checkLevel == null && getServer().loadLevel(CHECK_WORLD)) checkLevel = getServer().getLevelByName(CHECK_WORLD);
        if (checkLevel == null) { checker.sendMessage("§cМир '" + CHECK_WORLD + "' не существует!"); return; }

        // Телепорт проверяемого
        target.teleport(new Position(CHECK_X, CHECK_Y, CHECK_Z, checkLevel));
        target.setImmobile(true);
        checkingPlayers.add(target.getName());
        checkTimers.put(target.getName(), CHECK_DURATION_MS);
        checkerOfPlayer.put(target.getName(), checker.getName());

        // Проверяющий: НЕ в GM3, просто телепорт рядом
        checker.teleport(new Position(CHECKER_X, CHECKER_Y, CHECKER_Z, checkLevel));

        // Новый текст проверки
        target.sendMessage(LINE);
        target.sendMessage("§c§l     ПРОВЕРКА");
        target.sendMessage(LINE);
        target.sendMessage("§7Здравствуйте! Это проверка.");
        target.sendMessage("§eВключите запись экрана и предоставьте доказательства.");
        target.sendMessage("§eЕсли у вас нет Discord — скачайте его в течение §c8 минут§e.");
        target.sendMessage("§eУ вас есть §c10 минут§e на всё.");
        target.sendMessage("§eВыход — §cстрого с разрешения проверяющего§e.");
        target.sendMessage("§eЕсли выйдете без разрешения — §cбан 14 дней§e.");
        target.sendMessage(LINE);

        target.sendTitle("§cВы вызваны на проверку", "§eПроверяющий: §f" + checker.getName(), 20, 100, 20);

        checker.sendMessage(LINE);
        checker.sendMessage("§eВы вызвали §f" + target.getName() + " §eна проверку.");
        checker.sendMessage(LINE);

        checker.sendMessage("§aИгрок " + target.getName() + " телепортирован на проверку.");
        logAction(checker.getName(), "ВЫЗВАЛ НА ПРОВЕРКУ", target.getName(), "");
        announce("ВЫЗОВ НА ПРОВЕРКУ", "§c", new String[][]{{"Игрок", target.getName()}, {"Проверяющий", checker.getName()}}, "§c§lПРОВЕРКА", "§f" + target.getName() + " §7вызван");
    }

    // ==================== /ban ====================
    private boolean handleBan(CommandSender sender, String[] args) {
        if (args.length < 2) { sender.sendMessage("§c/ban <ник> <срок> [причина]"); return true; }
        String targetName = args[0];
        long durationMs = parseDuration(args[1]);
        if (durationMs <= 0) { sender.sendMessage("§cНеверный срок. Примеры: 10d, 5h, 30m."); return true; }
        String reason = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "Не указана";
        long expireTime = System.currentTimeMillis() + durationMs;
        bannedPlayers.put(targetName.toLowerCase(), new P(expireTime, durationMs, reason, sender.getName()));
        getServer().getNameBans().addBan(targetName, reason, new Date(expireTime));
        Player target = getServer().getPlayer(targetName);
        if (target != null) target.kick(banScreen(targetName, sender.getName(), reason, durationMs, expireTime), false);
        announce("БАН", "§c", new String[][]{{"Игрок", targetName}, {"Забанил", sender.getName()}, {"Срок", formatDuration(durationMs)}, {"Причина", reason}}, "§c§lБАН", "§f" + targetName + " §7на §f" + formatDuration(durationMs));
        logAction(sender.getName(), "ЗАБАНИЛ", targetName, "Срок: " + formatDuration(durationMs) + " | Причина: " + reason);
        savePunishmentsToConfig();
        return true;
    }

    // Красивая табличка при бане
    private String banScreen(String target, String banner, String reason, long duration, long expire) {
        return LINE + "\n" +
                "§c§l     ВЫ ЗАБАНЕНЫ\n" +
                LINE + "\n" +
                " §7Игрок: §f" + target + "\n" +
                " §7Забанил: §f" + banner + "\n" +
                " §7Причина: §f" + reason + "\n" +
                " §7Срок: §f" + formatDuration(duration) + "\n" +
                " §7Осталось: §f" + getRemainingTime(expire) + "\n" +
                LINE;
    }

    // ==================== /mute ====================
    private boolean handleMute(CommandSender sender, String[] args) {
        if (args.length < 2) { sender.sendMessage("§c/mute <ник> <срок> [причина]"); return true; }
        String targetName = args[0];
        long durationMs = parseDuration(args[1]);
        if (durationMs <= 0) { sender.sendMessage("§cНеверный срок. Примеры: 10d, 5h, 30m."); return true; }
        String reason = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "Не указана";
        long expireTime = System.currentTimeMillis() + durationMs;
        mutedPlayers.put(targetName.toLowerCase(), new P(expireTime, durationMs, reason, sender.getName()));
        Player target = getServer().getPlayer(targetName);
        if (target != null) target.sendMessage(LINE + "\n§e§l     ВЫ ЗАМУЧЕНЫ\n" + LINE + "\n §7Замутил: §f" + sender.getName() + "\n §7Срок: §f" + formatDuration(durationMs) + "\n §7Причина: §f" + reason + "\n" + LINE);
        announce("МУТ", "§e", new String[][]{{"Игрок", targetName}, {"Замутил", sender.getName()}, {"Срок", formatDuration(durationMs)}, {"Причина", reason}}, "§e§lМУТ", "§f" + targetName + " §7на §f" + formatDuration(durationMs));
        logAction(sender.getName(), "ЗАМУТИЛ", targetName, "Срок: " + formatDuration(durationMs) + " | Причина: " + reason);
        savePunishmentsToConfig();
        return true;
    }

    // ==================== /unban ====================
    private boolean handleUnban(CommandSender sender, String[] args) {
        if (args.length != 1) { sender.sendMessage("§c/unban <ник>"); return true; }
        boolean removed = bannedPlayers.remove(args[0].toLowerCase()) != null;
        try { getServer().getNameBans().remove(args[0]); } catch (Exception ignored) { }
        if (removed) {
            sender.sendMessage("§aИгрок " + args[0] + " разбанен.");
            logAction(sender.getName(), "РАЗБАНИЛ", args[0], "");
            savePunishmentsToConfig();
        } else sender.sendMessage("§cИгрок " + args[0] + " не в бан-листе.");
        return true;
    }

    // ==================== /unmute ====================
    private boolean handleUnmute(CommandSender sender, String[] args) {
        if (args.length != 1) { sender.sendMessage("§c/unmute <ник>"); return true; }
        if (mutedPlayers.remove(args[0].toLowerCase()) != null) {
            sender.sendMessage("§aИгрок " + args[0] + " размучен.");
            Player target = getServer().getPlayer(args[0]);
            if (target != null) target.sendMessage("§aВы были размучены.");
            logAction(sender.getName(), "РАЗМУТИЛ", args[0], "");
            savePunishmentsToConfig();
        } else sender.sendMessage("§cИгрок " + args[0] + " не в муте.");
        return true;
    }

    // ==================== /checklogs ====================
    private boolean handleLogs(CommandSender sender, String[] args) {
        int lines = 20;
        if (args.length == 1) { try { lines = Integer.parseInt(args[0]); } catch (NumberFormatException ignored) {} }
        if (!logFile.exists()) { sender.sendMessage("§cЛог-файл пуст."); return true; }
        try {
            List<String> all = java.nio.file.Files.readAllLines(logFile.toPath());
            int from = Math.max(0, all.size() - lines);
            sender.sendMessage("§6=== Последние " + (all.size() - from) + " действий ===");
            for (int i = from; i < all.size(); i++) sender.sendMessage("§7" + all.get(i));
        } catch (IOException e) { sender.sendMessage("§cОшибка: " + e.getMessage()); }
        return true;
    }

    // ==================== БЛОКИРОВКИ ====================
    @EventHandler
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        if (checkingPlayers.contains(event.getPlayer().getName())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cВо время проверки команды запрещены.");
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (checkingPlayers.contains(event.getPlayer().getName())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayerChat(PlayerChatEvent event) {
        String name = event.getPlayer().getName().toLowerCase();
        P p = mutedPlayers.get(name);
        if (p != null) {
            if (System.currentTimeMillis() < p.expire) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(LINE + "\n§e§l     ВЫ ЗАМУЧЕНЫ\n" + LINE + "\n §7Причина: §f" + p.reason + "\n §7Осталось: §f" + getRemainingTime(p.expire) + "\n" + LINE);
            } else mutedPlayers.remove(name);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        String name = event.getPlayer().getName();
        String lower = name.toLowerCase();
        Player p = event.getPlayer();

        // Проверка бана
        P ban = bannedPlayers.get(lower);
        if (ban != null) {
            if (System.currentTimeMillis() < ban.expire) {
                final String kickMessage = banScreen(name, ban.banner, ban.reason, ban.duration, ban.expire);
                getServer().getScheduler().scheduleDelayedTask(this, () -> {
                    Player target = getServer().getPlayer(name);
                    if (target != null) target.kick(kickMessage, false);
                }, 1);
                return;
            } else bannedPlayers.remove(lower);
        }

        // Если зашел ПРОВЕРЯЕМЫЙ (он в checkingPlayers)
        if (checkingPlayers.contains(name)) {
            Level checkLevel = getServer().getLevelByName(CHECK_WORLD);
            if (checkLevel == null) return;
            getServer().getScheduler().scheduleDelayedTask(this, () -> {
                Player target = getServer().getPlayer(name);
                if (target != null && checkingPlayers.contains(name)) {
                    target.teleport(new Position(CHECK_X, CHECK_Y, CHECK_Z, checkLevel));
                    target.setImmobile(true);
                    target.sendMessage(LINE + "\n§c§l     ВЫ ВСЁ ЕЩЁ НА ПРОВЕРКЕ\n" + LINE + "\n §7Ожидайте проверяющего\n" + LINE);
                    target.sendTitle("§cПроверка продолжается", "§eОжидайте проверяющего", 20, 80, 20);
                }
            }, 20);
        }

        // Если зашел ПРОВЕРЯЮЩИЙ (он есть в значениях checkerOfPlayer)
        if (checkerOfPlayer.containsValue(name)) {
            Level checkLevel = getServer().getLevelByName(CHECK_WORLD);
            if (checkLevel == null) return;
            getServer().getScheduler().scheduleDelayedTask(this, () -> {
                Player checker = getServer().getPlayer(name);
                if (checker != null) {
                    checker.teleport(new Position(CHECKER_X, CHECKER_Y, CHECKER_Z, checkLevel));
                    checker.sendMessage("§aВы вернулись на проверку. Таймер продолжен.");
                }
            }, 20);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        String name = event.getPlayer().getName();
        // Если проверяемый вышел без разрешения — бан
        if (checkingPlayers.contains(name)) {
            long durationMs = 14L * 86_400_000L;
            long expireTime = System.currentTimeMillis() + durationMs;
            String reason = "Отказ от проверки (выход без разрешения)";
            bannedPlayers.put(name.toLowerCase(), new P(expireTime, durationMs, reason, "Система"));
            getServer().getNameBans().addBan(name, reason, new Date(expireTime));
            checkingPlayers.remove(name);
            checkTimers.remove(name);
            checkerOfPlayer.remove(name);
            announce("БАН ЗА ОТКАЗ", "§4", new String[][]{{"Игрок", name}, {"Срок", "14д"}, {"Причина", reason}}, "§4§lОТКАЗ ОТ ПРОВЕРКИ", "§f" + name + " §7забанен на 14д");
            logAction("Система", "ЗАБАНИЛ (ОТКАЗ)", name, "14д | Выход без разрешения");
            savePunishmentsToConfig();
        }
    }

    // ==================== ВСПОМОГАТЕЛЬНЫЕ ====================
    private void announce(String header, String color, String[][] fields, String title, String subtitle) {
        StringBuilder sb = new StringBuilder();
        sb.append(LINE).append("\n").append(color).append("§l     ").append(header).append("\n").append(LINE).append("\n");
        for (String[] f : fields) sb.append(" §7").append(f[0]).append(": §f").append(f[1]).append("\n");
        sb.append(LINE);
        getServer().broadcastMessage(sb.toString());
        for (Player p : getServer().getOnlinePlayers().values()) p.sendTitle(title, subtitle, 10, 60, 10);
    }

    private void logAction(String who, String action, String target, String details) {
        String line = "[" + dateFormat.format(new Date()) + "] " + who + " " + action + " " + target + (details.isEmpty() ? "" : " | " + details);
        getLogger().info("[LOG] " + line);
        try (FileWriter fw = new FileWriter(logFile, true)) { fw.write(line + System.lineSeparator()); }
        catch (IOException e) { getLogger().error("Ошибка лога: " + e.getMessage()); }
    }

    private long parseDuration(String input) {
        Pattern pattern = Pattern.compile("(\\d+)([dhms])");
        Matcher matcher = pattern.matcher(input.toLowerCase());
        long totalMs = 0; boolean found = false;
        while (matcher.find()) {
            found = true;
            long value = Long.parseLong(matcher.group(1));
            switch (matcher.group(2)) {
                case "d": totalMs += value * 86_400_000L; break;
                case "h": totalMs += value * 3_600_000L; break;
                case "m": totalMs += value * 60_000L; break;
                case "s": totalMs += value * 1_000L; break;
            }
        }
        return found ? totalMs : 0;
    }

    private String formatDuration(long ms) {
        long days = ms / 86_400_000L; ms %= 86_400_000L;
        long hours = ms / 3_600_000L; ms %= 3_600_000L;
        long minutes = ms / 60_000L;
        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("д ");
        if (hours > 0) sb.append(hours).append("ч ");
        if (minutes > 0) sb.append(minutes).append("м");
        return sb.toString().trim().isEmpty() ? "0м" : sb.toString().trim();
    }

    private String getRemainingTime(long expireTimestamp) {
        long remaining = expireTimestamp - System.currentTimeMillis();
        return remaining <= 0 ? "0м" : formatDuration(remaining);
    }

    private void loadPunishmentsFromConfig() {
        if (punishConfig.isSection("bans")) {
            for (String key : punishConfig.getSection("bans").getKeys(false)) {
                String[] parts = punishConfig.getString("bans." + key, "").split("\\|", 4);
                if (parts.length == 4) { try { bannedPlayers.put(key, new P(Long.parseLong(parts[0]), Long.parseLong(parts[1]), parts[3], parts[2])); } catch (NumberFormatException ignored) {} }
            }
        }
        if (punishConfig.isSection("mutes")) {
            for (String key : punishConfig.getSection("mutes").getKeys(false)) {
                String[] parts = punishConfig.getString("mutes." + key, "").split("\\|", 4);
                if (parts.length == 4) { try { mutedPlayers.put(key, new P(Long.parseLong(parts[0]), Long.parseLong(parts[1]), parts[3], parts[2])); } catch (NumberFormatException ignored) {} }
            }
        }
    }

    private void savePunishmentsToConfig() {
        punishConfig.set("bans", null);
        for (Map.Entry<String, P> e : bannedPlayers.entrySet()) {
            P p = e.getValue();
            punishConfig.set("bans." + e.getKey(), p.expire + "|" + p.duration + "|" + p.banner + "|" + p.reason);
        }
        punishConfig.set("mutes", null);
        for (Map.Entry<String, P> e : mutedPlayers.entrySet()) {
            P p = e.getValue();
            punishConfig.set("mutes." + e.getKey(), p.expire + "|" + p.duration + "|" + p.banner + "|" + p.reason);
        }
        punishConfig.save();
    }
}