package com.ae2wildcard.encoder.craft;

import java.util.ArrayList;
import java.util.List;

import com.ae2wildcard.encoder.WildcardConfig;
import com.ae2wildcard.encoder.WildcardEncoderMod;
import com.mojang.logging.LogUtils;

import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraftforge.event.TagsUpdatedEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.server.ServerLifecycleHooks;

import org.slf4j.Logger;

/**
 * Builds the mod's crafting recipes from the config file and injects them into the recipe manager.
 *
 * <p>Minecraft recipes are data driven, so "configurable ingredients" means building the recipes at
 * runtime. {@link RecipeManager#replaceRecipes} is public, and the injection happens once the server
 * has started (and again after every datapack reload) - that is before any player logs in, so the
 * recipes end up in the list that is synced to clients and shown by JEI.
 *
 * <p>Ingredient syntax in the config: {@code namespace:item}, {@code #namespace:tag} or {@code ""} for
 * an empty slot, nine entries in reading order (row by row).
 */
public final class ConfigRecipes {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String GROUP = "wildcardencoder";

    private ConfigRecipes() {
    }

    public static void onServerStarted(ServerStartedEvent event) {
        inject(event.getServer());
    }

    /** A datapack reload rebuilds the recipe manager, so the configured recipes are added again. */
    public static void onTagsUpdated(TagsUpdatedEvent event) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            inject(server);
        }
    }

    private static void inject(MinecraftServer server) {
        RecipeManager manager = server.getRecipeManager();
        List<Recipe<?>> recipes = new ArrayList<>(manager.getRecipes());
        // drop the previous injection first, so restarts and /reload stay idempotent
        recipes.removeIf(recipe -> recipe.getId().getNamespace().equals(WildcardEncoderMod.MODID));

        addShaped(recipes, "wildcard_pattern_encoder", WildcardConfig.recipePatternEncoder(),
                WildcardEncoderMod.WILDCARD_PATTERN_ENCODER_ITEM.get());
        addShaped(recipes, "wildcard_pattern_provider", WildcardConfig.recipePatternProvider(),
                WildcardEncoderMod.WILDCARD_PATTERN_PROVIDER_ITEM.get());
        addShaped(recipes, "wildcard_pattern", WildcardConfig.recipeWildcardPattern(),
                WildcardEncoderMod.WILDCARD_PATTERN.get());

        manager.replaceRecipes(recipes);
    }

    private static void addShaped(List<Recipe<?>> out, String name, WildcardConfig.RecipeSpec spec, Item result) {
        if (!spec.enabled() || spec.count() <= 0) {
            return;
        }
        List<String> grid = spec.ingredients();
        int minRow = 3;
        int maxRow = -1;
        int minCol = 3;
        int maxCol = -1;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                if (entry(grid, row * 3 + col).isEmpty()) {
                    continue;
                }
                minRow = Math.min(minRow, row);
                maxRow = Math.max(maxRow, row);
                minCol = Math.min(minCol, col);
                maxCol = Math.max(maxCol, col);
            }
        }
        if (maxRow < 0) {
            LOGGER.warn("Recipe {} is enabled but has no ingredients; skipping it", name);
            return;
        }
        // trim empty edge rows/columns so the shape matches like a hand written recipe
        int width = maxCol - minCol + 1;
        int height = maxRow - minRow + 1;
        List<Ingredient> ingredients = new ArrayList<>(width * height);
        for (int row = minRow; row <= maxRow; row++) {
            for (int col = minCol; col <= maxCol; col++) {
                ingredients.add(parse(entry(grid, row * 3 + col)));
            }
        }
        NonNullList<Ingredient> list = NonNullList.of(Ingredient.EMPTY, ingredients.toArray(new Ingredient[0]));
        out.add(new ShapedRecipe(new ResourceLocation(WildcardEncoderMod.MODID, name), GROUP,
                CraftingBookCategory.MISC, width, height, list, new ItemStack(result, spec.count())));
    }

    private static String entry(List<String> grid, int index) {
        return index < grid.size() ? grid.get(index).trim() : "";
    }

    private static Ingredient parse(String spec) {
        if (spec.isEmpty()) {
            return Ingredient.EMPTY;
        }
        if (spec.startsWith("#")) {
            ResourceLocation tag = ResourceLocation.tryParse(spec.substring(1));
            if (tag == null) {
                LOGGER.warn("Ignoring malformed tag in the recipe config: {}", spec);
                return Ingredient.EMPTY;
            }
            return Ingredient.of(TagKey.create(Registries.ITEM, tag));
        }
        ResourceLocation id = ResourceLocation.tryParse(spec);
        Item item = id == null ? null : ForgeRegistries.ITEMS.getValue(id);
        if (item == null || item == Items.AIR) {
            LOGGER.warn("Ignoring unknown item in the recipe config: {}", spec);
            return Ingredient.EMPTY;
        }
        return Ingredient.of(item);
    }
}
