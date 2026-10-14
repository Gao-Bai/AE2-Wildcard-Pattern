package com.ae2wildcard.encoder.runtime;

import java.util.ArrayList;
import java.util.List;

import com.ae2wildcard.encoder.WildcardEncoderMod;
import com.ae2wildcard.encoder.craft.ResolvedPattern;
import com.ae2wildcard.encoder.rule.WildcardRules.NbtMatch;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * One instantiated wildcard recipe, presented to AE2 as a normal {@code IPatternDetails}.
 *
 * <p>This object only lives in memory - it is never written into an item (except as the definition key
 * that AE2 itself persists with a crafting job). The definition key carries the resolved recipe so AE2
 * can resolve it again after a reload.
 */
public final class WildcardPatternDetails implements IPatternDetails {

    private final ResolvedPattern pattern;
    private final AEItemKey definition;
    private final IInput[] inputs;
    private final GenericStack[] outputs;

    public WildcardPatternDetails(ResolvedPattern pattern) {
        this.pattern = pattern;

        CompoundTag tag = new CompoundTag();
        tag.put(ResolvedPatternKey.RESOLVED_KEY, ResolvedPatternKey.of(pattern));
        this.definition = AEItemKey.of(WildcardEncoderMod.WILDCARD_PATTERN.get(), tag);

        this.inputs = new IInput[pattern.inputs().size()];
        for (int i = 0; i < inputs.length; i++) {
            inputs[i] = new PatternInput(pattern.inputs().get(i));
        }

        this.outputs = new GenericStack[pattern.outputs().size()];
        for (int i = 0; i < outputs.length; i++) {
            ResolvedPattern.Output output = pattern.outputs().get(i);
            ItemStack stack = output.toStack(1);
            outputs[i] = new GenericStack(AEItemKey.of(stack), output.amount());
        }
    }

    public ResolvedPattern pattern() {
        return pattern;
    }

    @Override
    public AEItemKey getDefinition() {
        return definition;
    }

    @Override
    public IInput[] getInputs() {
        return inputs;
    }

    @Override
    public GenericStack[] getOutputs() {
        return outputs;
    }

    @Override
    public boolean supportsPushInputsToExternalInventory() {
        return true;
    }

    /**
     * Pushes only the inputs that are meant to be consumed. Inputs marked "not consumed" are skipped
     * here on purpose: AE2 gets them back through {@link IInput#getRemainingKey} and returns them to the
     * network, which is exactly the "真·不消耗" behaviour.
     */
    @Override
    public void pushInputsToExternalInventory(KeyCounter[] inputHolder, PatternInputSink sink) {
        for (int i = 0; i < inputs.length && i < inputHolder.length; i++) {
            if (!pattern.inputs().get(i).consume()) {
                continue;
            }
            KeyCounter counter = inputHolder[i];
            if (counter == null) {
                continue;
            }
            for (var entry : counter) {
                sink.pushInput(entry.getKey(), entry.getLongValue());
            }
        }
    }

    /** The stacks this pattern would push into a machine (used to find a valid target). */
    public List<GenericStack> pushableInputs(KeyCounter[] inputHolder) {
        List<GenericStack> collected = new ArrayList<>();
        pushInputsToExternalInventory(inputHolder, (what, amount) -> {
            if (amount > 0) {
                collected.add(new GenericStack(what, amount));
            }
        });
        return collected;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof WildcardPatternDetails details && definition.equals(details.definition);
    }

    @Override
    public int hashCode() {
        return definition.hashCode();
    }

    @Override
    public String toString() {
        return "WildcardPattern[" + pattern.describeVariant() + "]";
    }

    /** One input of the recipe. */
    private static final class PatternInput implements IInput {

        private final ResolvedPattern.Input input;
        private final GenericStack[] possible;

        PatternInput(ResolvedPattern.Input input) {
            this.input = input;
            ItemStack stack = input.toStack(1);
            if (input.nbt() != null) {
                // The exact key is what AE2 pulls out of the network, and it has to come first: for an
                // item whose state lives in NBT (a GregTech circuit, for instance) there is no plain
                // item in the network at all. The bare item is offered as a second option so a machine
                // that ignores NBT can still be satisfied.
                this.possible = new GenericStack[] {
                        new GenericStack(AEItemKey.of(stack), input.amount()),
                        new GenericStack(AEItemKey.of(new ItemStack(input.item())), input.amount()) };
            } else {
                this.possible = new GenericStack[] {
                        new GenericStack(AEItemKey.of(stack), input.amount()) };
            }
        }

        @Override
        public GenericStack[] getPossibleInputs() {
            return possible;
        }

        @Override
        public long getMultiplier() {
            return 1;
        }

        @Override
        public boolean isValid(AEKey candidate, Level level) {
            if (!(candidate instanceof AEItemKey itemKey) || itemKey.getItem() != input.item()) {
                return false;
            }
            // no NBT condition means "any configuration of this item"
            return input.nbt() == null || NbtMatch.subset(itemKey.getTag(), input.nbt());
        }

        @Override
        public AEKey getRemainingKey(AEKey template) {
            // not consumed -> AE2 returns this key to the network instead of the machine
            return input.consume() ? null : template;
        }

        @Override
        public String toString() {
            return "WildcardInput[" + input.item() + " x" + input.amount()
                    + (input.consume() ? "" : " keep") + "]";
        }
    }
}
