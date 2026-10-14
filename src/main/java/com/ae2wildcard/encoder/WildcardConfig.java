package com.ae2wildcard.encoder;

import java.util.ArrayList;
import java.util.List;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Mod configuration. All wildcard-rule limits/behaviour switches live here; per-pattern overrides are
 * stored in the pattern's own NBT (see {@code WildcardRules.Options}).
 */
public final class WildcardConfig {

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final Values VALUES = new Values(BUILDER);

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    private WildcardConfig() {
    }

    public static final class Values {

        public final ForgeConfigSpec.IntValue maxInstances;
        public final ForgeConfigSpec.IntValue maxMatchedVariants;
        public final ForgeConfigSpec.IntValue maxCandidatesPerVariant;
        public final ForgeConfigSpec.IntValue maxAmount;
        public final ForgeConfigSpec.BooleanValue allowModMatcher;
        public final ForgeConfigSpec.BooleanValue allowAnyMatcher;
        public final ForgeConfigSpec.BooleanValue encodeConsumesBlankPattern;
        public final ForgeConfigSpec.BooleanValue encodeRequiresBlankPattern;
        public final ForgeConfigSpec.BooleanValue recipePatternEncoderEnabled;
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> recipePatternEncoderIngredients;
        public final ForgeConfigSpec.IntValue recipePatternEncoderCount;
        public final ForgeConfigSpec.BooleanValue recipePatternProviderEnabled;
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> recipePatternProviderIngredients;
        public final ForgeConfigSpec.IntValue recipePatternProviderCount;
        public final ForgeConfigSpec.BooleanValue recipeWildcardPatternEnabled;
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> recipeWildcardPatternIngredients;
        public final ForgeConfigSpec.IntValue recipeWildcardPatternCount;
        public final ForgeConfigSpec.ConfigValue<String> preferredNamespaces;
        public final ForgeConfigSpec.ConfigValue<String> extraAliases;
        public final ForgeConfigSpec.IntValue commandPermissionLevel;

        Values(ForgeConfigSpec.Builder builder) {
            builder.comment("Runtime expansion limits (the wildcard pattern is instantiated in memory,",
                    "nothing is ever written into concrete pattern items)").push("limits");
            maxInstances = builder
                    .comment("Maximum number of concrete recipes one wildcard pattern may expand to at runtime.")
                    .defineInRange("maxInstances", 256, 1, 8192);
            maxMatchedVariants = builder
                    .comment("Maximum number of variants (materials) a single matcher may resolve to.")
                    .defineInRange("maxMatchedVariants", 512, 1, 65536);
            maxCandidatesPerVariant = builder
                    .comment("Maximum number of candidate items kept per variant when several mods add the",
                            "same material (the first one after sorting is used as the representative).")
                    .defineInRange("maxCandidatesPerVariant", 64, 1, 1024);
            maxAmount = builder
                    .comment("Maximum amount per rule entry.")
                    .defineInRange("maxAmount", 1000000, 1, 1000000);
            builder.pop();

            builder.comment("Which matcher kinds players may use").push("matchers");
            allowModMatcher = builder
                    .comment("Allow the @mod matcher. It can match thousands of items, so it is off by default.")
                    .define("allowModMatcher", false);
            allowAnyMatcher = builder
                    .comment("Allow the * (match everything) matcher. Debug use only.")
                    .define("allowAnyMatcher", false);
            builder.pop();

            builder.comment("Behaviour").push("behaviour");
            encodeConsumesBlankPattern = builder
                    .comment("The wildcard pattern encoder consumes one AE2 blank pattern per encoded wildcard pattern.",
                            "Only used when the player actually has one (and not in creative mode).")
                    .define("encodeConsumesBlankPattern", true);
            encodeRequiresBlankPattern = builder
                    .comment("The wildcard pattern encoder requires an AE2 blank pattern to be present.",
                            "Set to false to encode wildcard patterns with no blank pattern at all;",
                            "set consume=false above to use one without using it up.")
                    .define("encodeRequiresBlankPattern", true);
            preferredNamespaces = builder
                    .comment("Namespace priority when picking the representative item of a variant.",
                            "Comma separated, for example: minecraft,thermal,immersiveengineering")
                    .define("preferredNamespaces", "minecraft");
            extraAliases = builder
                    .comment("Extra family aliases for the FAMILY matcher, comma separated.",
                            "Format: token=namespace:tagpath  -  for example: pelt=forge:pelts",
                            "After adding one, 'pelt*' resolves to the forge:pelts tag family.")
                    .define("extraAliases", "");
            builder.pop();

            builder.comment("Crafting recipes. Ingredients are nine entries in reading order (row by row):",
                    "  \"ae2:quartz_glass\"  an item",
                    "  \"#forge:ingots/gold\"  any item of a tag",
                    "  \"\"  an empty slot",
                    "Set enabled=false to remove a recipe entirely. Changes apply on world load or /reload.")
                    .push("recipes");
            recipePatternEncoderEnabled = builder
                    .comment("Whether the wildcard pattern encoder can be crafted at all.")
                    .define("patternEncoderEnabled", true);
            recipePatternEncoderIngredients = builder
                    .comment("Ingredients of the wildcard pattern encoder.")
                    .defineList("patternEncoderIngredients", List.of(
                            "ae2:quartz_glass", "#forge:ingots/gold", "ae2:quartz_glass",
                            "ae2:calculation_processor", "ae2:blank_pattern", "ae2:calculation_processor",
                            "ae2:quartz_glass", "#forge:ingots/gold", "ae2:quartz_glass"),
                            value -> value instanceof String);
            recipePatternEncoderCount = builder
                    .comment("How many encoder blocks one craft yields.")
                    .defineInRange("patternEncoderCount", 1, 1, 64);

            recipePatternProviderEnabled = builder
                    .comment("Whether the wildcard pattern provider can be crafted at all.")
                    .define("patternProviderEnabled", true);
            recipePatternProviderIngredients = builder
                    .comment("Ingredients of the wildcard pattern provider.")
                    .defineList("patternProviderIngredients", List.of(
                            "ae2:quartz_glass", "ae2:calculation_processor", "ae2:quartz_glass",
                            "#forge:ingots/gold", "ae2:pattern_provider", "#forge:ingots/gold",
                            "ae2:quartz_glass", "ae2:calculation_processor", "ae2:quartz_glass"),
                            value -> value instanceof String);
            recipePatternProviderCount = builder
                    .comment("How many provider blocks one craft yields.")
                    .defineInRange("patternProviderCount", 1, 1, 64);

            recipeWildcardPatternEnabled = builder
                    .comment("Whether a blank wildcard pattern can be crafted directly (it is not encoded yet,",
                            "so it has no rules until you edit it). Handy to skip the encoder block.")
                    .define("wildcardPatternEnabled", true);
            recipeWildcardPatternIngredients = builder
                    .comment("Ingredients of the blank wildcard pattern.")
                    .defineList("wildcardPatternIngredients", List.of(
                            "#forge:ingots/gold", "ae2:quartz_glass", "#forge:ingots/gold",
                            "ae2:quartz_glass", "ae2:blank_pattern", "ae2:quartz_glass",
                            "#forge:ingots/gold", "ae2:quartz_glass", "#forge:ingots/gold"),
                            value -> value instanceof String);
            recipeWildcardPatternCount = builder
                    .comment("How many wildcard patterns one craft yields.")
                    .defineInRange("wildcardPatternCount", 1, 1, 64);
            builder.pop();

            commandPermissionLevel = builder
                    .comment("Permission level required to use the /wildcardencoder command.")
                    .defineInRange("commandPermissionLevel", 2, 0, 4);
        }
    }

    public static int maxInstances() {
        return readInt(VALUES.maxInstances, 256);
    }

    public static int maxMatchedVariants() {
        return readInt(VALUES.maxMatchedVariants, 512);
    }

    public static int maxCandidatesPerVariant() {
        return readInt(VALUES.maxCandidatesPerVariant, 64);
    }

    public static long maxAmount() {
        return readInt(VALUES.maxAmount, 1_000_000);
    }

    public static boolean allowModMatcher() {
        return readBoolean(VALUES.allowModMatcher, false);
    }

    public static boolean allowAnyMatcher() {
        return readBoolean(VALUES.allowAnyMatcher, false);
    }

    public static boolean encodeConsumesBlankPattern() {
        return readBoolean(VALUES.encodeConsumesBlankPattern, true);
    }

    public static boolean encodeRequiresBlankPattern() {
        return readBoolean(VALUES.encodeRequiresBlankPattern, true);
    }

    /** One configurable recipe: enabled, nine ingredient entries, result count. */
    public record RecipeSpec(boolean enabled, List<String> ingredients, int count) {
    }

    public static RecipeSpec recipePatternEncoder() {
        return new RecipeSpec(readBoolean(VALUES.recipePatternEncoderEnabled, true),
                readStringList(VALUES.recipePatternEncoderIngredients),
                readInt(VALUES.recipePatternEncoderCount, 1));
    }

    public static RecipeSpec recipePatternProvider() {
        return new RecipeSpec(readBoolean(VALUES.recipePatternProviderEnabled, true),
                readStringList(VALUES.recipePatternProviderIngredients),
                readInt(VALUES.recipePatternProviderCount, 1));
    }

    public static RecipeSpec recipeWildcardPattern() {
        return new RecipeSpec(readBoolean(VALUES.recipeWildcardPatternEnabled, true),
                readStringList(VALUES.recipeWildcardPatternIngredients),
                readInt(VALUES.recipeWildcardPatternCount, 1));
    }

    private static List<String> readStringList(ForgeConfigSpec.ConfigValue<List<? extends String>> value) {
        try {
            List<? extends String> read = value.get();
            return read == null ? List.of() : List.copyOf(read);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    public static int commandPermissionLevel() {
        return readInt(VALUES.commandPermissionLevel, 2);
    }

    public static List<String> preferredNamespaces() {
        List<String> values = splitList(VALUES.preferredNamespaces);
        return values.isEmpty() ? List.of("minecraft") : values;
    }

    public static List<String> extraAliases() {
        return splitList(VALUES.extraAliases);
    }

    private static int readInt(ForgeConfigSpec.ConfigValue<Integer> value, int fallback) {
        Integer read = read(value, fallback);
        return read != null ? read : fallback;
    }

    private static boolean readBoolean(ForgeConfigSpec.ConfigValue<Boolean> value, boolean fallback) {
        Boolean read = read(value, fallback);
        return read != null && read;
    }

    private static <T> T read(ForgeConfigSpec.ConfigValue<T> value, T fallback) {
        try {
            T read = value.get();
            return read != null ? read : fallback;
        } catch (RuntimeException e) {
            // Config not loaded yet (very early startup) - use the default.
            return fallback;
        }
    }

    private static List<String> splitList(ForgeConfigSpec.ConfigValue<String> value) {
        String raw = read(value, "");
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }
}
