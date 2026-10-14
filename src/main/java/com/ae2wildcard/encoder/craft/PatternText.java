package com.ae2wildcard.encoder.craft;

import java.util.List;

import com.ae2wildcard.encoder.rule.WildcardRules;
import com.ae2wildcard.encoder.rule.WildcardRules.Input;
import com.ae2wildcard.encoder.rule.WildcardRules.Match;
import com.ae2wildcard.encoder.rule.WildcardRules.Output;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;

/**
 * Human readable rendering of rules and instantiated recipes, used by the GUI, the tooltips and the
 * commands. Never used by the runtime matching itself.
 */
public final class PatternText {

    private PatternText() {
    }

    // ------------------------------------------------------------------ rules

    public static String plainMatch(Match match) {
        return match.describe();
    }

    public static String plainInput(Input input) {
        StringBuilder builder = new StringBuilder();
        builder.append(input.count()).append("x ").append(matchText(input.match()));
        if (input.grouped()) {
            builder.append(" [").append(input.group()).append(']');
        }
        if (!input.consume()) {
            builder.append(" keep");
        }
        if (input.optional()) {
            builder.append(" optional");
        }
        return builder.toString();
    }

    private static String matchText(Match match) {
        String text = match.describe();
        return match.hasNbt() ? text + WildcardRules.NbtMatch.describe(match.nbt()) : text;
    }

    public static String plainOutput(Output output) {
        StringBuilder builder = new StringBuilder();
        builder.append(output.count()).append("x ").append(matchText(output.match()));
        if (output.grouped()) {
            builder.append(" [").append(output.group()).append(']');
        }
        return builder.toString();
    }

    /** e.g. {@code 原料 2 · 产物 1 · 排除 3} */
    /** Localisable header summary: the client renders it in the player's own language. */
    public static Component summary(WildcardRules rules) {
        return Component.translatable("wildcardencoder.summary",
                rules.inputs().size(), rules.outputs().size(), rules.excludes().size());
    }

    public static String plainSummary(WildcardRules rules) {
        return summary(rules).getString();
    }

    // ------------------------------------------------------------------ recipes

    public static Component describe(ResolvedPattern pattern) {
        MutableComponent text = Component.literal(pattern.variant().isEmpty() ? "" : pattern.variant() + ": ");
        text.append(describeInputs(pattern.inputs()));
        text.append(" -> ");
        text.append(describeOutputs(pattern.outputs()));
        return text;
    }

    public static String plain(ResolvedPattern pattern) {
        StringBuilder builder = new StringBuilder();
        if (!pattern.variant().isEmpty()) {
            builder.append(pattern.variant()).append(": ");
        }
        for (int i = 0; i < pattern.inputs().size(); i++) {
            if (i > 0) {
                builder.append(" + ");
            }
            ResolvedPattern.Input input = pattern.inputs().get(i);
            builder.append(input.amount()).append("x ").append(stackName(input.toStack(1)));
        }
        builder.append(" -> ");
        for (int i = 0; i < pattern.outputs().size(); i++) {
            if (i > 0) {
                builder.append(" + ");
            }
            ResolvedPattern.Output output = pattern.outputs().get(i);
            builder.append(output.amount()).append("x ").append(stackName(output.toStack(1)));
        }
        return builder.toString();
    }

    private static MutableComponent describeInputs(List<ResolvedPattern.Input> inputs) {
        MutableComponent text = Component.empty();
        for (int i = 0; i < inputs.size(); i++) {
            if (i > 0) {
                text.append(" + ");
            }
            ResolvedPattern.Input input = inputs.get(i);
            text.append(input.amount() + "x ").append(input.toStack(1).getHoverName());
        }
        return text;
    }

    private static MutableComponent describeOutputs(List<ResolvedPattern.Output> outputs) {
        MutableComponent text = Component.empty();
        for (int i = 0; i < outputs.size(); i++) {
            if (i > 0) {
                text.append(" + ");
            }
            ResolvedPattern.Output output = outputs.get(i);
            text.append(output.amount() + "x ").append(output.toStack(1).getHoverName());
        }
        return text;
    }

    private static String stackName(ItemStack stack) {
        return stack.getHoverName().getString();
    }
}
