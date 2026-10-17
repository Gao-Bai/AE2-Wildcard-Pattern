package com.ae2wildcard.encoder.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.ae2wildcard.encoder.WildcardConfig;
import com.ae2wildcard.encoder.craft.TagIndex;
import com.ae2wildcard.encoder.craft.WildcardAliases;
import com.ae2wildcard.encoder.rule.WildcardRules.MatchKind;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Self contained search over items, item tags, mod namespaces and family tokens. No JEI dependency.
 *
 * <pre>
 * "iron"   -&gt; items, tags and family tokens matching the text
 * "#ingot" -&gt; tags only
 * "@therm" -&gt; mod namespaces only
 * </pre>
 */
public final class ItemSearch {

    public record Entry(MatchKind kind, String value, Item item, String title, String subtitle) {

        public static Entry item(Item item, String id) {
            return new Entry(MatchKind.ITEM, id, item, new ItemStack(item).getHoverName().getString(), id);
        }

        public static Entry tag(String tag) {
            return new Entry(MatchKind.TAG, tag, null, "#" + tag, "tag");
        }

        public static Entry family(String token) {
            return new Entry(MatchKind.FAMILY, token, null, token, "family");
        }

        public static Entry mod(String namespace) {
            return new Entry(MatchKind.MOD, namespace, null, "@" + namespace, "mod");
        }

        public boolean hasIcon() {
            return item != null;
        }
    }

    private ItemSearch() {
    }

    /** Family tokens, deduplicated per resolved tag so we show {@code ingot*} instead of {@code ingots*}. */
    public static List<String> familyTokens() {
        Map<ResourceLocation, String> best = new LinkedHashMap<>();
        for (var entry : WildcardAliases.all().entrySet()) {
            String token = entry.getKey() + "*";
            String existing = best.get(entry.getValue());
            if (existing == null || token.length() < existing.length()) {
                best.put(entry.getValue(), token);
            }
        }
        return new ArrayList<>(new LinkedHashSet<>(best.values()));
    }

    public static List<Entry> search(String rawQuery, int limit) {
        String query = rawQuery == null ? "" : rawQuery.trim().toLowerCase(Locale.ROOT);
        List<Entry> out = new ArrayList<>();

        if (query.startsWith("#")) {
            collectTags(query.substring(1), out, limit);
            return out;
        }
        if (query.startsWith("@")) {
            collectMods(query.substring(1), out, limit);
            return out;
        }

        if (query.isEmpty()) {
            for (String token : familyTokens()) {
                if (out.size() >= limit) {
                    return out;
                }
                out.add(Entry.family(token));
            }
            return out;
        }

        for (String token : familyTokens()) {
            if (out.size() >= limit) {
                return out;
            }
            if (token.toLowerCase(Locale.ROOT).contains(query)) {
                out.add(Entry.family(token));
            }
        }
        collectTags(query, out, limit);

        for (Item item : ForgeRegistries.ITEMS) {
            if (out.size() >= limit) {
                break;
            }
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
            if (id == null) {
                continue;
            }
            String path = id.getPath().toLowerCase(Locale.ROOT);
            String full = id.toString().toLowerCase(Locale.ROOT);
            boolean hit = path.contains(query) || full.contains(query) || tokensMatch(full, query);
            if (!hit) {
                String name = new ItemStack(item).getHoverName().getString().toLowerCase(Locale.ROOT);
                hit = !name.isEmpty() && name.contains(query);
            }
            if (hit) {
                out.add(Entry.item(item, id.toString()));
            }
        }
        return out;
    }

    /**
     * Every ':'/'/'/'_'/' '-separated token of the query has to appear somewhere in the haystack, so
     * "gtceu:single_wire" and "single wire" both find "gtceu:tin_single_wire".
     */
    private static boolean tokensMatch(String haystack, String query) {
        String[] tokens = query.split("[:/\\s_]+");
        int seen = 0;
        for (String token : tokens) {
            if (token.isEmpty()) {
                continue;
            }
            if (!haystack.contains(token)) {
                return false;
            }
            seen++;
        }
        return seen > 1; // a single token is already covered by the plain substring test
    }

    private static void collectTags(String query, List<Entry> out, int limit) {
        Set<String> seen = new LinkedHashSet<>();
        for (ResourceLocation tag : TagIndex.get().tagNames()) {
            if (out.size() >= limit) {
                return;
            }
            String text = tag.toString();
            String lower = text.toLowerCase(Locale.ROOT);
            if (query.isEmpty() || lower.contains(query) || tokensMatch(lower, query)) {
                if (seen.add(text)) {
                    out.add(Entry.tag(text));
                }
            }
        }
    }

    private static void collectMods(String query, List<Entry> out, int limit) {
        if (!WildcardConfig.allowModMatcher()) {
            return;
        }
        Set<String> namespaces = new LinkedHashSet<>();
        for (ResourceLocation id : ForgeRegistries.ITEMS.getKeys()) {
            namespaces.add(id.getNamespace());
        }
        for (String namespace : namespaces) {
            if (out.size() >= limit) {
                return;
            }
            if (query.isEmpty() || namespace.contains(query)) {
                out.add(Entry.mod(namespace));
            }
        }
    }
}
