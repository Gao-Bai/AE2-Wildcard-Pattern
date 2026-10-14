package com.ae2wildcard.encoder.craft;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

import com.ae2wildcard.encoder.rule.WildcardRules;
import com.ae2wildcard.encoder.rule.WildcardRules.Exclude;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Evaluates the "排除 / 去除不想要的编码" tab. It runs <b>before</b> a concrete recipe is handed to AE2,
 * so an excluded combination never becomes visible to the ME system at all.
 */
public final class ExclusionFilter {

    private ExclusionFilter() {
    }

    public static boolean isExcluded(WildcardRules rules, Collection<String> variants,
            List<ResolvedPattern.Input> inputs, List<ResolvedPattern.Output> outputs) {
        if (rules.excludes().isEmpty()) {
            return false;
        }
        for (Exclude exclude : rules.excludes()) {
            switch (exclude.kind()) {
                case VARIANT -> {
                    String wanted = exclude.a().toLowerCase(Locale.ROOT);
                    if (wanted.isEmpty()) {
                        continue;
                    }
                    for (String variant : variants) {
                        if (variant.toLowerCase(Locale.ROOT).equals(wanted)) {
                            return true;
                        }
                    }
                }
                case ITEM -> {
                    for (Item item : allItems(inputs, outputs)) {
                        if (idEquals(item, exclude.a())) {
                            return true;
                        }
                    }
                }
                case MOD -> {
                    String namespace = exclude.a().toLowerCase(Locale.ROOT);
                    if (namespace.startsWith("@")) {
                        namespace = namespace.substring(1);
                    }
                    if (namespace.isEmpty()) {
                        continue;
                    }
                    for (Item item : allItems(inputs, outputs)) {
                        ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
                        if (id != null && id.getNamespace().equals(namespace)) {
                            return true;
                        }
                    }
                }
                case NBT -> {
                    if (exclude.nbt() == null) {
                        continue;
                    }
                    for (Item item : allItems(inputs, outputs)) {
                        ItemStack probe = new ItemStack(item);
                        if (WildcardRules.NbtMatch.subset(probe.getTag(), exclude.nbt())) {
                            return true;
                        }
                    }
                }
                case PAIR -> {
                    if (exclude.a().isEmpty() || exclude.b().isEmpty()) {
                        continue;
                    }
                    boolean inputHit = inputs.stream().anyMatch(input -> idEquals(input.item(), exclude.a()));
                    boolean outputHit = outputs.stream().anyMatch(output -> idEquals(output.item(), exclude.b())
                            || matchesToken(exclude.b(), output.item(), ""));
                    if (inputHit && outputHit) {
                        return true;
                    }
                }
                case OUTPUT -> {
                    if (exclude.a().isEmpty()) {
                        continue;
                    }
                    for (ResolvedPattern.Output output : outputs) {
                        if (matchesToken(exclude.a(), output.item(), "")) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static List<Item> allItems(List<ResolvedPattern.Input> inputs, List<ResolvedPattern.Output> outputs) {
        List<Item> items = new java.util.ArrayList<>(inputs.size() + outputs.size());
        for (ResolvedPattern.Input input : inputs) {
            items.add(input.item());
        }
        for (ResolvedPattern.Output output : outputs) {
            items.add(output.item());
        }
        return items;
    }

    private static boolean idEquals(Item item, String value) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
        if (id == null || value == null || value.isEmpty()) {
            return false;
        }
        String text = value.trim();
        if (!text.contains(":")) {
            text = "minecraft:" + text;
        }
        return id.toString().equals(text);
    }

    /**
     * Matches a flexible token: {@code #tag}, an item id, a glob with {@code *} (against the full id and
     * the path) or a variant/material name.
     */
    public static boolean matchesToken(String token, Item item, String variant) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        String text = token.trim();
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
        if (text.startsWith("#")) {
            ResourceLocation tagId = ResourceLocation.tryParse(text.substring(1));
            if (tagId == null) {
                return false;
            }
            return new ItemStack(item).is(TagKey.create(Registries.ITEM, tagId));
        }
        if (text.indexOf('*') >= 0) {
            String regex = ("\\Q" + text.replace("*", "\\E.*\\Q") + "\\E");
            if (id != null && (id.toString().matches(regex) || id.getPath().matches(regex))) {
                return true;
            }
            return variant != null && !variant.isEmpty() && variant.matches(regex);
        }
        if (idEquals(item, text)) {
            return true;
        }
        return variant != null && !variant.isEmpty() && variant.equalsIgnoreCase(text);
    }
}
