package com.example.checkplugin;

import cn.nukkit.Player;
import cn.nukkit.Server;
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

    // --- Данные о проверках ---
    private final Set<String> checkingPlayers = ConcurrentHashMap.newKeySet();
    private static final String CHECK_WORLD = "dowtest";
    private static final double CHECK_X = -2, CHECK_Y = 98, CHECK_Z = -1;

    // --- Данные о наказаниях ---
    // Ключ: имя игрока (в нижнем регистре). Значение: время окончания наказания (timestamp в мс).
    private final Map<String, Long> mutedPlayers = new HashMap<>();
    private final Map<String, Long> bannedPlayers = new HashMap<>();
    private Config punishConfig;
    private File punishFile;

    @Override
    public void onEnable() {
        // Загружаем сохранённые наказания
        punishFile = new File(getDataFolder(), "punishments.yml");
        if (!punishFile.exists()) {
            punishFile.getParentFile().mkdirs();
            try {
                punishFile.createNewFile();
            } catch (Exception e) {
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

    // ==================== ОСНОВНАЯ КОМАНДА /check ====================
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("check")) {
            return false;
        }
        if (args.length == 0) {
            sender.sendMessage("§cИспользование: /check <ник> | /check done <ник> | /check ban <ник> <срок> [причина] | /check mute <ник> <срок> [причина]");
            return true;
        }

        // /check done <ник>
        if (args.length == 2 && args[0].equalsIgnoreCase("done")) {
            return handleCheckDone(sender, args[1]);
        }
        // /check ban <ник> <срок> [причина]
        if (args[0].equalsIgnoreCase("ban") && args.length >= 3) {
            return handlePunishCommand(sender, args, true);
        }
        // /check mute <ник> <срок> [причина]
        if (args[0].equalsIgnoreCase("mute") && args.length >= 3) {
            return handlePunishCommand(sender, args, false);
        }
        // /check <ник>
        if (args.length == 1) {
            return handleCallToCheck(sender, args[0]);
        }

        sender.sendMessage("§cНеверное использование. Введите /check для справки.");
        return true;
    }

    // ==================== ВЫЗОВ НА ПРОВЕРКУ ====================
    private boolean handleCallToCheck(CommandSender sender, String targetName) {
        Player target = getServer().getPlayer(targetName);
        if (target == null) {
            sender.sendMessage("§cИгрок " + targetName + " не найден.");
            return true;
        }

        String checkerName = sender.getName();
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

        target.sendTitle("§cВы вызваны на проверку", "§eПроверяющий: §f" + checkerName, 20, 100, 20);
        target.sendMessage("§eВы вызваны на проверку. Ожидайте модератора.");
        sender.sendMessage("§aИгрок " + target.getName() + " телепортирован на проверку.");
        return true;
    }

    // ==================== СНЯТИЕ С ПРОВЕРКИ ====================
    private boolean handleCheckDone(CommandSender sender, String targetName) {
        if (checkingPlayers.remove(targetName)) {
            Player target = getServer().getPlayer(targetName);
            if (target != null) {
                target.setImmobile(false);
                target.sendMessage("§aПроверка завершена. Вы снова можете двигаться и использовать команды.");
            }
            sender.sendMessage("§aИгрок " + targetName + " снят с проверки.");
        } else {
            sender.sendMessage("§cИгрок " + targetName + " не находится на проверке.");
        }
        return true;
    }

    // ==================== ВЫДАЧА НАКАЗАНИЯ (БАН/МУТ) ====================
    private boolean handlePunishCommand(CommandSender sender, String[] args, boolean isBan) {
        String targetName = args[1];
        String durationStr = args[2];
        String reason = args.length > 3 ? String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length)) : "Не указана";

        long durationMs = parseDuration(durationStr);
        if (durationMs <= 0) {
            sender.sendMessage("§cНеверный формат срока. Примеры: 10d (10 дней), 5h (5 часов), 30m (30 минут).");
            return true;
        }

        long expireTime = System.currentTimeMillis() + durationMs;
        String key = targetName.toLowerCase();

        if (isBan) {
            bannedPlayers.put(key, expireTime);
            Player target = getServer().getPlayer(targetName);
            if (target != null) {
                target.kick("§cВы забанены. Причина: " + reason + " §7Срок: " + formatDuration(durationMs), false);
            }
            getServer().getBanList().addBan(targetName, reason, new java.util.Date(expireTime)); // Дополнительная запись в бан-лист сервера
            sender.sendMessage("§cИгрок " + targetName + " забанен на " + formatDuration(durationMs) + ". Причина: " + reason);
        } else {
            mutedPlayers.put(key, expireTime);
            sender.sendMessage("§eИгрок " + targetName + " замучен на " + formatDuration(durationMs) + ". Причина: " + reason);
            Player target = getServer().getPlayer(targetName);
            if (target != null) target.sendMessage("§cВы замучены на " + formatDuration(durationMs) + ". Причина: " + reason);
        }
        savePunishmentsToConfig();
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
                mutedPlayers.remove(name); // Срок истёк
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
                bannedPlayers.remove(name); // Срок истёк
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        checkingPlayers.remove(event.getPlayer().getName());
    }

    // ==================== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ====================
    private long parseDuration(String input) {
        // Поддерживает форматы: 10d, 5h, 30m, 45s. Можно комбинировать: 1d12h.
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
        return sb.toString().trim();
    }

    private String getRemainingTime(long expireTimestamp) {
        long remaining = expireTimestamp - System.currentTimeMillis();
        if (remaining <= 0) return "0м";
        return formatDuration(remaining);
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