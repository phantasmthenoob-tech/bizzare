package com.tidalsurger.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Small MiniMessage helper with a legacy ampersand fallback so a malformed
 * config string never breaks the plugin.
 */
public final class Text {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final LegacyComponentSerializer LEGACY_SECTION = LegacyComponentSerializer.legacySection();

    private Text() {
    }

    public static Component mm(String raw) {
        return mm(raw, true);
    }

    /**
     * @param italicsOff when true the root component forbids the italic decoration
     *                   (MiniMessage does not italicise by default, legacy codes do).
     */
    public static Component mm(String raw, boolean italicsOff) {
        if (raw == null || raw.isEmpty()) {
            return Component.empty();
        }
        Component component;
        try {
            component = MINI.deserialize(raw);
        } catch (Throwable bad) {
            component = LEGACY.deserialize(raw);
        }
        return italicsOff ? component.decoration(TextDecoration.ITALIC, false) : component;
    }

    /**
     * MiniMessage (or ampersand fallback) rendered into a legacy section-code
     * string, for APIs that still take plain strings - the BossBar title.
     */
    public static String legacy(String raw) {
        return LEGACY_SECTION.serialize(mm(raw));
    }

    public static String plain(String raw) {
        if (raw == null) {
            return "";
        }
        try {
            return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                    .serialize(MINI.deserialize(raw));
        } catch (Throwable bad) {
            return raw;
        }
    }
}
