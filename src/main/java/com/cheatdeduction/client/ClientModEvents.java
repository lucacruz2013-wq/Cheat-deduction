package com.cheatdeduction.client;

import com.cheatdeduction.CheatDeductionMod;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = CheatDeductionMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ClientModEvents {
    /** Tecla Z: liga/desliga o X-Ray do cheat MINING. */
    public static final KeyMapping MINING_KEY = new KeyMapping(
            "key.cheatdeduction.mining", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Z, "key.categories.cheatdeduction");

    private ClientModEvents() {}

    @SubscribeEvent
    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(MINING_KEY);
    }
}
