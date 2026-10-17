package com.ae2wildcard.encoder.rule;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * The complete rule set of one wildcard pattern.
 *
 * <p>This is pure data (no AE2 and no GUI dependencies): it is what gets stored in the item's NBT and
 * what the runtime instantiator reads. See docs/DESIGN-v2.md section 4/5.
 */
public record WildcardRules(List<Input> inputs, List<Output> outputs, List<Exclude> excludes, Options options) {

    public static final WildcardRules EMPTY = new WildcardRules(List.of(), List.of(), List.of(), Options.DEFAULT);

    public WildcardRules {
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
        excludes = List.copyOf(excludes);
        options = options == null ? Options.DEFAULT : options;
    }

    /** A rule is only usable when it has at least one input and one output. */
    public boolean isComplete() {
        return !inputs.isEmpty() && !outputs.isEmpty();
    }

    public boolean hasWildcardPart() {
        return inputs.stream().anyMatch(input -> input.match().isWildcard())
                || outputs.stream().anyMatch(output -> output.match().isWildcard());
    }

    public WildcardRules withInputs(List<Input> value) {
        return new WildcardRules(value, outputs, excludes, options);
    }

    public WildcardRules withOutputs(List<Output> value) {
        return new WildcardRules(inputs, value, excludes, options);
    }

    public WildcardRules withExcludes(List<Exclude> value) {
        return new WildcardRules(inputs, outputs, value, options);
    }

    public WildcardRules withOptions(Options value) {
        return new WildcardRules(inputs, outputs, excludes, value);
    }

    // ------------------------------------------------------------------ matchers

    public enum MatchKind {
        /** one concrete item (with an optional NBT subset) */
        ITEM,
        /** one exact item tag, e.g. forge:plates/iron */
        TAG,
        /** an ore-dictionary style family wildcard, e.g. ingot* or plate* */
        FAMILY,
        /** every item of one mod namespace */
        MOD,
        /** an item id pattern, e.g. gtceu:*_single_wire - the first '*' captures the material name */
        PATTERN,
        /** everything (debug only, disabled by default) */
        ANY;

        public static MatchKind byName(String name) {
            if (name != null) {
                for (MatchKind kind : values()) {
                    if (kind.name().equalsIgnoreCase(name)) {
                        return kind;
                    }
                }
            }
            return ITEM;
        }
    }

    /**
     * One matcher: what an input/output entry accepts. The optional {@code nbt} is a <b>subset</b>
     * template: the candidate item must contain all of it.
     */
    public record Match(MatchKind kind, String value, CompoundTag nbt) {

        public Match {
            Objects.requireNonNull(kind, "kind");
            value = value == null ? "" : value.trim();
            nbt = nbt == null || nbt.isEmpty() ? null : nbt.copy();
        }

        public static Match item(ResourceLocation id) {
            return new Match(MatchKind.ITEM, id.toString(), null);
        }

        /** Same, but keeping the NBT of the stack that was picked (config values, modes, ...). */
        public static Match item(ResourceLocation id, CompoundTag nbt) {
            return new Match(MatchKind.ITEM, id.toString(), nbt);
        }

        public static Match of(MatchKind kind, String value) {
            return new Match(kind, value, null);
        }

        public boolean hasNbt() {
            return nbt != null;
        }

        /** A plain, fully qualified item without NBT constraints. */
        public boolean isSimpleItem() {
            return kind == MatchKind.ITEM && nbt == null;
        }

        public boolean isWildcard() {
            return kind != MatchKind.ITEM;
        }

        public String describe() {
            return switch (kind) {
                case ITEM -> value;
                case TAG -> "#" + value;
                case FAMILY -> value;
                case MOD -> "@" + value;
                case PATTERN -> value;
                case ANY -> "*";
            };
        }

        /** NBT part of the match (candidate selection already happened in the resolver). */
        public boolean matchesNbt(ItemStack stack) {
            return NbtMatch.subset(stack.getTag(), nbt);
        }
    }

    /** NBT subset matching, used by both the resolver and the runtime pattern inputs. */
    public static final class NbtMatch {

        private NbtMatch() {
        }

        public static boolean subset(CompoundTag actual, CompoundTag template) {
            if (template == null || template.isEmpty()) {
                return true;
            }
            if (actual == null) {
                return false;
            }
            for (String key : template.getAllKeys()) {
                Tag expected = template.get(key);
                if (expected == null) {
                    continue;
                }
                Tag found = actual.get(key);
                if (found == null || !valueMatches(found, expected)) {
                    return false;
                }
            }
            return true;
        }

        private static boolean valueMatches(Tag found, Tag expected) {
            if (expected instanceof CompoundTag expectedCompound) {
                return found instanceof CompoundTag foundCompound && subset(foundCompound, expectedCompound);
            }
            if (expected instanceof ListTag expectedList) {
                if (!(found instanceof ListTag foundList) || foundList.size() != expectedList.size()) {
                    return false;
                }
                for (int i = 0; i < expectedList.size(); i++) {
                    if (!valueMatches(foundList.get(i), expectedList.get(i))) {
                        return false;
                    }
                }
                return true;
            }
            if (expected instanceof NumericTag expectedNumber && found instanceof NumericTag foundNumber) {
                return expectedNumber.getAsString().equals(foundNumber.getAsString());
            }
            return expected.equals(found);
        }

        /** Human readable form for tooltips/logs. */
        public static String describe(CompoundTag template) {
            return template == null || template.isEmpty() ? "-" : template.getAsString();
        }
    }

    // ------------------------------------------------------------------ entries

    /** 原料条目。{@code group} 相同（非空）的条目会绑定到同一个变体。 */
    public record Input(Match match, long count, String group, boolean consume, boolean optional) {

        public static final long MAX_COUNT = 1_000_000L;

        public Input {
            Objects.requireNonNull(match, "match");
            count = Math.max(1L, Math.min(MAX_COUNT, count));
            group = group == null ? "" : group.trim().toUpperCase(Locale.ROOT);
        }

        public boolean grouped() {
            return !group.isEmpty();
        }
    }

    /** 产物条目。 */
    public record Output(Match match, long count, String group) {

        public Output {
            Objects.requireNonNull(match, "match");
            count = Math.max(1L, Math.min(Input.MAX_COUNT, count));
            group = group == null ? "" : group.trim().toUpperCase(Locale.ROOT);
        }

        public boolean grouped() {
            return !group.isEmpty();
        }
    }

    public enum ExcludeKind {
        /** a single item id */
        ITEM,
        /** a whole mod namespace */
        MOD,
        /** a variant/material name, e.g. tin */
        VARIANT,
        /** items carrying this NBT subset */
        NBT,
        /** one exact input item paired with one exact output item */
        PAIR,
        /** an output item id or family token, e.g. !thermal:*plate* */
        OUTPUT;
    }

    /**
     * One exclusion rule. Meaning of the fields depends on {@link ExcludeKind}:
     * ITEM/MOD/VARIANT/OUTPUT use {@code a}; PAIR uses {@code a} (input) and {@code b} (output);
     * NBT uses {@code nbt}.
     */
    public record Exclude(ExcludeKind kind, String a, String b, CompoundTag nbt) {

        public Exclude {
            Objects.requireNonNull(kind, "kind");
            a = a == null ? "" : a.trim();
            b = b == null ? "" : b.trim();
            nbt = nbt == null || nbt.isEmpty() ? null : nbt.copy();
        }

        public static Exclude of(ExcludeKind kind, String a) {
            return new Exclude(kind, a, "", null);
        }

        public static Exclude pair(String input, String output) {
            return new Exclude(ExcludeKind.PAIR, input, output, null);
        }

        public String describe() {
            return switch (kind) {
                case ITEM -> "!" + a;
                case MOD -> "@" + a;
                case VARIANT -> "variant:" + a;
                case NBT -> "nbt:" + NbtMatch.describe(nbt);
                case PAIR -> a + " -> !" + b;
                case OUTPUT -> "out:!" + a;
            };
        }
    }

    /** Per-pattern options (also mirrored in the mod config as defaults). */
    public record Options(boolean bindPerGroup, int maxInstances, int maxOptionalExpansion) {

        public static final Options DEFAULT = new Options(true, 256, 0);

        public Options {
            maxInstances = Math.max(1, Math.min(8192, maxInstances));
            maxOptionalExpansion = Math.max(0, Math.min(2, maxOptionalExpansion));
        }
    }
}
