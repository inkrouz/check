package com.example.checkplugin;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerCommandPreprocessEvent;
import cn.nukkit.event.player.PlayerMoveEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.level.Level;
import cn.nukkit.level.Position;
import cn.nukkit.plugin.PluginBase;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class CheckPlugin extends PluginBase implements Listener {

    private final Set<String> checkingPlayers = ConcurrentHashMap.newKeySet();
    private static final String CHECK_WORLD = "dowtest";
    private static final double CHECK_X = -2;
    private static final double CHECK_Y = 98;
    private static final double CHECK_Z = -1;

    @Override
    public void onEnable() {
        this.getServer().getPluginManager().registerEvents(this, this);
        this.getLogger().info("CheckPlugin включен! Система проверок активна.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("check")) {
            return false;
        }

        // Снятие с проверки: /check done <ник>
        if (args.length == 2 && args[0].equalsIgnoreCase("done")) {
            String targetName = args[1];
            if (checkingPlayers.remove(targetName)) {
                Player target = this.getServer().getPlayer(targetName);
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

        // Вызов на проверку: /check <ник>
        if (args.length != 1) {
            sender.sendMessage("§cИспользование: /check <ник>  или  /check done <ник>");
            return true;
        }

        Player target = this.getServer().getPlayer(args[0]);
        if (target == null) {
            sender.sendMessage("§cИгрок " + args[0] + " не найден.");
            return true;
        }

        String checkerName = sender.getName();

        // 1. Получаем или загружаем мир dowtest
        Server server = this.getServer();
        Level checkLevel = server.getLevelByName(CHECK_WORLD);

        if (checkLevel == null) {
            if (server.loadLevel(CHECK_WORLD)) {
                checkLevel = server.getLevelByName(CHECK_WORLD);
            }
        }

        if (checkLevel == null) {
            sender.sendMessage("§cМир '" + CHECK_WORLD + "' не существует или не может быть загружен!");
            this.getLogger().error("Не удалось загрузить мир: " + CHECK_WORLD);
            return true;
        }

        // 2. Телепортируем в мир dowtest на координаты -2 98 -1
        Position checkPos = new Position(CHECK_X, CHECK_Y, CHECK_Z, checkLevel);
        target.teleport(checkPos);

        // 3. Замораживаем игрока (блокируем движение)
        target.setImmobile(true);

        // 4. Добавляем в список проверяемых (блокирует команды)
        checkingPlayers.add(target.getName());

        // 5. Показываем титул на экране
        target.sendTitle(
                "§cВы вызваны на проверку",
                "§eПроверяющий: §f" + checkerName,
                20, 100, 20
        );

        target.sendMessage("§eВы вызваны на проверку. Ожидайте модератора.");
        sender.sendMessage("§aИгрок " + target.getName() + " телепортирован на проверку в мир " + CHECK_WORLD + ".");

        return true;
    }

    // Блокируем команды для игроков на проверке
    @EventHandler
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        if (checkingPlayers.contains(event.getPlayer().getName())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cВо время проверки использование команд запрещено.");
        }
    }

    // Дополнительная страховка: отменяем движение, если игрок попытается сдвинуться
    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (checkingPlayers.contains(event.getPlayer().getName())) {
            event.setCancelled(true);
        }
    }

    // Если игрок вышел во время проверки — убираем из списка
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        checkingPlayers.remove(event.getPlayer().getName());
    }
}