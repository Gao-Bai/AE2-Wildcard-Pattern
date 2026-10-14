package com.ae2wildcard.encoder.craft;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.ae2wildcard.encoder.WildcardConfig;
import com.ae2wildcard.encoder.rule.WildcardRules;
import com.ae2wildcard.encoder.rule.WildcardRules.Input;
import com.ae2wildcard.encoder.rule.WildcardRules.MatchKind;
import com.ae2wildcard.encoder.rule.WildcardRules.Output;

import net.minecraft.world.item.Item;

/**
 * The heart of the mod: turns a saved rule set into concrete recipes <b>in memory</b>.
 *
 * <p>Nothing is encoded into items here. AE2's crafting planner needs concrete
 * {@code IPatternDetails}, so the rules have to be instantiated eventually - but that happens on
 * demand (when a pattern provider is asked for available patterns) and the result is cached, not
 * written into the world. See docs/DESIGN-v2.md section 6.1.
 */
public final class PatternInstantiator {

    /** Instantiation result. {@code error} is a human readable, translatable-agnostic message. */
    public record Result(List<ResolvedPattern> patterns, int excluded, boolean truncated,
            List<Msg> warnings, Msg error) {

        public static Result error(Msg message) {
            return new Result(List.of(), 0, false, List.of(), message);
        }

        public boolean ok() {
            return error == null;
        }

        public int total() {
            return patterns.size() + excluded;
        }
    }

    private PatternInstantiator() {
    }

    public static Result instantiate(WildcardRules rules) {
        if (!rules.isComplete()) {
            return Result.error(Msg.of(Msg.ERROR_NEED_INPUT_OUTPUT));
        }
        try {
            return expand(rules);
        } catch (RuntimeException e) {
            return Result.error(Msg.of(Msg.ERROR_FAILED, e.toString()));
        }
    }

    // ------------------------------------------------------------------ slots

    /** One rule entry with its resolved candidates. */
    private record Slot(boolean input, int ruleIndex, Input inputRule, Output outputRule,
            Map<String, List<Item>> variants, Item fixed, List<Msg> warnings) {

        String group() {
            return input ? inputRule.group() : outputRule.group();
        }

        boolean isWildcard() {
            return fixed == null;
        }

        long count() {
            return input ? inputRule.count() : outputRule.count();
        }
    }

    private static Slot resolveSlot(Input rule, int index) {
        VariantResolver.Result resolved = VariantResolver.resolve(rule.match());
        List<Msg> warnings = new ArrayList<>(resolved.warnings());
        if (rule.match().kind() == MatchKind.ITEM) {
            Item fixed = resolved.variants().isEmpty() ? null : resolved.variants().values().iterator().next().get(0);
            return new Slot(true, index, rule, null, null, fixed, warnings);
        }
        Map<String, List<Item>> variants = sortVariants(resolved.variants(), warnings);
        return new Slot(true, index, rule, null, variants, null, warnings);
    }

    private static Slot resolveSlot(Output rule, int index) {
        VariantResolver.Result resolved = VariantResolver.resolve(rule.match());
        List<Msg> warnings = new ArrayList<>(resolved.warnings());
        if (rule.match().kind() == MatchKind.ITEM) {
            Item fixed = resolved.variants().isEmpty() ? null : resolved.variants().values().iterator().next().get(0);
            return new Slot(false, index, null, rule, null, fixed, warnings);
        }
        Map<String, List<Item>> variants = sortVariants(resolved.variants(), warnings);
        return new Slot(false, index, null, rule, variants, null, warnings);
    }

    private static Map<String, List<Item>> sortVariants(Map<String, List<Item>> raw, List<Msg> warnings) {
        Map<String, List<Item>> out = new LinkedHashMap<>();
        int cap = WildcardConfig.maxMatchedVariants();
        for (var entry : raw.entrySet()) {
            if (out.size() >= cap) {
                warnings.add(Msg.of(Msg.WARN_VARIANTS_CAPPED, cap));
                break;
            }
            out.put(entry.getKey(), VariantResolver.sortCandidates(entry.getValue()));
        }
        return out;
    }

    // ------------------------------------------------------------------ expansion

    private static Result expand(WildcardRules rules) {
        List<Msg> warnings = new ArrayList<>();
        List<Slot> slots = new ArrayList<>();
        for (int i = 0; i < rules.inputs().size(); i++) {
            slots.add(resolveSlot(rules.inputs().get(i), i));
        }
        int inputCount = slots.size();
        for (int i = 0; i < rules.outputs().size(); i++) {
            slots.add(resolveSlot(rules.outputs().get(i), i));
        }

        for (Slot slot : slots) {
            warnings.addAll(slot.warnings());
            if (slot.isWildcard() && (slot.variants() == null || slot.variants().isEmpty())) {
                String matcher = slot.input() ? slot.inputRule().match().describe() : slot.outputRule().match().describe();
                return Result.error(Msg.of(Msg.ERROR_NO_MATCH, matcher));
            }
            if (!slot.isWildcard() && slot.fixed() == null) {
                String matcher = slot.input() ? slot.inputRule().match().describe() : slot.outputRule().match().describe();
                return Result.error(Msg.of(Msg.ERROR_UNKNOWN_ITEM, matcher));
            }
        }

        // ---- binding units: same group -> same variant
        List<Unit> units = buildUnits(rules, slots, warnings);
        int budget = Math.min(rules.options().maxInstances(), WildcardConfig.maxInstances());

        List<ResolvedPattern> patterns = new ArrayList<>();
        int excluded = 0;
        boolean truncated = false;
        // Several variant keys can resolve to the same items (an item registered both as "copper" and
        // "copper_ingot" for example), so identical recipes are collapsed.
        Set<String> seen = new HashSet<>();

        int[] cursor = new int[units.size()];
        outer:
        while (true) {
            // resolve one concrete recipe for this combination
            Set<String> variantsUsed = new LinkedHashSet<>();
            Item[] picked = new Item[slots.size()];
            boolean valid = true;
            for (int u = 0; u < units.size(); u++) {
                Unit unit = units.get(u);
                String variant = unit.variants().get(cursor[u]);
                variantsUsed.add(variant);
                for (int slotIndex : unit.slotIndexes()) {
                    picked[slotIndex] = unit.representative(slotIndex, variant);
                    if (picked[slotIndex] == null) {
                        valid = false;
                    }
                }
            }
            for (int i = 0; i < slots.size() && valid; i++) {
                Slot slot = slots.get(i);
                if (picked[i] == null) {
                    picked[i] = slot.fixed();
                }
                if (picked[i] == null) {
                    valid = false;
                }
            }

            if (valid) {
                List<ResolvedPattern.Input> inputs = new ArrayList<>(inputCount);
                List<ResolvedPattern.Output> outputs = new ArrayList<>(slots.size() - inputCount);
                for (int i = 0; i < slots.size(); i++) {
                    Slot slot = slots.get(i);
                    if (slot.input()) {
                        inputs.add(ResolvedPattern.Input.of(picked[i], slot.inputRule().count(),
                                slot.inputRule().match().nbt(), slot.inputRule().consume()));
                    } else {
                        outputs.add(ResolvedPattern.Output.of(picked[i], slot.outputRule().count(),
                                slot.outputRule().match().nbt()));
                    }
                }
                String variantKey = variantsUsed.size() == 1 ? variantsUsed.iterator().next() : String.join("+", variantsUsed);
                boolean duplicate = !seen.add(recipeSignature(inputs, outputs));
                if (!duplicate) {
                    if (ExclusionFilter.isExcluded(rules, List.of(variantKey), inputs, outputs)) {
                        excluded++;
                    } else {
                        addPatterns(patterns, rules, variantKey, inputs, outputs, warnings);
                    }
                }
            }
            if (patterns.size() >= budget) {
                truncated = true;
                break;
            }

            // advance the mixed-radix cursor
            int position = units.size() - 1;
            while (position >= 0) {
                cursor[position]++;
                if (cursor[position] < units.get(position).variants().size()) {
                    break;
                }
                cursor[position] = 0;
                position--;
            }
            if (position < 0) {
                break outer;
            }
        }

        if (truncated) {
            warnings.add(Msg.of(Msg.WARN_TRUNCATED, budget));
        }
        return new Result(patterns, excluded, truncated, dedupe(warnings), null);
    }

    /**
     * Appends the recipe, expanded over the optional-input subsets when that is enabled. Exclusions are
     * already evaluated by the caller so that dropped combinations can be counted.
     */
    private static void addPatterns(List<ResolvedPattern> patterns, WildcardRules rules, String variantKey,
            List<ResolvedPattern.Input> inputs, List<ResolvedPattern.Output> outputs, List<Msg> warnings) {
        int optionalExpansion = rules.options().maxOptionalExpansion();
        List<Integer> optionalIndexes = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            if (inputs.get(i).consume() && isOptional(rules, i)) {
                optionalIndexes.add(i);
            }
        }
        if (optionalExpansion <= 0 || optionalIndexes.isEmpty()) {
            patterns.add(new ResolvedPattern(variantKey, List.copyOf(inputs), List.copyOf(outputs)));
            return;
        }
        if (optionalIndexes.size() > optionalExpansion) {
            warnings.add(Msg.of(Msg.WARN_OPTIONAL_LIMIT, optionalIndexes.size(), optionalExpansion));
            patterns.add(new ResolvedPattern(variantKey, List.copyOf(inputs), List.copyOf(outputs)));
            return;
        }
        int combinations = 1 << optionalIndexes.size();
        for (int mask = 0; mask < combinations; mask++) {
            List<ResolvedPattern.Input> subset = new ArrayList<>(inputs.size());
            for (int i = 0; i < inputs.size(); i++) {
                int optionalPosition = optionalIndexes.indexOf(i);
                boolean dropped = optionalPosition >= 0 && (mask & (1 << optionalPosition)) != 0;
                if (!dropped) {
                    subset.add(inputs.get(i));
                }
            }
            patterns.add(new ResolvedPattern(variantKey, List.copyOf(subset), List.copyOf(outputs)));
        }
    }

    private static boolean isOptional(WildcardRules rules, int inputIndex) {
        return inputIndex < rules.inputs().size() && rules.inputs().get(inputIndex).optional();
    }

    /** Content based signature of a resolved recipe, used to drop duplicates. */
    private static String recipeSignature(List<ResolvedPattern.Input> inputs,
            List<ResolvedPattern.Output> outputs) {
        StringBuilder builder = new StringBuilder();
        for (ResolvedPattern.Input input : inputs) {
            builder.append(id(input.item())).append('x').append(input.amount())
                    .append(input.nbt() == null ? "" : input.nbt()).append(input.consume() ? '+' : '~');
        }
        builder.append("->");
        for (ResolvedPattern.Output output : outputs) {
            builder.append(id(output.item())).append('x').append(output.amount())
                    .append(output.nbt() == null ? "" : output.nbt()).append('+');
        }
        return builder.toString();
    }

    private static String id(Item item) {
        var location = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(item);
        return location == null ? item.toString() : location.toString();
    }

    // ------------------------------------------------------------------ units

    /** One independent binding: a group of slots that must share the same variant. */
    private record Unit(String id, List<Integer> slotIndexes, List<String> variants,
            Map<Integer, Map<String, List<Item>>> candidates) {

        Item representative(int slotIndex, String variant) {
            Map<String, List<Item>> map = candidates.get(slotIndex);
            if (map == null) {
                return null;
            }
            List<Item> list = map.get(variant);
            return list == null || list.isEmpty() ? null : list.get(0);
        }
    }

    private static List<Unit> buildUnits(WildcardRules rules, List<Slot> slots, List<Msg> warnings) {
        boolean bindGroups = rules.options().bindPerGroup();
        Map<String, List<Integer>> byGroup = new LinkedHashMap<>();
        List<Unit> units = new ArrayList<>();

        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            if (!slot.isWildcard()) {
                continue;
            }
            if (bindGroups && !slot.group().isEmpty()) {
                byGroup.computeIfAbsent(slot.group(), k -> new ArrayList<>()).add(i);
                continue;
            }
            units.add(unitFor("slot" + i, List.of(i), slots));
        }

        for (var entry : byGroup.entrySet()) {
            List<Integer> indexes = entry.getValue();
            Set<String> intersection = null;
            for (int index : indexes) {
                Set<String> keys = slots.get(index).variants().keySet();
                if (intersection == null) {
                    intersection = new LinkedHashSet<>(keys);
                } else {
                    intersection.retainAll(keys);
                }
            }
            if (intersection == null || intersection.isEmpty()) {
                warnings.add(Msg.of(Msg.WARN_GROUP_NO_COMMON, entry.getKey()));
                for (int index : indexes) {
                    units.add(unitFor("slot" + index, List.of(index), slots));
                }
                continue;
            }
            units.add(unitFor("group" + entry.getKey(), indexes, slots));
        }
        return units;
    }

    private static Unit unitFor(String id, List<Integer> indexes, List<Slot> slots) {
        Set<String> variants;
        if (indexes.size() == 1) {
            variants = new LinkedHashSet<>(slots.get(indexes.get(0)).variants().keySet());
        } else {
            variants = new LinkedHashSet<>(slots.get(indexes.get(0)).variants().keySet());
            for (int i = 1; i < indexes.size(); i++) {
                variants.retainAll(slots.get(indexes.get(i)).variants().keySet());
            }
        }
        Map<Integer, Map<String, List<Item>>> candidates = new LinkedHashMap<>();
        for (int index : indexes) {
            candidates.put(index, slots.get(index).variants());
        }
        return new Unit(id, List.copyOf(indexes), List.copyOf(variants), candidates);
    }

    private static List<Msg> dedupe(List<Msg> messages) {
        return new ArrayList<>(new LinkedHashSet<>(messages));
    }

    /** Small helper for GUI/commands: how many recipes a rule set would produce right now. */
    public static int countInstances(WildcardRules rules) {
        return instantiate(rules).patterns().size();
    }

    /** Variant names a rule set would bind to (used by the GUI preview). */
    public static List<String> previewVariants(WildcardRules rules, int limit) {
        return instantiate(rules).patterns().stream()
                .map(ResolvedPattern::variant)
                .filter(variant -> !variant.isEmpty())
                .distinct()
                .limit(limit)
                .map(variant -> variant.toLowerCase(Locale.ROOT))
                .toList();
    }
}
