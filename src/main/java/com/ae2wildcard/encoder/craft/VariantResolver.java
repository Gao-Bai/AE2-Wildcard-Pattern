package com.ae2wildcard.encoder.craft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.ae2wildcard.encoder.WildcardConfig;
import com.ae2wildcard.encoder.rule.WildcardRules.Match;
import com.ae2wildcard.encoder.rule.WildcardRules.MatchKind;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Turns one {@link Match} into {@code variant -> candidate items}.
 *
 * <p>The variant key is the material name (iron, copper, tin, ...) derived from the Forge item tags -
 * the 1.20.1 replacement for the ore dictionary. Nothing here creates items: it only produces lists of
 * item references plus their variant keys.
 */
public final class VariantResolver {

    /** Result of resolving one matcher. */
    public record Result(Map<String, List<Item>> variants, List<Msg> warnings) {

        public boolean isEmpty() {
            return variants.isEmpty();
        }

        public int totalItems() {
            return variants.values().stream().mapToInt(List::size).sum();
        }
    }

    private VariantResolver() {
    }

    public static Result resolve(Match match) {
        Map<String, List<Item>> variants = new LinkedHashMap<>();
        List<Msg> warnings = new ArrayList<>();

        switch (match.kind()) {
            case ITEM -> {
                Item item = itemById(match.value());
                if (item == null) {
                    warnings.add(Msg.of(Msg.ERROR_UNKNOWN_ITEM, match.value()));
                } else {
                    // Never test the NBT template against a bare ItemStack here: state NBT (a GregTech
                    // circuit's Configuration, a mode, ...) only exists on real stacks, so such a test
                    // would reject exactly the items the rule is about. The template is a requirement on
                    // the input key - it travels into every resolved input and AE2 enforces it through
                    // IInput.isValid when the craft runs.
                    variants.put("", List.of(item));
                }
            }
            case TAG -> {
                ResourceLocation tag = parseTag(match.value());
                if (tag == null || !TagIndex.get().hasTag(tag)) {
                    warnings.add(Msg.of(Msg.WARN_UNKNOWN_TAG, match.describe()));
                } else {
                    TagIndex.get().collectVariants(tag, variants);
                }
            }
            case FAMILY -> resolveFamily(match, variants, warnings);
            case PATTERN -> resolvePattern(match, variants, warnings);
            case MOD -> {
                String namespace = match.value().toLowerCase(Locale.ROOT);
                for (Item item : ForgeRegistries.ITEMS) {
                    ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
                    if (id == null || !id.getNamespace().equals(namespace)) {
                        continue;
                    }
                    add(variants, id.getPath(), item);
                }
                if (variants.isEmpty()) {
                    warnings.add(Msg.of(Msg.WARN_NO_MOD_ITEMS, namespace));
                }
            }
            case ANY -> {
                for (Item item : ForgeRegistries.ITEMS) {
                    ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
                    if (id == null) {
                        continue;
                    }
                    add(variants, id.getPath(), item);
                }
            }
        }

        return new Result(variants, warnings);
    }

    private static void resolveFamily(Match match, Map<String, List<Item>> variants, List<Msg> warnings) {
        String token = match.value();
        if (token.indexOf('*') < 0) {
            // a family matcher without '*' is treated as an alias lookup
            ResourceLocation alias = WildcardAliases.resolve(token);
            if (alias != null && TagIndex.get().hasTag(alias)) {
                TagIndex.get().collectVariants(alias, variants);
                return;
            }
            warnings.add(Msg.of(Msg.WARN_FAMILY_NEEDS_STAR, token));
            return;
        }

        String namespace = null;
        String glob = token;
        int colon = token.indexOf(':');
        if (colon >= 0) {
            namespace = token.substring(0, colon);
            glob = token.substring(colon + 1);
        } else if (token.endsWith("*") && token.length() > 1) {
            ResourceLocation alias = WildcardAliases.resolve(token.substring(0, token.length() - 1));
            if (alias != null && TagIndex.get().hasTag(alias)) {
                TagIndex.get().collectVariants(alias, variants);
                return;
            }
        }
        List<ResourceLocation> roots = TagIndex.get().findFamilyRoots(namespace, glob);
        if (roots.isEmpty()) {
            warnings.add(Msg.of(Msg.WARN_NO_FAMILY, token));
            return;
        }
        for (ResourceLocation root : roots) {
            TagIndex.get().collectVariants(root, variants);
        }
    }

    /**
     * Matches every item id against a glob such as {@code gtceu:*_single_wire}. The text captured by
     * the first {@code *} becomes the variant name (the material), which is what lets a PATTERN output
     * bind to a {@code ingot*} input by material instead of pinning one concrete item.
     */
    private static void resolvePattern(Match match, Map<String, List<Item>> variants,
            List<Msg> warnings) {
        String glob = match.value();
        if (glob.indexOf('*') < 0 && glob.indexOf('?') < 0) {
            warnings.add(Msg.of(Msg.WARN_PATTERN_NEEDS_WILDCARD, glob));
            return;
        }
        java.util.regex.Pattern regex = globToRegex(glob);
        java.util.regex.Matcher matcher = regex.matcher("");
        for (Item item : ForgeRegistries.ITEMS) {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
            if (id == null) {
                continue;
            }
            matcher.reset(id.toString());
            if (!matcher.matches()) {
                continue;
            }
            String variant = matcher.groupCount() >= 1 && matcher.group(1) != null
                    ? matcher.group(1)
                    : id.toString();
            add(variants, variant, item);
        }
        if (variants.isEmpty()) {
            warnings.add(Msg.of(Msg.WARN_PATTERN_NO_MATCH, glob));
        }
    }

    /** Glob to regex: the first '*' becomes a capture group, later ones are plain wildcards. */
    private static java.util.regex.Pattern globToRegex(String glob) {
        StringBuilder regex = new StringBuilder("^");
        boolean captured = false;
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (!captured) {
                    regex.append("(.*)");
                    captured = true;
                } else {
                    regex.append(".*");
                }
            } else if (c == '?') {
                regex.append('.');
            } else {
                regex.append(java.util.regex.Pattern.quote(String.valueOf(c)));
            }
        }
        return java.util.regex.Pattern.compile(regex.append('$').toString());
    }

    private static void add(Map<String, List<Item>> variants, String key, Item item) {
        List<Item> list = variants.computeIfAbsent(key, k -> new ArrayList<>());
        if (!list.contains(item)) {
            list.add(item);
        }
    }

    private static Item itemById(String value) {
        ResourceLocation id = value.indexOf(':') >= 0
                ? ResourceLocation.tryParse(value)
                : ResourceLocation.tryParse("minecraft:" + value);
        if (id == null) {
            return null;
        }
        Item item = ForgeRegistries.ITEMS.getValue(id);
        return item == null || item == net.minecraft.world.item.Items.AIR ? null : item;
    }

    private static ResourceLocation parseTag(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.indexOf(':') >= 0 ? value : "forge:" + value;
        return ResourceLocation.tryParse(text);
    }

    // ------------------------------------------------------------------ ordering

    /**
     * Sorts the candidates of one variant: preferred namespaces first, then registry name. The first
     * entry is used as the representative item of that variant.
     */
    public static List<Item> sortCandidates(List<Item> items) {
        List<String> preferred = WildcardConfig.preferredNamespaces();
        List<Item> copy = new ArrayList<>(items);
        copy.sort(java.util.Comparator
                .comparingInt((Item item) -> preference(item, preferred))
                .thenComparing(item -> {
                    ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
                    return id != null ? id.toString() : "";
                }));
        int cap = WildcardConfig.maxCandidatesPerVariant();
        return copy.size() > cap ? copy.subList(0, cap) : copy;
    }

    private static int preference(Item item, List<String> preferred) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
        if (id == null) {
            return Integer.MAX_VALUE;
        }
        int index = preferred.indexOf(id.getNamespace());
        return index < 0 ? Integer.MAX_VALUE : index;
    }

    /** True for matcher kinds that can resolve to more than one item. */
    public static boolean isWildcardKind(MatchKind kind) {
        return kind != MatchKind.ITEM;
    }
}
