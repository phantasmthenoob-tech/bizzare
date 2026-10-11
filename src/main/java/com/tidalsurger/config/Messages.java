package com.tidalsurger.config;

import com.tidalsurger.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Reads configurable messages and sends them with an optional prefix.
 */
public final class Messages {

    private final JavaPlugin plugin;

    public Messages(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public Component build(String key, String... placeholders) {
        FileConfiguration config = plugin.getConfig();
        String raw = config.getString("messages." + key, "<red>Missing message: " + key);
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            raw = raw.replace("%" + placeholders[i] + "%", placeholders[i + 1]);
        }
        boolean usePrefix = config.getBoolean("messages.use-prefix", true);
        if (usePrefix && !key.equals("info-header") && !key.equals("info-surf")
                && !key.equals("info-core") && !key.equals("info-usage")) {
            raw = config.getString("messages.prefix", "") + raw;
        }
        return Text.mm(raw);
    }

    public void send(CommandSender sender, String key, String... placeholders) {
        sender.sendMessage(build(key, placeholders));
    }
}
