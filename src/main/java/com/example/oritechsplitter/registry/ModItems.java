package com.example.oritechsplitter.registry;

import com.example.oritechsplitter.OritechSplitter;
import net.minecraft.world.item.BlockItem;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {

    private ModItems() {
    }

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(OritechSplitter.MOD_ID);

    public static final DeferredItem<BlockItem> SMART_SPLITTER = ITEMS.registerSimpleBlockItem(ModBlocks.SMART_SPLITTER);

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
    }
}
