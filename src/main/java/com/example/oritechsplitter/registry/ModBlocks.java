package com.example.oritechsplitter.registry;

import com.example.oritechsplitter.OritechSplitter;
import com.example.oritechsplitter.block.SmartSplitterBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {

    private ModBlocks() {
    }

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(OritechSplitter.MOD_ID);

    /**
     * The Smart Splitter.
     *
     * <p>Properties mirror upstream ({@code ofFullCopy(IRON_BLOCK).strength(1.0f, 2.0f)}) with one
     * deliberate porting change: {@code noOcclusion()}. The model is an open frame, but the default
     * full-cube collision shape would make neighbouring blocks cull the faces they share with the
     * splitter, leaving visible holes behind the open sides. Upstream targets a newer Minecraft
     * version, so this is adjusted for correct 1.21.1 rendering.
     */
    public static final DeferredBlock<SmartSplitterBlock> SMART_SPLITTER = BLOCKS.registerBlock(
            "smart_splitter",
            SmartSplitterBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK)
                    .strength(1.0F, 2.0F)
                    .noOcclusion());

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
    }
}
