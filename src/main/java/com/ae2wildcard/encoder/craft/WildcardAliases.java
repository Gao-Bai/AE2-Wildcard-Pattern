package com.ae2wildcard.encoder.craft;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.ae2wildcard.encoder.WildcardConfig;

import net.minecraft.resources.ResourceLocation;

/**
 * Maps wildcard tokens such as {@code ingot} or {@code plate} to the Forge item tag family that backs them.
 * Forge item tags are the 1.20.1 replacement for the old ore dictionary.
 */
public final class WildcardAliases {

    private static final Map<String, ResourceLocation> BUILTIN = new LinkedHashMap<>();

    /** Names that describe a whole family instead of a single material. */
    private static final Set<String> GENERIC = new LinkedHashSet<>(Set.of(
            "ingot", "nugget", "dust", "powder", "plate", "sheet", "gear", "rod", "stick",
            "storage_block", "block", "raw_material", "raw_ore", "raw", "ore", "gem",
            "wire", "foil", "crystal", "clump", "shard", "fragment"));

    static {
        alias("ingot", "ingots");
        alias("nugget", "nuggets");
        alias("dust", "dusts");
        alias("powder", "dusts");
        alias("plate", "plates");
        alias("sheet", "plates");
        alias("gear", "gears");
        alias("rod", "rods");
        alias("stick", "rods");
        alias("block", "storage_blocks");
        alias("storage_block", "storage_blocks");
        alias("raw", "raw_materials");
        alias("raw_material", "raw_materials");
        alias("raw_ore", "raw_materials");
        alias("ore", "ores");
        alias("gem", "gems");
        alias("wire", "wires");
        alias("foil", "foils");
        // Vanilla families, handy for wood/stone processing chains.
        alias("log", "minecraft:logs");
        alias("plank", "minecraft:planks");
        alias("sapling", "minecraft:saplings");
        alias("leaves", "minecraft:leaves");
    }

    private WildcardAliases() {
    }

    private static void alias(String token, String tag) {
        String value = tag.indexOf(':') >= 0 ? tag : "forge:" + tag;
        ResourceLocation location = ResourceLocation.tryParse(value);
        if (location != null) {
            BUILTIN.put(token, location);
            // plural / singular forms resolve to the same family
            if (!token.endsWith("s")) {
                BUILTIN.put(token + "s", location);
            }
        }
    }

    /** All aliases, including the ones added through the config. */
    public static Map<String, ResourceLocation> all() {
        Map<String, ResourceLocation> map = new LinkedHashMap<>(BUILTIN);
        for (String entry : WildcardConfig.extraAliases()) {
            int eq = entry.indexOf('=');
            if (eq <= 0 || eq >= entry.length() - 1) {
                continue;
            }
            String token = entry.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            ResourceLocation location = ResourceLocation.tryParse(entry.substring(eq + 1).trim());
            if (!token.isEmpty() && location != null) {
                map.put(token, location);
            }
        }
        return map;
    }

    public static ResourceLocation resolve(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        return all().get(token.toLowerCase(Locale.ROOT));
    }

    /** Family names such as {@code ingots}: their tag entries are named after the material, not after the family. */
    public static Set<String> genericNames() {
        Set<String> names = new LinkedHashSet<>(GENERIC);
        for (ResourceLocation location : all().values()) {
            String path = location.getPath();
            int slash = path.lastIndexOf('/');
            String last = slash >= 0 ? path.substring(slash + 1) : path;
            names.add(last.toLowerCase(Locale.ROOT));
        }
        return names;
    }
}
