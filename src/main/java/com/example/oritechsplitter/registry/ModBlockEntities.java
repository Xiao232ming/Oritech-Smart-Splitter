package com.example.oritechsplitter.registry;

import com.example.oritechsplitter.OritechSplitter;
import com.example.oritechsplitter.block.entity.SmartSplitterBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlockEntities {

    private ModBlockEntities() {
    }

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, OritechSplitter.MOD_ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SmartSplitterBlockEntity>> SMART_SPLITTER =
            BLOCK_ENTITIES.register("smart_splitter", () -> BlockEntityType.Builder
                    .of(SmartSplitterBlockEntity::new, ModBlocks.SMART_SPLITTER.get())
                    .build(null));

    public static void register(IEventBus modEventBus) {
        BLOCK_ENTITIES.register(modEventBus);
    }
}
