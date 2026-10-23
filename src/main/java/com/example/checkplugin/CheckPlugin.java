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
import cn.nukkit.level.Level;
import cn.nukkit.level.Position;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.utils.Config;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CheckPlugin extends PluginBase implements Listener {

    private final Set<String> checkingPlayers = ConcurrentHashMap.newKeySet();
    private static final String CHECK_WORLD = "dowtest";
    private static final double CHECK_X = -2, CHECK_Y = 98, CHECK_Z = -1;
    private static final String LINE = "§8§m──────────────────────────────";

    private static class P {
        long expire, duration;
        String reason, banner;
        P(long e, long d, String r, String b) { expire = e; duration = d; reason = r; banner = b; }
    }

    private final Map<String, P> mutedPlayers = new HashMap<>();
    private final Map<String, P> bannedPlayers = new HashMap<>();
    private Config punishConfig;
    private File punishFile, logFile;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    @Override
    public void onEnable() {
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
            default:         return false;
        }
    }

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
                if (target != null) { target.setImmobile(false); target.sendMessage("§aПроверка завершена."); }
                sender.sendMessage("§aИгрок " + targetName + " снят с проверки.");
                logAction(sender.getName(), "СНЯЛ С ПРОВЕРКИ", targetName, "");
                announce("СНЯТ С ПРОВЕРКИ", "§a", new String[][]{{"Игрок", targetName}, {"Снял", sender.getName()}}, "§a§lСНЯТ С ПРОВЕРКИ", "§f" + targetName);
            } else sender.sendMessage("§cИгрок " + targetName + " не на проверке.");
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("leave")) {
            String targetName = args[1];
            if (checkingPlayers.remove(targetName)) {
                sender.sendMessage("§aИгроку " + targetName + " разрешено выйти без бана.");
                logAction(sender.getName(), "РАЗРЕШИЛ ВЫХОД", targetName, "");
            } else sender.sendMessage("§cИгрок " + targetName + " не на проверке.");
            return true;
        }
        if (args.length == 1) {
            Player target = getServer().getPlayer(args[0]);
            if (target == null) { sender.sendMessage("§cИгрок " + args[0] + " не найден."); return true; }
            Level checkLevel = getServer().getLevelByName(CHECK_WORLD);
            if (checkLevel == null && getServer().loadLevel(CHECK_WORLD)) checkLevel = getServer().getLevelByName(CHECK_WORLD);
            if (checkLevel == null) { sender.sendMessage("§cМир '" + CHECK_WORLD + "' не существует!"); return true; }
            target.teleport(new Position(CHECK_X, CHECK_Y, CHECK_Z, checkLevel));
            target.setImmobile(true);
            checkingPlayers.add(target.getName());
            target.sendMessage(LINE);
            target.sendMessage("§c§l     ВЫ ВЫЗВАНЫ НА ПРОВЕРКУ");
            target.sendMessage(LINE);
            target.sendMessage("§7Проверяющий: §f" + sender.getName());
            target.sendMessage("");
            target.sendMessage("§eУ вас есть §c3 минуты§e, чтобы предоставить доказательства.");
            target.sendMessage("§eЕсли Discord нет — §c8 минут§e на скачивание.");
            target.sendMessage("§eВыход — §cстрого с разрешения проверяющего§e.");
            target.sendMessage("§eЕсли выйдете без разрешения — §cбан 14 дней§e.");
            target.sendMessage("");
            target.sendMessage("§7Ожидайте. Не двигайтесь.");
            target.sendMessage(LINE);
            target.sendTitle("§cВы вызваны на проверку", "§eПроверяющий: §f" + sender.getName(), 20, 100, 20);
            sender.sendMessage("§aИгрок " + target.getName() + " телепортирован на проверку.");
            logAction(sender.getName(), "ВЫЗВАЛ НА ПРОВЕРКУ", target.getName(), "");
            announce("ВЫЗОВ НА ПРОВЕРКУ", "§c", new String[][]{{"Игрок", target.getName()}, {"Проверяющий", sender.getName()}}, "§c§lПРОВЕРКА", "§f" + target.getName() + " §7вызван");
            return true;
        }
        sender.sendMessage("§cНеверное использование. /check для справки.");
        return true;
    }

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
        if (target != null) target.kick(LINE + "\n§c§l     ВЫ ЗАБАНЕНЫ\n" + LINE + "\n §7Забанил: §f" + sender.getName() + "\n §7Срок: §f" + formatDuration(durationMs) + "\n §7Причина: §f" + reason + "\n" + LINE, false);
        announce("БАН", "§c", new String[][]{{"Игрок", targetName}, {"Забанил", sender.getName()}, {"Срок", formatDuration(durationMs)}, {"Причина", reason}}, "§c§lБАН", "§f" + targetName + " §7на §f" + formatDuration(durationMs));
        logAction(sender.getName(), "ЗАБАНИЛ", targetName, "Срок: " + formatDuration(durationMs) + " | Причина: " + reason);
        savePunishmentsToConfig();
        return true;
    }

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

    private boolean handleUnban(CommandSender sender, String[] args) {
        if (args.length != 1) { sender.sendMessage("§c/unban <ник>"); return true; }
        boolean removed = bannedPlayers.remove(args[0].toLowerCase()) != null;
        try { getServer().getNameBans().remove(args[0]); } catch (Exception ignored) { }
        if (removed) {
            announce("РАЗБАН", "§a", new String[][]{{"Игрок", args[0]}, {"Разбанил", sender.getName()}}, "§a§lРАЗБАН", "§f" + args[0]);
            logAction(sender.getName(), "РАЗБАНИЛ", args[0], "");
            savePunishmentsToConfig();
        } else sender.sendMessage("§cИгрок " + args[0] + " не в бан-листе.");
        return true;
    }

    private boolean handleUnmute(CommandSender sender, String[] args) {
        if (args.length != 1) { sender.sendMessage("§c/unmute <ник>"); return true; }
        if (mutedPlayers.remove(args[0].toLowerCase()) != null) {
            announce("РАЗМУТ", "§a", new String[][]{{"Игрок", args[0]}, {"Размутил", sender.getName()}}, "§a§lРАЗМУТ", "§f" + args[0]);
            Player target = getServer().getPlayer(args[0]);
            if (target != null) target.sendMessage("§aВы были размучены.");
            logAction(sender.getName(), "РАЗМУТИЛ", args[0], "");
            savePunishmentsToConfig();
        } else sender.sendMessage("§cИгрок " + args[0] + " не в муте.");
        return true;
    }

    private boolean handleLogs(CommandSender sender, String[] args) {
        int lines = 20;
        if (args.length == 1) { try { lines = Integer.parseInt(args[0]); } catch (NumberFormatException ignored) {} }
        if (!logFile.exists()) { sender.sendMessage("§cЛог-файл пуст."); return true; }
        try {
            java.util.List<String> all = java.nio.file.Files.readAllLines(logFile.toPath());
            int from = Math.max(0, all.size() - lines);
            sender.sendMessage("§6=== Последние " + (all.size() - from) + " действий ===");
            for (int i = from; i < all.size(); i++) sender.sendMessage("§7" + all.get(i));
        } catch (IOException e) { sender.sendMessage("§cОшибка: " + e.getMessage()); }
        return true;
    }

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
        P ban = bannedPlayers.get(lower);
        if (ban != null) {
            if (System.currentTimeMillis() < ban.expire) {
                event.getPlayer().kick(LINE + "\n§c§l     ВЫ ЗАБАНЕНЫ\n" + LINE + "\n §7Забанил: §f" + ban.banner + "\n §7Срок: §f" + formatDuration(ban.duration) + "\n §7Причина: §f" + ban.reason + "\n §7Осталось: §f" + getRemainingTime(ban.expire) + "\n" + LINE, false);
                return;
            } else bannedPlayers.remove(lower);
        }
        if (checkingPlayers.contains(name)) {
            Level checkLevel = getServer().getLevelByName(CHECK_WORLD);
            if (checkLevel == null) return;
            getServer().getScheduler().scheduleDelayedTask(this, () -> {
                Player p = getServer().getPlayer(name);
                if (p != null && checkingPlayers.contains(name)) {
                    p.teleport(new Position(CHECK_X, CHECK_Y, CHECK_Z, checkLevel));
                    p.setImmobile(true);
                    p.sendMessage(LINE + "\n§c§l     ВЫ ВСЁ ЕЩЁ НА ПРОВЕРКЕ\n" + LINE + "\n §7Выход без разрешения = §cбан 14 дней\n" + LINE);
                    p.sendTitle("§cПроверка продолжается", "§eОжидайте проверяющего", 20, 80, 20);
                }
            }, 20);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        String name = event.getPlayer().getName();
        if (checkingPlayers.contains(name)) {
            long durationMs = 14L * 86_400_000L;
            long expireTime = System.currentTimeMillis() + durationMs;
            String reason = "Отказ от проверки (выход без разрешения)";
            bannedPlayers.put(name.toLowerCase(), new P(expireTime, durationMs, reason, "Система"));
            getServer().getNameBans().addBan(name, reason, new Date(expireTime));
            checkingPlayers.remove(name);
            announce("БАН ЗА ОТКАЗ", "§4", new String[][]{{"Игрок", name}, {"Срок", "14д"}, {"Причина", reason}}, "§4§lОТКАЗ ОТ ПРОВЕРКИ", "§f" + name + " §7забанен на 14д");
            logAction("Система", "ЗАБАНИЛ (ОТКАЗ)", name, "14д | Выход без разрешения");
            savePunishmentsToConfig();
        }
    }

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