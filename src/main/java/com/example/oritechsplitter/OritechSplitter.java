package com.example.oritechsplitter;

import com.example.oritechsplitter.registry.ModBlockEntities;
import com.example.oritechsplitter.registry.ModBlocks;
import com.example.oritechsplitter.registry.ModCreativeTabs;
import com.example.oritechsplitter.registry.ModItems;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import org.slf4j.Logger;

/**
 * Entry point of the Oritech Smart Splitter backport.
 *
 * <p>This mod ports the {@code smart_splitter} block that Oritech 2.0.0 (Minecraft 26.1.2) introduced,
 * so that it can be used on Minecraft 1.21.1 together with Oritech 1.2.x.
 *
 * <p>Oritech is a required dependency: the crafting recipe consumes {@code oritech:item_pipe} and
 * {@code oritech:item_filter_block}, and the splitter is designed to be driven by Oritech's item pipes.
 */
@Mod(OritechSplitter.MOD_ID)
public class OritechSplitter {

    /** Must match {@code modId} in {@code META-INF/neoforge.mods.toml}. */
    public static final String MOD_ID = "oritechsplitter";

    public static final Logger LOGGER = LogUtils.getLogger();

    public OritechSplitter(IEventBus modEventBus, ModContainer modContainer) {
        ModBlocks.register(modEventBus);
        ModItems.register(modEventBus);
        ModBlockEntities.register(modEventBus);
        ModCreativeTabs.register(modEventBus);

        modEventBus.addListener(OritechSplitter::registerCapabilities);
    }

    /** Namespaced id helper for this mod. */
    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    /**
     * Exposes the splitter inventory through the standard NeoForge item handler capability.
     *
     * <p>On Minecraft 1.21.1 this capability ({@code Capabilities.ItemHandler.BLOCK} / {@code IItemHandler})
     * is what Oritech's own item pipes, vanilla hoppers and other mods use to move items, so the splitter
     * stays usable by any of them - the same design goal the upstream block documents for its own
     * "standard item transaction API".
     */
    private static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
                Capabilities.ItemHandler.BLOCK,
                ModBlockEntities.SMART_SPLITTER.get(),
                (blockEntity, side) -> blockEntity.getItemHandler(side));
    }
}
