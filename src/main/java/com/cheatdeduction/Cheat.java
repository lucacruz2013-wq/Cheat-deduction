package com.cheatdeduction;

import java.util.Arrays;
import java.util.List;
import javax.annotation.Nullable;

public enum Cheat {
    SPEED("speed", "Speed"),
    INVISIBLE("invisible", "Invisible"),
    FALLDAMAGE("falldamage", "Fall Damage"),
    KILLBOT("killbot", "Kill Bot"),
    MINING("mining", "Mining"),
    FLY("fly", "Fly"),
    AIM_LOCK("aimlock", "Aim Lock"),
    NO_MISS("nomiss", "No Miss");

    public static final Cheat[] VALUES = values();

    private final String commandName;
    private final String displayName;

    Cheat(String commandName, String displayName) {
        this.commandName = commandName;
        this.displayName = displayName;
    }

    public String commandName() { return commandName; }
    public String displayName() { return displayName; }

    @Nullable
    public static Cheat byId(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : null;
    }

    @Nullable
    public static Cheat byCommandName(String name) {
        for (Cheat c : VALUES) {
            if (c.commandName.equalsIgnoreCase(name)) return c;
        }
        return null;
    }

    public static List<String> commandNames() {
        return Arrays.stream(VALUES).map(Cheat::commandName).toList();
    }
}
