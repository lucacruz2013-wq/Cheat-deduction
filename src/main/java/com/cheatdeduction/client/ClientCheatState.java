package com.cheatdeduction.client;

import com.cheatdeduction.Cheat;
import javax.annotation.Nullable;

/** Cheat do jogador local, recebido do servidor via SyncCheatPacket. */
public final class ClientCheatState {
    @Nullable private static Cheat current;

    private ClientCheatState() {}

    @Nullable public static Cheat get() { return current; }
    public static void set(@Nullable Cheat cheat) { current = cheat; }
}
