package com.ae2wildcard.encoder.block;

import java.util.ArrayList;
import java.util.List;

import com.ae2wildcard.encoder.WildcardEncoderMod;
import com.ae2wildcard.encoder.craft.PatternInstantiator;
import com.ae2wildcard.encoder.craft.ResolvedPattern;
import com.ae2wildcard.encoder.craft.TagIndex;
import com.ae2wildcard.encoder.item.WildcardItemData;
import com.ae2wildcard.encoder.rule.WildcardRules;
import com.ae2wildcard.encoder.runtime.WildcardPatternDetails;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.GridFlags;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.MEStorage;
import appeng.api.util.AECableType;
import appeng.capabilities.Capabilities;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;

/**
 * The wildcard pattern provider: an AE2 crafting provider that turns the rules stored in the wildcard
 * patterns it holds into concrete recipes <b>at runtime, in memory</b>.
 *
 * <p>This is the whole reason no mixin is needed: AE2's public
 * {@link GridHelper#createManagedNode} + {@link ICraftingProvider} + {@code IPatternDetails} are enough
 * to be a first class crafting provider on the ME network.
 *
 * <p>Nothing is ever written into concrete pattern items: {@link #getAvailablePatterns()} instantiates
 * the rules on demand (cached by rule content + tag revision) and the resulting objects are thrown away
 * with the cache.
 */
public class WildcardPatternProviderBlockEntity extends BlockEntity
        implements IInWorldGridNodeHost, ICraftingProvider, IActionHost {

    /** Insertions into the network are attributed to this block, like AE2's own provider does. */
    private final IActionSource returnSource = IActionSource.ofMachine(this);

    public static final int SLOTS = 9;
    public static final int RETURN_SLOTS = 9;

    private final ItemStackHandler inventory = new ItemStackHandler(SLOTS) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
            invalidatePatterns();
            ICraftingProvider.requestUpdate(mainNode);
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return stack.is(WildcardEncoderMod.WILDCARD_PATTERN.get());
        }
    };

    private final LazyOptional<IItemHandler> itemHandler = LazyOptional.of(() -> inventory);

    /**
     * Return slot. A machine with auto-eject pushes its products in here (this is what AE2's pattern
     * provider does too), and the server tick injects them back into the ME network.
     */
    private final ItemStackHandler returnInventory = new ItemStackHandler(RETURN_SLOTS) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }
    };

    private final LazyOptional<IItemHandler> returnHandler = LazyOptional.of(() -> returnInventory);

    private final IManagedGridNode mainNode = GridHelper
            .createManagedNode(this, (owner, node) -> owner.setChanged())
            .setVisualRepresentation(WildcardEncoderMod.WILDCARD_PATTERN.get())
            .setInWorldNode(true)
            .setTagName("gridnode")
            .setFlags(GridFlags.REQUIRE_CHANNEL)
            .setIdlePowerUsage(1.0)
            .addService(ICraftingProvider.class, this);

    private List<IPatternDetails> patterns = List.of();
    private String patternsKey = "";
    private int lastWarning = 0;

    public WildcardPatternProviderBlockEntity(BlockPos pos, BlockState state) {
        super(WildcardEncoderMod.WILDCARD_PATTERN_PROVIDER_BE.get(), pos, state);
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onLoad() {
        super.onLoad();
        GridHelper.onFirstTick(this, be -> be.mainNode.create(be.getLevel(), be.getBlockPos()));
    }

    @Override
    public void setRemoved() {
        mainNode.destroy();
        super.setRemoved();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        mainNode.saveToNBT(tag);
        tag.put("inventory", inventory.serializeNBT());
        tag.put("returns", returnInventory.serializeNBT());
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        mainNode.loadFromNBT(tag);
        inventory.deserializeNBT(tag.getCompound("inventory"));
        returnInventory.deserializeNBT(tag.getCompound("returns"));
    }

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> capability, Direction side) {
        if (capability == Capabilities.IN_WORLD_GRID_NODE_HOST) {
            return LazyOptional.of(() -> this).cast();
        }
        if (capability == ForgeCapabilities.ITEM_HANDLER) {
            // neighbours (machines with auto-eject) insert into the return slot, exactly like AE2's
            // pattern provider; patterns themselves are inserted by right-clicking the block
            return returnHandler.cast();
        }
        return super.getCapability(capability, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        itemHandler.invalidate();
        returnHandler.invalidate();
    }

    // ------------------------------------------------------------------ grid host

    @Override
    public IGridNode getGridNode(Direction dir) {
        return mainNode.getNode();
    }

    @Override
    public AECableType getCableConnectionType(Direction dir) {
        return AECableType.SMART;
    }

    @Override
    public IGridNode getActionableNode() {
        return mainNode.getNode();
    }

    public IManagedGridNode mainNode() {
        return mainNode;
    }

    public IItemHandler itemHandler() {
        return inventory;
    }

    // ------------------------------------------------------------------ crafting provider

    @Override
    public List<IPatternDetails> getAvailablePatterns() {
        String key = currentKey();
        if (!key.equals(patternsKey)) {
            patternsKey = key;
            patterns = instantiate();
        }
        return patterns;
    }

    /** Rebuilds the in-memory recipes. Public so the cache can be dropped explicitly. */
    public void invalidatePatterns() {
        patternsKey = "";
    }

    private List<IPatternDetails> instantiate() {
        List<IPatternDetails> result = new ArrayList<>();
        int skipped = 0;
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.isEmpty() || !stack.is(WildcardEncoderMod.WILDCARD_PATTERN.get())) {
                continue;
            }
            WildcardRules rules = WildcardItemData.rules(stack);
            PatternInstantiator.Result instantiated = PatternInstantiator.instantiate(rules);
            if (!instantiated.ok()) {
                skipped++;
                continue;
            }
            for (ResolvedPattern pattern : instantiated.patterns()) {
                result.add(new WildcardPatternDetails(pattern));
            }
        }
        if (skipped > 0 && lastWarning != skipped) {
            lastWarning = skipped;
            WildcardEncoderMod.LOGGER.warn("{} wildcard pattern(s) in the provider at {} could not be expanded",
                    skipped, worldPosition);
        }
        return List.copyOf(result);
    }

    private String currentKey() {
        StringBuilder builder = new StringBuilder().append(TagIndex.revision());
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            builder.append('|');
            if (!stack.isEmpty()) {
                builder.append(stack.getItem());
                CompoundTag tag = WildcardItemData.rulesTag(stack);
                builder.append('#').append(tag == null ? 0 : tag.hashCode());
            }
        }
        return builder.toString();
    }

    @Override
    public boolean isBusy() {
        // we have no outgoing send buffer, so we are never busy
        return false;
    }

    @Override
    public boolean pushPattern(IPatternDetails details, KeyCounter[] inputHolder) {
        if (!(details instanceof WildcardPatternDetails wildcard) || level == null) {
            return false;
        }
        List<GenericStack> inputs = wildcard.pushableInputs(inputHolder);
        if (inputs.isEmpty()) {
            return false;
        }
        for (Direction direction : Direction.values()) {
            BlockEntity neighbour = level.getBlockEntity(worldPosition.relative(direction));
            if (neighbour == null) {
                continue;
            }
            IItemHandler handler = neighbour
                    .getCapability(ForgeCapabilities.ITEM_HANDLER, direction.getOpposite()).orElse(null);
            if (handler == null) {
                continue;
            }
            if (insertAll(handler, inputs, true)) {
                insertAll(handler, inputs, false);
                return true;
            }
        }
        return false;
    }

    private static boolean insertAll(IItemHandler handler, List<GenericStack> inputs, boolean simulate) {
        List<ItemStack> stacks = new ArrayList<>();
        for (GenericStack input : inputs) {
            if (!(input.what() instanceof AEItemKey itemKey)) {
                return false;
            }
            long remaining = input.amount();
            int max = Math.max(1, itemKey.getMaxStackSize());
            while (remaining > 0) {
                int chunk = (int) Math.min(max, remaining);
                stacks.add(itemKey.toStack(chunk));
                remaining -= chunk;
            }
        }
        // simulate first so we never half-insert
        List<ItemStack> leftovers = new ArrayList<>();
        for (ItemStack stack : stacks) {
            leftovers.add(insert(handler, stack, simulate));
        }
        for (ItemStack leftover : leftovers) {
            if (!leftover.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static ItemStack insert(IItemHandler handler, ItemStack stack, boolean simulate) {
        ItemStack remaining = stack;
        for (int slot = 0; slot < handler.getSlots() && !remaining.isEmpty(); slot++) {
            remaining = handler.insertItem(slot, remaining, simulate);
        }
        return remaining;
    }

    // ------------------------------------------------------------------ return slot

    /** Called every server tick: hand whatever a machine ejected back to the ME network. */
    public void serverTick() {
        if (returnInventory.getSlots() == 0 || level == null || level.isClientSide || !mainNode.isActive()) {
            return;
        }
        boolean hasItems = false;
        for (int slot = 0; slot < returnInventory.getSlots(); slot++) {
            if (!returnInventory.getStackInSlot(slot).isEmpty()) {
                hasItems = true;
                break;
            }
        }
        if (!hasItems) {
            return;
        }
        IGrid grid = mainNode.getGrid();
        if (grid == null) {
            return;
        }
        MEStorage storage = grid.getStorageService().getInventory();
        for (int slot = 0; slot < returnInventory.getSlots(); slot++) {
            ItemStack stack = returnInventory.getStackInSlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            AEItemKey key = AEItemKey.of(stack);
            long inserted = storage.insert(key, stack.getCount(), Actionable.MODULATE, returnSource);
            if (inserted > 0) {
                stack.shrink((int) inserted);
                returnInventory.setStackInSlot(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
            }
        }
        setChanged();
    }

    public int returnCount() {
        int count = 0;
        for (int slot = 0; slot < returnInventory.getSlots(); slot++) {
            count += returnInventory.getStackInSlot(slot).getCount();
        }
        return count;
    }

    /** Drops the pattern slots and the return slot when the block is broken. */
    public void dropContents() {
        if (level == null) {
            return;
        }
        for (int slot = 0; slot < returnInventory.getSlots(); slot++) {
            ItemStack stack = returnInventory.getStackInSlot(slot);
            if (!stack.isEmpty()) {
                Block.popResource(level, worldPosition, stack);
                returnInventory.setStackInSlot(slot, ItemStack.EMPTY);
            }
        }
        for (ItemStack stack : ejectPatterns()) {
            Block.popResource(level, worldPosition, stack);
        }
    }

    // ------------------------------------------------------------------ pattern slots

    public int patternCount() {
        int count = 0;
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            if (!inventory.getStackInSlot(slot).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    public int availablePatternCount() {
        return getAvailablePatterns().size();
    }

    /** Inserts one wildcard pattern from the player's hand into the first free slot. */
    public boolean insertPattern(ItemStack stack) {
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            if (inventory.getStackInSlot(slot).isEmpty()) {
                ItemStack single = stack.copy();
                single.setCount(1);
                ItemStack leftover = inventory.insertItem(slot, single, false);
                if (leftover.isEmpty()) {
                    stack.shrink(1);
                    return true;
                }
                return false;
            }
        }
        return false;
    }

    public List<ItemStack> ejectPatterns() {
        List<ItemStack> ejected = new ArrayList<>();
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (!stack.isEmpty()) {
                ejected.add(stack);
                inventory.setStackInSlot(slot, ItemStack.EMPTY);
            }
        }
        invalidatePatterns();
        return ejected;
    }
}
