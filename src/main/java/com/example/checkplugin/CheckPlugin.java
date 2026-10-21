package com.example.checkplugin;

import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.plugin.PluginBase;

public class CheckPlugin extends PluginBase {

    @Override
    public void onEnable() {
        this.getLogger().info("CheckPlugin включен!");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("check")) {
            return false;
        }
        if (args.length != 1) {
            sender.sendMessage("§cИспользование: /check <ник>");
            return true;
        }
        Player target = this.getServer().getPlayer(args[0]);
        if (target == null) {
            sender.sendMessage("§cИгрок " + args[0] + " не найден.");
            return true;
        }
        target.teleport(target.getLevel().getSafeSpawn());
        target.sendMessage("§eВы вызваны на проверку. Ожидайте модератора.");
        sender.sendMessage("§aИгрок " + target.getName() + " телепортирован.");
        return true;
    }
}