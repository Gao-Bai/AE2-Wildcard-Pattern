package com.ae2wildcard.encoder.craft;

import java.util.List;

import com.ae2wildcard.encoder.rule.WildcardRules;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * One fully resolved recipe of a wildcard pattern: concrete items only, still an in-memory object
 * (never an item stack). This is what the runtime layer turns into an AE2 {@code IPatternDetails}.
 *
 * @param variant the material/variant this recipe was bound to ({@code ""} when the rule has no
 *                wildcard part)
 */
public record ResolvedPattern(String variant, List<Input> inputs, List<Output> outputs) {

    /** A concrete input. {@code nbt} is a subset template (null = any NBT). */
    public record Input(Item item, long amount, CompoundTag nbt, boolean consume) {

        public static Input of(Item item, long amount, CompoundTag nbt, boolean consume) {
            return new Input(item, amount, nbt == null || nbt.isEmpty() ? null : nbt.copy(), consume);
        }

        public ItemStack toStack(int count) {
            ItemStack stack = new ItemStack(item, count);
            if (nbt != null) {
                stack.setTag(nbt.copy());
            }
            return stack;
        }

        public boolean matches(ItemStack stack) {
            return stack.is(item) && WildcardRules.NbtMatch.subset(stack.getTag(), nbt);
        }
    }

    /** A concrete output. {@code nbt} is written onto the output item (null = plain item). */
    public record Output(Item item, long amount, CompoundTag nbt) {

        public static Output of(Item item, long amount, CompoundTag nbt) {
            return new Output(item, amount, nbt == null || nbt.isEmpty() ? null : nbt.copy());
        }

        public ItemStack toStack(int count) {
            ItemStack stack = new ItemStack(item, count);
            if (nbt != null) {
                stack.setTag(nbt.copy());
            }
            return stack;
        }
    }

    public boolean hasWildcard() {
        return !variant.isEmpty();
    }

    public Item primaryOutput() {
        return outputs.isEmpty() ? null : outputs.get(0).item();
    }

    public ResourceLocation primaryOutputId() {
        Item item = primaryOutput();
        return item == null ? null : net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(item);
    }

    /** Convenience for logs/preview: the matcher that produced this recipe is not stored here. */
    public String describeVariant() {
        return variant.isEmpty() ? "(fixed)" : variant;
    }
}
