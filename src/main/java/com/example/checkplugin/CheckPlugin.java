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

    private final Map<String, Long> mutedPlayers = new HashMap<>();
    private final Map<String, Long> bannedPlayers = new HashMap<>();
    private Config punishConfig;
    private File punishFile;

    @Override
    public void onEnable() {
        punishFile = new File(getDataFolder(), "punishments.yml");
        if (!punishFile.exists()) {
            punishFile.getParentFile().mkdirs();
            try { punishFile.createNewFile(); } catch (Exception e) {
                getLogger().error("Не удалось создать файл наказаний!", e);
            }
        }
        punishConfig = new Config(punishFile, Config.YAML);
        loadPunishmentsFromConfig();
        this.getServer().getPluginManager().registerEvents(this, this);
        this.getLogger().info("CheckPlugin включен! Система наказаний активна.");
    }

    @Override
    public void onDisable() {
        savePunishmentsToConfig();
    }

    // ==================== РАСПРЕДЕЛЕНИЕ КОМАНД ====================
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmd = command.getName().toLowerCase();
        switch (cmd) {
            case "check": return handleCheck(sender, args);
            case "ban":   return handleBan(sender, args);
            case "mute":  return handleMute(sender, args);
            case "unban": return handleUnban(sender, args);
            case "unmute":return handleUnmute(sender, args);
            default: return false;
        }
    }

    // ==================== /check ====================
    private boolean handleCheck(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§cИспользование: /check <ник> | /check done <ник>");
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("done")) {
            if (checkingPlayers.remove(args[1])) {
                Player target = getServer().getPlayer(args[1]);
                if (target != null) {
                    target.setImmobile(false);
                    target.sendMessage("§aПроверка завершена. Вы снова можете двигаться и использовать команды.");
                }
                sender.sendMessage("§aИгрок " + args[1] + " снят с проверки.");
            } else {
                sender.sendMessage("§cИгрок " + args[1] + " не находится на проверке.");
            }
            return true;
        }
        if (args.length == 1) {
            Player target = getServer().getPlayer(args[0]);
            if (target == null) {
                sender.sendMessage("§cИгрок " + args[0] + " не найден.");
                return true;
            }
            Level checkLevel = getServer().getLevelByName(CHECK_WORLD);
            if (checkLevel == null && getServer().loadLevel(CHECK_WORLD)) {
                checkLevel = getServer().getLevelByName(CHECK_WORLD);
            }
            if (checkLevel == null) {
                sender.sendMessage("§cМир '" + CHECK_WORLD + "' не существует!");
                return true;
            }
            target.teleport(new Position(CHECK_X, CHECK_Y, CHECK_Z, checkLevel));
            target.setImmobile(true);
            checkingPlayers.add(target.getName());
            target.sendTitle("§cВы вызваны на проверку", "§eПроверяющий: §f" + sender.getName(), 20, 100, 20);
            target.sendMessage("§eВы вызваны на проверку. Ожидайте модератора.");
            sender.sendMessage("§aИгрок " + target.getName() + " телепортирован на проверку.");
            return true;
        }
        sender.sendMessage("§cНеверное использование. /check <ник> | /check done <ник>");
        return true;
    }

    // ==================== /ban <ник> <срок> [причина] ====================
    private boolean handleBan(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cИспользование: /ban <ник> <срок> [причина]  (пример: /ban Steve 10d Читы)");
            return true;
        }
        String targetName = args[0];
        long durationMs = parseDuration(args[1]);
        if (durationMs <= 0) {
            sender.sendMessage("§cНеверный срок. Примеры: 10d, 5h, 30m, 1d12h.");
            return true;
        }
        String reason = args.length > 2 ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)) : "Не указана";
        long expireTime = System.currentTimeMillis() + durationMs;
        bannedPlayers.put(targetName.toLowerCase(), expireTime);
        getServer().getNameBans().addBan(targetName, reason, new java.util.Date(expireTime));

        Player target = getServer().getPlayer(targetName);
        if (target != null) {
            target.kick("§cВы забанены.\n§7Причина: " + reason + "\n§7Срок: " + formatDuration(durationMs));
        }
        sender.sendMessage("§cИгрок " + targetName + " забанен на " + formatDuration(durationMs) + ". Причина: " + reason);
        savePunishmentsToConfig();
        return true;
    }

    // ==================== /mute <ник> <срок> [причина] ====================
    private boolean handleMute(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cИспользование: /mute <ник> <срок> [причина]  (пример: /mute Steve 5h Оскорбления)");
            return true;
        }
        String targetName = args[0];
        long durationMs = parseDuration(args[1]);
        if (durationMs <= 0) {
            sender.sendMessage("§cНеверный срок. Примеры: 10d, 5h, 30m, 1d12h.");
            return true;
        }
        String reason = args.length > 2 ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)) : "Не указана";
        long expireTime = System.currentTimeMillis() + durationMs;
        mutedPlayers.put(targetName.toLowerCase(), expireTime);

        Player target = getServer().getPlayer(targetName);
        if (target != null) {
            target.sendMessage("§cВы замучены на " + formatDuration(durationMs) + ". Причина: " + reason);
        }
        sender.sendMessage("§eИгрок " + targetName + " замучен на " + formatDuration(durationMs) + ". Причина: " + reason);
        savePunishmentsToConfig();
        return true;
    }

    // ==================== /unban <ник> ====================
    private boolean handleUnban(CommandSender sender, String[] args) {
        if (args.length != 1) {
            sender.sendMessage("§cИспользование: /unban <ник>");
            return true;
        }
        String key = args[0].toLowerCase();
        boolean removedFromMap = bannedPlayers.remove(key) != null;
        try {
            getServer().getNameBans().removeBan(args[0]);
        } catch (Exception ignored) { }

        if (removedFromMap) {
            sender.sendMessage("§aИгрок " + args[0] + " разбанен.");
            savePunishmentsToConfig();
        } else {
            sender.sendMessage("§cИгрок " + args[0] + " не находится в бан-листе плагина.");
        }
        return true;
    }

    // ==================== /unmute <ник> ====================
    private boolean handleUnmute(CommandSender sender, String[] args) {
        if (args.length != 1) {
            sender.sendMessage("§cИспользование: /unmute <ник>");
            return true;
        }
        String key = args[0].toLowerCase();
        if (mutedPlayers.remove(key) != null) {
            sender.sendMessage("§aИгрок " + args[0] + " размучен.");
            Player target = getServer().getPlayer(args[0]);
            if (target != null) target.sendMessage("§aВы были размучены. Можете снова писать в чат.");
            savePunishmentsToConfig();
        } else {
            sender.sendMessage("§cИгрок " + args[0] + " не находится в муте.");
        }
        return true;
    }

    // ==================== БЛОКИРОВКА КОМАНД И ДВИЖЕНИЯ НА ПРОВЕРКЕ ====================
    @EventHandler
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        if (checkingPlayers.contains(event.getPlayer().getName())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cВо время проверки использование команд запрещено.");
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (checkingPlayers.contains(event.getPlayer().getName())) {
            event.setCancelled(true);
        }
    }

    // ==================== БЛОКИРОВКА ЧАТА ДЛЯ ЗАМУЧЕННЫХ ====================
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayerChat(PlayerChatEvent event) {
        String name = event.getPlayer().getName().toLowerCase();
        Long expire = mutedPlayers.get(name);
        if (expire != null) {
            if (System.currentTimeMillis() < expire) {
                event.setCancelled(true);
                event.getPlayer().sendMessage("§cВы замучены. Осталось: " + getRemainingTime(expire));
            } else {
                mutedPlayers.remove(name);
            }
        }
    }

    // ==================== ПРОВЕРКА БАНА ПРИ ВХОДЕ ====================
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        String name = event.getPlayer().getName().toLowerCase();
        Long expire = bannedPlayers.get(name);
        if (expire != null) {
            if (System.currentTimeMillis() < expire) {
                event.getPlayer().kick("§cВы забанены. Осталось: " + getRemainingTime(expire));
            } else {
                bannedPlayers.remove(name);
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        checkingPlayers.remove(event.getPlayer().getName());
    }

    // ==================== УТИЛИТЫ ====================
    private long parseDuration(String input) {
        Pattern pattern = Pattern.compile("(\\d+)([dhms])");
        Matcher matcher = pattern.matcher(input.toLowerCase());
        long totalMs = 0;
        boolean found = false;
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
                bannedPlayers.put(key, punishConfig.getLong("bans." + key));
            }
        }
        if (punishConfig.isSection("mutes")) {
            for (String key : punishConfig.getSection("mutes").getKeys(false)) {
                mutedPlayers.put(key, punishConfig.getLong("mutes." + key));
            }
        }
    }

    private void savePunishmentsToConfig() {
        punishConfig.set("bans", bannedPlayers);
        punishConfig.set("mutes", mutedPlayers);
        punishConfig.save();
    }
}