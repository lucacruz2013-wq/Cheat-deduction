package com.cheatdeduction;

import com.cheatdeduction.network.ModNetwork;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * Mod principal. Os demais handlers se registram sozinhos via @Mod.EventBusSubscriber
 * (CommonEvents, ModCommands, ClientEvents, ClientModEvents).
 */
@Mod(CheatDeductionMod.MODID)
public class CheatDeductionMod {
    public static final String MODID = "cheatdeduction";

    public CheatDeductionMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(this::commonSetup);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(ModNetwork::register);
    }
}
