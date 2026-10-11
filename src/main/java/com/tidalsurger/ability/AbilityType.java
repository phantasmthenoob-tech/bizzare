package com.tidalsurger.ability;

/**
 * The two Tidal Surger abilities and their config keys.
 */
public enum AbilityType {

    TIDAL_SURF("tidal-surf", "Tidal Surf", "tidalsurger.ability.surf"),
    TIDAL_CORE("tidal-core", "Tidal Core", "tidalsurger.ability.core");

    private final String configKey;
    private final String displayName;
    private final String defaultPermission;

    AbilityType(String configKey, String displayName, String defaultPermission) {
        this.configKey = configKey;
        this.displayName = displayName;
        this.defaultPermission = defaultPermission;
    }

    public String configKey() {
        return configKey;
    }

    public String displayName() {
        return displayName;
    }

    public String defaultPermission() {
        return defaultPermission;
    }
}
