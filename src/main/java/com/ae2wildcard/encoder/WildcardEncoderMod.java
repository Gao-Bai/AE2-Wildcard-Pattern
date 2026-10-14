package com.ae2wildcard.encoder;

import com.ae2wildcard.encoder.block.WildcardPatternEncoderBlock;
import com.ae2wildcard.encoder.block.WildcardPatternProviderBlock;
import com.ae2wildcard.encoder.block.WildcardPatternProviderBlockEntity;
import com.ae2wildcard.encoder.command.WildcardCommands;
import com.ae2wildcard.encoder.craft.ConfigRecipes;
import com.ae2wildcard.encoder.craft.TagIndex;
import com.ae2wildcard.encoder.item.WildcardPatternItem;
import com.ae2wildcard.encoder.menu.WildcardPatternMenu;
import com.ae2wildcard.encoder.network.WildcardNetwork;
import com.ae2wildcard.encoder.runtime.WildcardPatternDecoder;
import com.mojang.logging.LogUtils;

import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import org.slf4j.Logger;

/**
 * Wildcard Pattern Encoder - a rule based replacement for "one encoded pattern per recipe".
 *
 * <p>A wildcard pattern stores a <b>rule set</b> (inputs / outputs / exclusions) in its own NBT and is
 * expanded on demand inside the ME network, instead of being pre-expanded into concrete pattern items.
 */
@Mod(WildcardEncoderMod.MODID)
public class WildcardEncoderMod {

    public static final String MODID = "wildcardencoder";

    /** Version of the loaded jar, so tooltips and commands can name the exact build. */
    public static String version() {
        return net.minecraftforge.fml.ModList.get().getModContainerById(MODID)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("dev");
    }
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MODID);
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(ForgeRegistries.MENU_TYPES,
            MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, MODID);

    public static final RegistryObject<Block> WILDCARD_PATTERN_ENCODER = BLOCKS
            .register("wildcard_pattern_encoder", WildcardPatternEncoderBlock::new);

    public static final RegistryObject<Item> WILDCARD_PATTERN_ENCODER_ITEM = ITEMS
            .register("wildcard_pattern_encoder",
                    () -> new BlockItem(WILDCARD_PATTERN_ENCODER.get(), new Item.Properties()));

    /** The provider that turns the rules into AE2 patterns at runtime (P2). */
    public static final RegistryObject<Block> WILDCARD_PATTERN_PROVIDER = BLOCKS
            .register("wildcard_pattern_provider", WildcardPatternProviderBlock::new);

    public static final RegistryObject<Item> WILDCARD_PATTERN_PROVIDER_ITEM = ITEMS
            .register("wildcard_pattern_provider",
                    () -> new BlockItem(WILDCARD_PATTERN_PROVIDER.get(), new Item.Properties()));

    public static final RegistryObject<BlockEntityType<WildcardPatternProviderBlockEntity>>
            WILDCARD_PATTERN_PROVIDER_BE = BLOCK_ENTITIES.register("wildcard_pattern_provider",
                    () -> BlockEntityType.Builder
                            .of(WildcardPatternProviderBlockEntity::new, WILDCARD_PATTERN_PROVIDER.get())
                            .build(null));

    /** The wildcard pattern itself: a plain mod item, deliberately not an AE2 encoded pattern. */
    public static final RegistryObject<Item> WILDCARD_PATTERN = ITEMS
            .register("wildcard_pattern", WildcardPatternItem::new);

    public static final RegistryObject<MenuType<WildcardPatternMenu>> WILDCARD_PATTERN_MENU = MENUS
            .register("wildcard_pattern",
                    () -> new MenuType<WildcardPatternMenu>(WildcardPatternMenu::new, FeatureFlags.DEFAULT_FLAGS));

    public WildcardEncoderMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        MENUS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        modBus.addListener(this::addCreativeTabItems);

        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, WildcardConfig.SPEC);

        LOGGER.info("Wildcard Encoder v{}", version());

        WildcardNetwork.register();

        // AE2's public decoder API: resolved definition keys decode back into one recipe, so in-flight
        // crafting jobs survive a world reload. No mixin involved.
        WildcardPatternDecoder.register();

        MinecraftForge.EVENT_BUS.addListener(WildcardCommands::register);
        MinecraftForge.EVENT_BUS.addListener(TagIndex::onTagsUpdated);

        // configurable crafting recipes: built once the server is up, and re-added after /reload
        MinecraftForge.EVENT_BUS.addListener(ConfigRecipes::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(ConfigRecipes::onTagsUpdated);
    }

    private void addCreativeTabItems(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(WILDCARD_PATTERN_ENCODER_ITEM);
            event.accept(WILDCARD_PATTERN_PROVIDER_ITEM);
            event.accept(WILDCARD_PATTERN);
        }
    }
}
