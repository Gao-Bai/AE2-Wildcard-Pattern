package com.ae2wildcard.encoder.craft;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraftforge.event.TagsUpdatedEvent;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.tags.ITag;
import net.minecraftforge.registries.tags.ITagManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Snapshot of every bound item tag, plus the logic that turns a tag family into
 * {@code variant key -> candidate items}. Rebuilt whenever the server/client reloads tags.
 */
public final class TagIndex {

    private static final Logger LOGGER = LoggerFactory.getLogger("wildcardencoder/TagIndex");

    /** Suffix tokens that are stripped from item ids when a family tag carries no material sub-tag. */
    private static final Map<String, List<String>> AFFIXES = Map.ofEntries(
            Map.entry("ingots", List.of("ingot")),
            Map.entry("nuggets", List.of("nugget", "bit")),
            Map.entry("dusts", List.of("dust", "powder")),
            Map.entry("plates", List.of("plate", "sheet")),
            Map.entry("gears", List.of("gear")),
            Map.entry("rods", List.of("rod", "stick")),
            Map.entry("storage_blocks", List.of("block", "storage_block")),
            Map.entry("raw_materials", List.of("raw", "raw_material", "raw_ore", "crushed", "cluster", "ore")),
            Map.entry("ores", List.of("ore")),
            Map.entry("gems", List.of("gem", "crystal")),
            Map.entry("wires", List.of("wire")),
            Map.entry("foils", List.of("foil")));

    private static volatile TagIndex instance;
    private static final java.util.concurrent.atomic.AtomicInteger REVISION =
            new java.util.concurrent.atomic.AtomicInteger();

    private final Map<ResourceLocation, List<Item>> itemsByTag;
    private final List<ResourceLocation> tagNames;
    private final Set<String> genericNames;

    private TagIndex(Map<ResourceLocation, List<Item>> itemsByTag, Set<String> genericNames) {
        this.itemsByTag = itemsByTag;
        this.tagNames = List.copyOf(itemsByTag.keySet());
        this.genericNames = genericNames;
    }

    public static TagIndex get() {
        TagIndex local = instance;
        if (local == null) {
            synchronized (TagIndex.class) {
                local = instance;
                if (local == null) {
                    local = build();
                    instance = local;
                }
            }
        }
        return local;
    }

    public static void invalidate() {
        instance = null;
        REVISION.incrementAndGet();
    }

    /** Bumped whenever the tag snapshot is rebuilt; used as a cache key by the runtime layer. */
    public static int revision() {
        return REVISION.get();
    }

    /** Called from the Forge event bus: tags changed, so our snapshot is stale. */
    public static void onTagsUpdated(TagsUpdatedEvent event) {
        if (event.shouldUpdateStaticData()) {
            invalidate();
        }
    }

    private static TagIndex build() {
        // ResourceLocation is Comparable, so a TreeMap gives a stable iteration order
        Map<ResourceLocation, List<Item>> map = new TreeMap<>();
        ITagManager<Item> manager = ForgeRegistries.ITEMS == null ? null : ForgeRegistries.ITEMS.tags();
        if (manager == null) {
            LOGGER.warn("The item registry does not expose a tag manager; "
                    + "wildcard families cannot be resolved on this side.");
            return new TagIndex(map, WildcardAliases.genericNames());
        }
        for (TagKey<Item> key : manager.getTagNames().toList()) {
            ITag<Item> tag = manager.getTag(key);
            if (!tag.isBound() || tag.isEmpty()) {
                continue;
            }
            map.put(key.location(), tag.stream().toList());
        }
        LOGGER.debug("Indexed {} bound item tags", map.size());
        return new TagIndex(map, WildcardAliases.genericNames());
    }

    /** True if the given tag exists and has at least one item. */
    public boolean hasTag(ResourceLocation location) {
        List<Item> items = itemsByTag.get(location);
        return items != null && !items.isEmpty();
    }

    public List<ResourceLocation> tagNames() {
        return tagNames;
    }

    /**
     * Finds the tag families matching a glob such as {@code ingot*} (which matches {@code forge:ingots}
     * and therefore its sub tags). Only the top-most matching tags are returned.
     */
    public List<ResourceLocation> findFamilyRoots(String namespace, String glob) {
        if (namespace != null) {
            return minimalRoots(match(namespace, glob));
        }
        List<ResourceLocation> forge = minimalRoots(match("forge", glob));
        if (!forge.isEmpty()) {
            return forge;
        }
        return minimalRoots(match(null, glob));
    }

    private List<ResourceLocation> match(String namespace, String glob) {
        Pattern regex = globToRegex(glob);
        List<ResourceLocation> out = new ArrayList<>();
        for (ResourceLocation location : tagNames) {
            if (namespace != null && !location.getNamespace().equals(namespace)) {
                continue;
            }
            if (regex.matcher(location.getPath()).matches()) {
                out.add(location);
            }
        }
        return out;
    }

    private static List<ResourceLocation> minimalRoots(List<ResourceLocation> tags) {
        List<ResourceLocation> out = new ArrayList<>();
        for (ResourceLocation candidate : tags) {
            boolean hasParent = false;
            for (ResourceLocation other : tags) {
                if (!other.equals(candidate) && isSubTagOf(candidate, other)) {
                    hasParent = true;
                    break;
                }
            }
            if (!hasParent) {
                out.add(candidate);
            }
        }
        return out;
    }

    private static boolean isSubTagOf(ResourceLocation child, ResourceLocation parent) {
        return child.getNamespace().equals(parent.getNamespace())
                && child.getPath().startsWith(parent.getPath() + "/");
    }

    private static Pattern globToRegex(String glob) {
        StringBuilder builder = new StringBuilder();
        String[] parts = glob.split("\\*", -1);
        for (String part : parts) {
            if (builder.length() > 0) {
                builder.append(".*");
            }
            builder.append(Pattern.quote(part));
        }
        return Pattern.compile(builder.toString());
    }

    /**
     * Adds every item of {@code root} and all of its sub tags (for example {@code forge:ingots} and
     * {@code forge:ingots/iron}) to {@code out}, keyed by material variant.
     */
    public void collectVariants(ResourceLocation root, Map<String, List<Item>> out) {
        String rootPath = root.getPath();
        for (ResourceLocation location : tagNames) {
            if (!location.getNamespace().equals(root.getNamespace())) {
                continue;
            }
            String path = location.getPath();
            if (!path.equals(rootPath) && !path.startsWith(rootPath + "/")) {
                continue;
            }
            List<Item> items = itemsByTag.get(location);
            if (items == null) {
                continue;
            }
            for (Item item : items) {
                String key = variantKey(location, item);
                add(out, key, item);
                String pathKey = itemPath(item);
                if (pathKey != null && !pathKey.equals(key)) {
                    // allows filters such as {iron_ingot} in addition to {iron}
                    add(out, pathKey, item);
                }
            }
        }
    }

    /** Variant keys of a family, sorted, for previews and the debug command. */
    public List<String> variantKeys(ResourceLocation root) {
        Map<String, List<Item>> variants = new LinkedHashMap<>();
        collectVariants(root, variants);
        List<String> keys = new ArrayList<>(variants.keySet());
        keys.sort(Comparator.naturalOrder());
        return keys;
    }

    private static void add(Map<String, List<Item>> out, String key, Item item) {
        List<Item> list = out.computeIfAbsent(key, k -> new ArrayList<>());
        if (!list.contains(item)) {
            list.add(item);
        }
    }

    private String variantKey(ResourceLocation tagLocation, Item item) {
        String path = tagLocation.getPath();
        int slash = path.lastIndexOf('/');
        String last = (slash >= 0 ? path.substring(slash + 1) : path).toLowerCase(Locale.ROOT);
        if (!genericNames.contains(last)) {
            return last;
        }
        String itemPath = itemPath(item);
        return itemPath != null ? stripAffix(itemPath, last) : last;
    }

    /**
     * Derives a material name from an item id when the family tag has no sub tag,
     * e.g. {@code tin_ingot} -> {@code tin} for the {@code forge:ingots} family.
     */
    private static String stripAffix(String itemPath, String family) {
        List<String> tokens = AFFIXES.get(family);
        if (tokens == null) {
            tokens = List.of(family.endsWith("s") ? family.substring(0, family.length() - 1) : family);
        }
        for (String token : tokens) {
            if (itemPath.length() > token.length() + 1) {
                if (itemPath.endsWith("_" + token)) {
                    return itemPath.substring(0, itemPath.length() - token.length() - 1);
                }
                if (itemPath.startsWith(token + "_")) {
                    return itemPath.substring(token.length() + 1);
                }
            }
        }
        return itemPath;
    }

    private static String itemPath(Item item) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
        return id != null ? id.getPath() : null;
    }
}
