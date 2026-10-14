package com.ae2wildcard.encoder.rule;

import java.util.ArrayList;
import java.util.List;

import com.ae2wildcard.encoder.rule.WildcardRules.Exclude;
import com.ae2wildcard.encoder.rule.WildcardRules.ExcludeKind;
import com.ae2wildcard.encoder.rule.WildcardRules.Input;
import com.ae2wildcard.encoder.rule.WildcardRules.Match;
import com.ae2wildcard.encoder.rule.WildcardRules.MatchKind;
import com.ae2wildcard.encoder.rule.WildcardRules.Options;
import com.ae2wildcard.encoder.rule.WildcardRules.Output;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;

/**
 * NBT (and SNBT) serialisation of {@link WildcardRules}. This is the only place that knows the on-disk
 * shape, so a future format change only needs a new branch here (guarded by {@link #VERSION}).
 */
public final class RuleCodec {

    public static final int VERSION = 1;

    private static final String KEY_VERSION = "we_ver";
    private static final String KEY_INPUTS = "in";
    private static final String KEY_OUTPUTS = "out";
    private static final String KEY_EXCLUDES = "ex";
    private static final String KEY_OPTIONS = "opt";

    private RuleCodec() {
    }

    // ------------------------------------------------------------------ rules

    public static CompoundTag write(WildcardRules rules) {
        CompoundTag tag = new CompoundTag();
        tag.putInt(KEY_VERSION, VERSION);

        var inputs = new net.minecraft.nbt.ListTag();
        for (Input input : rules.inputs()) {
            inputs.add(writeInput(input));
        }
        tag.put(KEY_INPUTS, inputs);

        var outputs = new net.minecraft.nbt.ListTag();
        for (Output output : rules.outputs()) {
            outputs.add(writeOutput(output));
        }
        tag.put(KEY_OUTPUTS, outputs);

        var excludes = new net.minecraft.nbt.ListTag();
        for (Exclude exclude : rules.excludes()) {
            excludes.add(writeExclude(exclude));
        }
        tag.put(KEY_EXCLUDES, excludes);

        Options options = rules.options();
        CompoundTag optionTag = new CompoundTag();
        optionTag.putBoolean("bind", options.bindPerGroup());
        optionTag.putInt("maxI", options.maxInstances());
        optionTag.putInt("maxOpt", options.maxOptionalExpansion());
        tag.put(KEY_OPTIONS, optionTag);
        return tag;
    }

    /** Never fails: missing or broken data yields {@link WildcardRules#EMPTY}. */
    public static WildcardRules read(CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            return WildcardRules.EMPTY;
        }
        try {
            List<Input> inputs = new ArrayList<>();
            for (Tag element : tag.getList(KEY_INPUTS, Tag.TAG_COMPOUND)) {
                inputs.add(readInput((CompoundTag) element));
            }
            List<Output> outputs = new ArrayList<>();
            for (Tag element : tag.getList(KEY_OUTPUTS, Tag.TAG_COMPOUND)) {
                outputs.add(readOutput((CompoundTag) element));
            }
            List<Exclude> excludes = new ArrayList<>();
            for (Tag element : tag.getList(KEY_EXCLUDES, Tag.TAG_COMPOUND)) {
                excludes.add(readExclude((CompoundTag) element));
            }
            Options options = Options.DEFAULT;
            if (tag.contains(KEY_OPTIONS, Tag.TAG_COMPOUND)) {
                CompoundTag optionTag = tag.getCompound(KEY_OPTIONS);
                options = new Options(
                        optionTag.contains("bind") ? optionTag.getBoolean("bind") : true,
                        optionTag.contains("maxI") ? optionTag.getInt("maxI") : Options.DEFAULT.maxInstances(),
                        optionTag.contains("maxOpt") ? optionTag.getInt("maxOpt") : 0);
            }
            return new WildcardRules(inputs, outputs, excludes, options);
        } catch (RuntimeException e) {
            return WildcardRules.EMPTY;
        }
    }

    public static boolean looksLikeRules(CompoundTag tag) {
        return tag != null && tag.contains(KEY_VERSION);
    }

    // ------------------------------------------------------------------ entries

    public static CompoundTag writeMatch(Match match) {
        CompoundTag tag = new CompoundTag();
        tag.putString("k", match.kind().name());
        if (!match.value().isEmpty()) {
            tag.putString("v", match.value());
        }
        if (match.hasNbt()) {
            tag.put("nbt", match.nbt().copy());
        }
        return tag;
    }

    public static Match readMatch(CompoundTag tag) {
        MatchKind kind = MatchKind.byName(tag.getString("k"));
        String value = tag.getString("v");
        CompoundTag nbt = tag.contains("nbt", Tag.TAG_COMPOUND) ? tag.getCompound("nbt") : null;
        return new Match(kind, value, nbt);
    }

    public static CompoundTag writeInput(Input input) {
        CompoundTag tag = new CompoundTag();
        tag.put("m", writeMatch(input.match()));
        tag.putLong("n", input.count());
        if (input.grouped()) {
            tag.putString("grp", input.group());
        }
        if (!input.consume()) {
            tag.putBoolean("keep", true);
        }
        if (input.optional()) {
            tag.putBoolean("opt", true);
        }
        return tag;
    }

    public static Input readInput(CompoundTag tag) {
        Match match = readMatch(tag.getCompound("m"));
        long count = tag.contains("n") ? tag.getLong("n") : 1L;
        String group = tag.getString("grp");
        boolean keep = tag.getBoolean("keep");
        boolean optional = tag.getBoolean("opt");
        return new Input(match, count, group, !keep, optional);
    }

    public static CompoundTag writeOutput(Output output) {
        CompoundTag tag = new CompoundTag();
        tag.put("m", writeMatch(output.match()));
        tag.putLong("n", output.count());
        if (output.grouped()) {
            tag.putString("grp", output.group());
        }
        return tag;
    }

    public static Output readOutput(CompoundTag tag) {
        Match match = readMatch(tag.getCompound("m"));
        long count = tag.contains("n") ? tag.getLong("n") : 1L;
        return new Output(match, count, tag.getString("grp"));
    }

    public static CompoundTag writeExclude(Exclude exclude) {
        CompoundTag tag = new CompoundTag();
        tag.putString("k", exclude.kind().name());
        if (!exclude.a().isEmpty()) {
            tag.putString("a", exclude.a());
        }
        if (!exclude.b().isEmpty()) {
            tag.putString("b", exclude.b());
        }
        if (exclude.nbt() != null) {
            tag.put("nbt", exclude.nbt().copy());
        }
        return tag;
    }

    public static Exclude readExclude(CompoundTag tag) {
        ExcludeKind kind;
        try {
            kind = ExcludeKind.valueOf(tag.getString("k"));
        } catch (IllegalArgumentException e) {
            kind = ExcludeKind.ITEM;
        }
        CompoundTag nbt = tag.contains("nbt", Tag.TAG_COMPOUND) ? tag.getCompound("nbt") : null;
        return new Exclude(kind, tag.getString("a"), tag.getString("b"), nbt);
    }

    // ------------------------------------------------------------------ snbt

    public static String toSnbt(WildcardRules rules) {
        return write(rules).getAsString();
    }

    /**
     * @return the parsed rules, or {@code null} when the text is not valid rule SNBT
     */
    public static WildcardRules fromSnbt(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            CompoundTag tag = TagParser.parseTag(text);
            if (!looksLikeRules(tag)) {
                return null;
            }
            return read(tag);
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException | RuntimeException e) {
            return null;
        }
    }
}
