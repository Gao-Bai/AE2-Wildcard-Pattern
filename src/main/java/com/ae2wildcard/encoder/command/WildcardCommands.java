package com.ae2wildcard.encoder.command;

import java.util.List;
import java.util.Map;

import com.ae2wildcard.encoder.WildcardConfig;
import com.ae2wildcard.encoder.WildcardEncoderMod;
import com.ae2wildcard.encoder.craft.Msg;
import com.ae2wildcard.encoder.craft.PatternInstantiator;
import com.ae2wildcard.encoder.craft.PatternText;
import com.ae2wildcard.encoder.craft.TagIndex;
import com.ae2wildcard.encoder.craft.WildcardAliases;
import com.ae2wildcard.encoder.item.WildcardItemData;
import com.ae2wildcard.encoder.rule.RuleCodec;
import com.ae2wildcard.encoder.rule.WildcardRules;
import com.ae2wildcard.encoder.rule.WildcardRules.Exclude;
import com.ae2wildcard.encoder.rule.WildcardRules.Input;
import com.ae2wildcard.encoder.rule.WildcardRules.Output;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.RegisterCommandsEvent;

/**
 * Debug/automation commands for the wildcard patterns.
 *
 * <pre>
 * /wildcardencoder rules                     print the held pattern's rule set
 * /wildcardencoder preview [limit]           instantiate the rules and list the recipes
 * /wildcardencoder export                    print the held rules as SNBT
 * /wildcardencoder import &lt;snbt&gt;             apply SNBT to the held pattern
 * /wildcardencoder give                      spawn an empty wildcard pattern
 * /wildcardencoder families                  list the family aliases
 * /wildcardencoder variants &lt;family&gt;         list the variants of one family
 * </pre>
 */
public final class WildcardCommands {

    private WildcardCommands() {
    }

    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("wildcardencoder")
                .requires(source -> source.hasPermission(WildcardConfig.commandPermissionLevel()))
                .then(Commands.literal("rules").executes(context -> rules(context.getSource())))
                .then(Commands.literal("preview")
                        .executes(context -> preview(context.getSource(), 10))
                        .then(Commands.argument("limit", IntegerArgumentType.integer(1, 50))
                                .executes(context -> preview(context.getSource(),
                                        IntegerArgumentType.getInteger(context, "limit")))))
                .then(Commands.literal("export").executes(context -> export(context.getSource())))
                .then(Commands.literal("import")
                        .then(Commands.argument("snbt", StringArgumentType.greedyString())
                                .executes(context -> importRules(context.getSource(),
                                        StringArgumentType.getString(context, "snbt")))))
                .then(Commands.literal("version").executes(context -> version(context.getSource())))
                .then(Commands.literal("give").executes(context -> give(context.getSource())))
                .then(Commands.literal("families").executes(context -> families(context.getSource())))
                .then(Commands.literal("variants")
                        .then(Commands.argument("family", StringArgumentType.word())
                                .executes(context -> variants(context.getSource(),
                                        StringArgumentType.getString(context, "family"))))));
    }

    private static int version(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("wildcardencoder v" + WildcardEncoderMod.version()
                + "  (MC 1.20.1 / Forge 47 / AE2 15)"), false);
        return 1;
    }

    private static ItemStack held(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ItemStack stack = player.getMainHandItem();
        return stack.is(WildcardEncoderMod.WILDCARD_PATTERN.get()) ? stack : ItemStack.EMPTY;
    }

    private static int rules(CommandSourceStack source) {
        ItemStack stack;
        try {
            stack = held(source);
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            source.sendFailure(Component.literal("player only"));
            return 0;
        }
        if (stack.isEmpty()) {
            source.sendFailure(Component.translatable("wildcardencoder.msg.hold_pattern"));
            return 0;
        }
        WildcardRules rules = WildcardItemData.rules(stack);
        source.sendSuccess(() -> PatternText.summary(rules), false);
        for (Input input : rules.inputs()) {
            source.sendSuccess(() -> Component.literal("  in  " + PatternText.plainInput(input)), false);
        }
        for (Output output : rules.outputs()) {
            source.sendSuccess(() -> Component.literal("  out " + PatternText.plainOutput(output)), false);
        }
        for (Exclude exclude : rules.excludes()) {
            source.sendSuccess(() -> Component.literal("  ex  " + exclude.describe()), false);
        }
        source.sendSuccess(() -> Component.literal("  opts bind=" + rules.options().bindPerGroup()
                + " max=" + rules.options().maxInstances()
                + " optional=" + rules.options().maxOptionalExpansion()), false);
        return rules.inputs().size() + rules.outputs().size();
    }

    private static int preview(CommandSourceStack source, int limit) {
        ItemStack stack;
        try {
            stack = held(source);
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            source.sendFailure(Component.literal("player only"));
            return 0;
        }
        if (stack.isEmpty()) {
            source.sendFailure(Component.translatable("wildcardencoder.msg.hold_pattern"));
            return 0;
        }
        PatternInstantiator.Result result = PatternInstantiator.instantiate(WildcardItemData.rules(stack));
        if (!result.ok()) {
            source.sendFailure(result.error().component());
            return 0;
        }
        source.sendSuccess(() -> Component.literal("recipes=" + result.patterns().size()
                + " excluded=" + result.excluded() + (result.truncated() ? " (truncated)" : "")), false);
        int shown = 0;
        for (var pattern : result.patterns()) {
            if (shown >= limit) {
                break;
            }
            String line = "  " + PatternText.plain(pattern);
            source.sendSuccess(() -> Component.literal(line), false);
            shown++;
        }
        for (Msg warning : result.warnings()) {
            source.sendSuccess(() -> Component.literal("  ! " + warning.plain()), false);
        }
        return result.patterns().size();
    }

    private static int export(CommandSourceStack source) {
        ItemStack stack;
        try {
            stack = held(source);
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            source.sendFailure(Component.literal("player only"));
            return 0;
        }
        if (stack.isEmpty()) {
            source.sendFailure(Component.translatable("wildcardencoder.msg.hold_pattern"));
            return 0;
        }
        String snbt = RuleCodec.toSnbt(WildcardItemData.rules(stack));
        source.sendSuccess(() -> Component.literal(snbt), false);
        return 1;
    }

    private static int importRules(CommandSourceStack source, String snbt) {
        ItemStack stack;
        try {
            stack = held(source);
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            source.sendFailure(Component.literal("player only"));
            return 0;
        }
        if (stack.isEmpty()) {
            source.sendFailure(Component.translatable("wildcardencoder.msg.hold_pattern"));
            return 0;
        }
        WildcardRules rules = RuleCodec.fromSnbt(snbt);
        if (rules == null) {
            source.sendFailure(Component.translatable("wildcardencoder.msg.bad_snbt"));
            return 0;
        }
        WildcardItemData.setRules(stack, rules);
        source.sendSuccess(() -> Component.translatable("wildcardencoder.msg.imported",
                PatternText.summary(rules)), false);
        return 1;
    }

    private static int give(CommandSourceStack source) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            ItemStack stack = new ItemStack(WildcardEncoderMod.WILDCARD_PATTERN.get());
            if (!player.getInventory().add(stack)) {
                player.drop(stack, false);
            }
            source.sendSuccess(() -> Component.translatable("wildcardencoder.msg.given"), false);
            return 1;
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            source.sendFailure(Component.literal("player only"));
            return 0;
        }
    }

    private static int families(CommandSourceStack source) {
        Map<String, ResourceLocation> aliases = WildcardAliases.all();
        source.sendSuccess(() -> Component.literal("family aliases (" + aliases.size() + "):"), false);
        aliases.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> source.sendSuccess(
                        () -> Component.literal("  " + entry.getKey() + "* -> " + entry.getValue()), false));
        return aliases.size();
    }

    private static int variants(CommandSourceStack source, String family) {
        ResourceLocation location = WildcardAliases.resolve(family);
        if (location == null) {
            location = ResourceLocation.tryParse(family.indexOf(':') >= 0 ? family : "forge:" + family);
        }
        if (location == null || !TagIndex.get().hasTag(location)) {
            ResourceLocation unresolved = location;
            source.sendFailure(Component.literal("no such family: "
                    + (unresolved != null ? unresolved.toString() : family)));
            return 0;
        }
        List<String> keys = TagIndex.get().variantKeys(location);
        if (keys.isEmpty()) {
            source.sendFailure(Component.literal("no variants for " + location));
            return 0;
        }
        ResourceLocation root = location;
        source.sendSuccess(() -> Component.literal(root + " (" + keys.size() + " variants):"), false);
        source.sendSuccess(() -> Component.literal(String.join(", ", keys)), false);
        return keys.size();
    }
}
