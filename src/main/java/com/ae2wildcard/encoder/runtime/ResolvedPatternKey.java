package com.ae2wildcard.encoder.runtime;

import com.ae2wildcard.encoder.craft.ResolvedPattern;
import com.ae2wildcard.encoder.rule.WildcardRules.NbtMatch;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * The "definition key" of one instantiated wildcard recipe.
 *
 * <p>AE2 persists in-flight crafting jobs by writing {@code IPatternDetails.getDefinition()} and later
 * resolving it again with {@code PatternDetailsHelper.decodePattern(AEItemKey, Level)}. A wildcard
 * pattern item alone expands to many recipes, so every instantiated recipe gets its <b>own</b>
 * definition key: our wildcard pattern item plus a {@code we_res} tag holding the fully resolved
 * recipe. That way a job survives a world reload exactly, even if tags changed in between.
 */
public final class ResolvedPatternKey {

    /** Tag holding the resolved recipe inside the definition key. */
    public static final String RESOLVED_KEY = "we_res";

    private static final String INPUTS = "i";
    private static final String OUTPUTS = "o";
    private static final String VARIANT = "v";

    private ResolvedPatternKey() {
    }

    public static CompoundTag of(ResolvedPattern pattern) {
        CompoundTag tag = new CompoundTag();
        tag.putString(VARIANT, pattern.variant());

        ListTag inputs = new ListTag();
        for (ResolvedPattern.Input input : pattern.inputs()) {
            CompoundTag entry = new CompoundTag();
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(input.item());
            if (id == null) {
                continue;
            }
            entry.putString("id", id.toString());
            entry.putLong("n", input.amount());
            if (!input.consume()) {
                entry.putBoolean("keep", true);
            }
            if (input.nbt() != null) {
                entry.put("nbt", input.nbt().copy());
            }
            inputs.add(entry);
        }
        tag.put(INPUTS, inputs);

        ListTag outputs = new ListTag();
        for (ResolvedPattern.Output output : pattern.outputs()) {
            CompoundTag entry = new CompoundTag();
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(output.item());
            if (id == null) {
                continue;
            }
            entry.putString("id", id.toString());
            entry.putLong("n", output.amount());
            if (output.nbt() != null) {
                entry.put("nbt", output.nbt().copy());
            }
            outputs.add(entry);
        }
        tag.put(OUTPUTS, outputs);
        return tag;
    }

    /** @return the resolved recipe, or {@code null} when the tag is not a resolved recipe */
    public static ResolvedPattern read(CompoundTag tag) {
        if (tag == null || !tag.contains(RESOLVED_KEY, Tag.TAG_COMPOUND)) {
            return null;
        }
        CompoundTag data = tag.getCompound(RESOLVED_KEY);
        try {
            java.util.List<ResolvedPattern.Input> inputs = new java.util.ArrayList<>();
            for (Tag element : data.getList(INPUTS, Tag.TAG_COMPOUND)) {
                CompoundTag entry = (CompoundTag) element;
                Item item = item(entry.getString("id"));
                if (item == null) {
                    return null;
                }
                inputs.add(ResolvedPattern.Input.of(item, Math.max(1, entry.getLong("n")),
                        entry.contains("nbt", Tag.TAG_COMPOUND) ? entry.getCompound("nbt") : null,
                        !entry.getBoolean("keep")));
            }
            java.util.List<ResolvedPattern.Output> outputs = new java.util.ArrayList<>();
            for (Tag element : data.getList(OUTPUTS, Tag.TAG_COMPOUND)) {
                CompoundTag entry = (CompoundTag) element;
                Item item = item(entry.getString("id"));
                if (item == null) {
                    return null;
                }
                outputs.add(ResolvedPattern.Output.of(item, Math.max(1, entry.getLong("n")),
                        entry.contains("nbt", Tag.TAG_COMPOUND) ? entry.getCompound("nbt") : null));
            }
            if (inputs.isEmpty() || outputs.isEmpty()) {
                return null;
            }
            return new ResolvedPattern(data.getString(VARIANT), inputs, outputs);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static boolean isResolved(ItemStack stack) {
        ItemStack probe = stack;
        return probe.hasTag() && probe.getTag().contains(RESOLVED_KEY, Tag.TAG_COMPOUND);
    }

    public static String describeNbt(java.util.List<ResolvedPattern.Input> inputs) {
        return inputs.isEmpty() ? "-" : NbtMatch.describe(inputs.get(0).nbt());
    }

    private static Item item(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        if (location == null) {
            return null;
        }
        Item item = ForgeRegistries.ITEMS.getValue(location);
        return item == null || item == Items.AIR ? null : item;
    }
}
