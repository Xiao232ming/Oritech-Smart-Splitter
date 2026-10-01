package com.example.oritechsplitter.block.entity;

import com.example.oritechsplitter.block.SmartSplitterBlock;
import com.example.oritechsplitter.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Ported from Oritech 2.0.0 (Minecraft 26.1.2),
 * {@code rearth.oritech.block.entity.pipes.SmartSplitterBlockEntity}.
 *
 * <h2>Porting notes</h2>
 * <p>Upstream is written against the newer NeoForge transfer API
 * ({@code ResourceHandler<ItemResource>}, {@code TransactionContext}, {@code SnapshotJournal}).
 * None of that exists on Minecraft 1.21.1, so the same behaviour is expressed with the 1.21.1
 * standard item API, {@link IItemHandler}:
 *
 * <ul>
 *   <li>Upstream mutates its reservations/cursors inside a transaction and relies on
 *       {@code SnapshotJournal} to roll them back when the transaction aborts. On 1.21.1 the
 *       equivalent of "this operation may not stick" is {@code simulate == true}, so every
 *       simulated call captures {@link SplitterSnapshot} and reverts it before returning. That keeps
 *       upstream's guarantee that <em>simulated or rolled-back extractions do not consume a turn</em>
 *       and do not consume reservations.</li>
 *   <li>Upstream's {@code SimpleInventoryStorage} (one slot, capacity = the item's max stack size,
 *       any item accepted) is replaced by a single {@link ItemStack} field with the same semantics.</li>
 *   <li>Upstream probes for new outputs in {@code getResource}, its read path. That does not
 *       translate to 1.21.1: {@link IItemHandler#getStackInSlot(int)} is a generic read that an
 *       <em>inserting</em> pipe also uses, so probing there marked receiving sides as outputs and
 *       blocked every insert. The probe therefore lives in {@code extractItem}, the actual 1.21.1
 *       extraction path, which keeps upstream's rule that a simulated extraction still teaches the
 *       splitter which side is an output. The one behavioural difference is that a side is no longer
 *       learned while the splitter is empty, because a pipe skips empty slots before probing; the
 *       side is learned as soon as the first item arrives.</li>
 *   <li>Block entity save/load uses 1.21.1's {@code CompoundTag} + {@code HolderLookup.Provider}
 *       signatures instead of {@code ValueInput}/{@code ValueOutput}.</li>
 * </ul>
 */
public class SmartSplitterBlockEntity extends BlockEntity {

    /** Ticks an output may make no progress before Overflow mode reclaims its reservation. */
    public static final int OVERFLOW_DELAY = 20;

    private static final Direction[] OUTPUT_DIRECTIONS = {
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
    };

    private static final int DEFAULT_SLOT_LIMIT = 64;

    /** The single stored stack. The splitter holds one stack at a time and cannot mix item types. */
    private ItemStack stored = ItemStack.EMPTY;

    private final int[] reservations = new int[OUTPUT_DIRECTIONS.length];
    private final long[] lastProgress = new long[OUTPUT_DIRECTIONS.length];
    private final Set<Direction> insertedInputSides = EnumSet.noneOf(Direction.class);
    private final EnumMap<Direction, SplitterItemHandler> sidedHandlers = new EnumMap<>(Direction.class);
    private final SplitterItemHandler unsidedHandler = new SplitterItemHandler(null);

    private SplitMode mode = SplitMode.STRICT;
    private int remainderCursor;
    private int roundRobinCursor;

    /** Guards {@link #configureOutputOnExtractionProbe} against re-entering itself via neighbour updates. */
    private boolean probingOutput;

    public SmartSplitterBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SMART_SPLITTER.get(), pos, state);
        for (Direction direction : Direction.values()) {
            sidedHandlers.put(direction, new SplitterItemHandler(direction));
        }
    }

    /** Returns the capability instance for a side, or the unsided view when {@code direction} is null. */
    public IItemHandler getItemHandler(@Nullable Direction direction) {
        return direction == null ? unsidedHandler : sidedHandlers.get(direction);
    }

    public SplitMode getMode() {
        return mode;
    }

    public SplitMode cycleMode() {
        mode = mode.next();
        if (mode != SplitMode.ROUND_ROBIN) {
            rebalanceStoredItems();
        } else {
            normalizeRoundRobinCursor();
            setChanged();
        }
        return mode;
    }

    /** Called after a side was toggled between output and closed. */
    public void onOutputConfigurationChanged() {
        if (mode == SplitMode.ROUND_ROBIN) {
            normalizeRoundRobinCursor();
            setChanged();
        } else {
            rebalanceStoredItems();
        }
    }

    public void serverTick(Level level) {
        if (!level.isClientSide() && !stored.isEmpty() && level.getGameTime() % 5L == 0L) {
            if (mode == SplitMode.OVERFLOW) {
                redistributeStaleReservations(level.getGameTime());
            }
        }
    }

    // ------------------------------------------------------------------
    // Storage primitives (a single slot, mirroring SimpleInventoryStorage)
    // ------------------------------------------------------------------

    private int insertIntoStorage(ItemStack stack, boolean simulate) {
        if (stack.isEmpty()) {
            return 0;
        }
        if (!stored.isEmpty() && !ItemStack.isSameItemSameComponents(stored, stack)) {
            return 0;
        }

        int limit = stored.isEmpty() ? stack.getMaxStackSize() : stored.getMaxStackSize();
        int space = limit - stored.getCount();
        if (space <= 0) {
            return 0;
        }

        int accepted = Math.min(space, stack.getCount());
        if (!simulate) {
            if (stored.isEmpty()) {
                stored = stack.copyWithCount(accepted);
            } else {
                stored.grow(accepted);
            }
        }
        return accepted;
    }

    private ItemStack extractFromStorage(int amount, boolean simulate) {
        if (stored.isEmpty() || amount <= 0) {
            return ItemStack.EMPTY;
        }

        int extracted = Math.min(amount, stored.getCount());
        if (extracted <= 0) {
            return ItemStack.EMPTY;
        }

        ItemStack result = stored.copyWithCount(extracted);
        if (!simulate) {
            stored.shrink(extracted);
            if (stored.isEmpty()) {
                stored = ItemStack.EMPTY;
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Item handler operations
    // ------------------------------------------------------------------

    private ItemStack insert(@Nullable Direction side, int index, ItemStack stack, boolean simulate) {
        if (stack.isEmpty()) {
            return stack;
        }
        // Items may also be inserted from above or below, but only horizontal sides can be outputs,
        // and an output never accepts items back.
        if (isOutputSide(side)) {
            return stack;
        }

        boolean wasEmpty = stored.isEmpty();
        int accepted = insertIntoStorage(stack, simulate);
        if (accepted <= 0) {
            return stack;
        }

        SplitterSnapshot snapshot = simulate ? captureSnapshot() : null;

        // A side that received items becomes an input, unless it was already configured as an output.
        if (side != null && side.getAxis().isHorizontal()
                && SmartSplitterBlock.getSideMode(getBlockState(), side) == SmartSplitterBlock.SideMode.CLOSED) {
            insertedInputSides.add(side);
        }

        if (mode == SplitMode.ROUND_ROBIN) {
            if (wasEmpty) {
                normalizeRoundRobinCursor();
            }
        } else {
            addReservations(accepted, activeOutputs());
        }

        if (simulate) {
            revertToSnapshot(snapshot);
        } else {
            applyCommittedInputSides();
            setChanged();
        }

        return stack.copyWithCount(stack.getCount() - accepted);
    }

    private ItemStack extract(@Nullable Direction side, int index, int amount, boolean simulate) {
        // Probing happens before any other check so that pipes which only inspect the block still
        // teach the splitter which side they are on.
        configureOutputOnExtractionProbe(side);

        if (side == null || !isConfiguredOutput(side) || amount == 0) {
            return ItemStack.EMPTY;
        }

        int sideIndex = outputIndex(side);
        int allowed = amount;

        if (mode == SplitMode.ROUND_ROBIN) {
            // Only one output may extract at a time; simulated extractions do not consume a turn.
            if (currentRoundRobinOutput() != side) {
                return ItemStack.EMPTY;
            }
        } else {
            // Strict and Overflow only hand out what was reserved for this side.
            allowed = Math.min(amount, reservations[sideIndex]);
            if (allowed <= 0) {
                return ItemStack.EMPTY;
            }
        }

        ItemStack extracted = extractFromStorage(allowed, simulate);
        if (extracted.isEmpty()) {
            return ItemStack.EMPTY;
        }

        SplitterSnapshot snapshot = simulate ? captureSnapshot() : null;

        lastProgress[sideIndex] = gameTime();
        if (mode == SplitMode.ROUND_ROBIN) {
            advanceRoundRobin(side);
        } else {
            reservations[sideIndex] -= extracted.getCount();
        }

        if (simulate) {
            revertToSnapshot(snapshot);
        } else {
            setChanged();
        }

        return extracted;
    }

    /**
     * Marks a horizontal side as an output the first time something tries to extract through it.
     * Mirrors upstream: this also happens for purely simulated probes, so a pipe that only asks
     * whether it could extract still teaches the splitter that the side is an output.
     *
     * <p>Called from {@link #extract}, which is the 1.21.1 extraction path. It is deliberately not
     * called from {@code getStackInSlot}: see the note on {@code SplitterItemHandler} for why.
     */
    private void configureOutputOnExtractionProbe(@Nullable Direction side) {
        // level.setBlock below fires neighbour updates, which can re-enter this block's handlers.
        // The state is applied before those updates run, so a re-entrant call would already see the
        // new output side, but the guard keeps that from depending on ordering.
        if (probingOutput) {
            return;
        }
        if (side != null
                && side.getAxis().isHorizontal()
                && !isConfiguredOutput(side)
                && level != null
                && !level.isClientSide()
                && !isRemoved()) {
            probingOutput = true;
            try {
                BlockState updatedState = SmartSplitterBlock.setSideMode(
                        getBlockState(), side, SmartSplitterBlock.SideMode.OUTPUT);
                level.setBlock(worldPosition, updatedState, Block.UPDATE_ALL);
                onOutputConfigurationChanged();
            } finally {
                probingOutput = false;
            }
        }
    }

    // ------------------------------------------------------------------
    // Reservation bookkeeping
    // ------------------------------------------------------------------

    private void addReservations(int amount, List<Direction> outputs) {
        if (amount > 0 && !outputs.isEmpty()) {
            distribute(amount, outputs, gameTime());
        }
    }

    private void distribute(int amount, List<Direction> outputs, long now) {
        int evenShare = amount / outputs.size();
        if (evenShare > 0) {
            for (Direction output : outputs) {
                addReservation(output, evenShare, now);
            }
        }

        // Remainder items rotate between the outputs over subsequent insertions.
        int remainder = amount % outputs.size();
        while (remainder-- > 0) {
            Direction output = nextOutput(outputs, remainderCursor);
            addReservation(output, 1, now);
            remainderCursor = (outputIndex(output) + 1) % OUTPUT_DIRECTIONS.length;
        }
    }

    private void addReservation(Direction output, int amount, long now) {
        int index = outputIndex(output);
        if (reservations[index] == 0) {
            lastProgress[index] = now;
        }
        reservations[index] += amount;
    }

    private void rebalanceStoredItems() {
        Arrays.fill(reservations, 0);
        List<Direction> outputs = activeOutputs();
        if (!outputs.isEmpty()) {
            distribute(stored.getCount(), outputs, gameTime());
        }
        setChanged();
    }

    private void redistributeStaleReservations(long now) {
        List<Direction> active = activeOutputs();
        if (active.size() < 2) {
            return;
        }

        List<Direction> stale = new ArrayList<>();
        List<Direction> recipients = new ArrayList<>();

        for (Direction output : active) {
            int index = outputIndex(output);
            if (reservations[index] > 0 && now - lastProgress[index] >= OVERFLOW_DELAY) {
                stale.add(output);
            } else {
                recipients.add(output);
            }
        }

        if (stale.isEmpty() || recipients.isEmpty()) {
            return;
        }

        int reclaimed = 0;
        for (Direction output : stale) {
            int index = outputIndex(output);
            reclaimed += reservations[index];
            reservations[index] = 0;
        }

        distribute(reclaimed, recipients, now);
        setChanged();
    }

    // ------------------------------------------------------------------
    // Round robin
    // ------------------------------------------------------------------

    private void normalizeRoundRobinCursor() {
        Direction current = currentRoundRobinOutput();
        if (current != null) {
            roundRobinCursor = outputIndex(current);
        }
    }

    private void advanceRoundRobin(@Nullable Direction completedOutput) {
        int start = completedOutput == null ? roundRobinCursor + 1 : outputIndex(completedOutput) + 1;
        List<Direction> outputs = activeOutputs();
        if (!outputs.isEmpty()) {
            roundRobinCursor = outputIndex(nextOutput(outputs, start));
        }
    }

    @Nullable
    private Direction currentRoundRobinOutput() {
        List<Direction> outputs = activeOutputs();
        return outputs.isEmpty() ? null : nextOutput(outputs, roundRobinCursor);
    }

    private static Direction nextOutput(List<Direction> outputs, int start) {
        for (int offset = 0; offset < OUTPUT_DIRECTIONS.length; offset++) {
            Direction candidate = OUTPUT_DIRECTIONS[Math.floorMod(start + offset, OUTPUT_DIRECTIONS.length)];
            if (outputs.contains(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("No splitter output available");
    }

    private List<Direction> activeOutputs() {
        List<Direction> result = new ArrayList<>(OUTPUT_DIRECTIONS.length);
        for (Direction direction : OUTPUT_DIRECTIONS) {
            if (isConfiguredOutput(direction)) {
                result.add(direction);
            }
        }
        return result;
    }

    private boolean isConfiguredOutput(Direction direction) {
        return direction.getAxis().isHorizontal() && SmartSplitterBlock.isOutput(getBlockState(), direction);
    }

    private boolean isOutputSide(@Nullable Direction side) {
        return side != null && side.getAxis().isHorizontal() && SmartSplitterBlock.isOutput(getBlockState(), side);
    }

    private long gameTime() {
        return level == null ? 0L : level.getGameTime();
    }

    private static int outputIndex(Direction direction) {
        return switch (direction) {
            case NORTH -> 0;
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> throw new IllegalArgumentException("Not a horizontal direction: " + direction);
        };
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);

        if (!stored.isEmpty()) {
            tag.put("inventory", stored.saveOptional(registries));
        }
        tag.putInt("split_mode", mode.ordinal());
        tag.putInt("remainder_cursor", remainderCursor);
        tag.putInt("round_robin_cursor", roundRobinCursor);

        for (Direction direction : OUTPUT_DIRECTIONS) {
            String name = direction.getName();
            int index = outputIndex(direction);
            tag.putInt("reserved_" + name, reservations[index]);
            tag.putLong("last_progress_" + name, lastProgress[index]);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);

        stored = tag.contains("inventory")
                ? ItemStack.parseOptional(registries, tag.getCompound("inventory"))
                : ItemStack.EMPTY;

        mode = SplitMode.fromOrdinal(tag.getInt("split_mode"));
        remainderCursor = Math.floorMod(tag.getInt("remainder_cursor"), OUTPUT_DIRECTIONS.length);
        roundRobinCursor = Math.floorMod(tag.getInt("round_robin_cursor"), OUTPUT_DIRECTIONS.length);

        for (Direction direction : OUTPUT_DIRECTIONS) {
            String name = direction.getName();
            int index = outputIndex(direction);
            reservations[index] = Math.max(0, tag.getInt("reserved_" + name));
            lastProgress[index] = tag.getLong("last_progress_" + name);
        }
    }

    /** Moves the stored stack into the block's drops and empties the inventory. */
    public void addDrops(List<ItemStack> drops) {
        if (!stored.isEmpty()) {
            drops.add(stored.copy());
            stored = ItemStack.EMPTY;
        }
    }

    // ------------------------------------------------------------------
    // Deferred state changes
    // ------------------------------------------------------------------

    private void applyCommittedInputSides() {
        if (insertedInputSides.isEmpty()) {
            return;
        }

        if (level != null && !isRemoved()) {
            BlockState currentState = getBlockState();
            BlockState updatedState = currentState;

            for (Direction direction : insertedInputSides) {
                if (SmartSplitterBlock.getSideMode(updatedState, direction) == SmartSplitterBlock.SideMode.CLOSED) {
                    updatedState = SmartSplitterBlock.setSideMode(
                            updatedState, direction, SmartSplitterBlock.SideMode.INPUT);
                }
            }

            if (updatedState != currentState) {
                level.setBlock(worldPosition, updatedState, Block.UPDATE_ALL);
            }
        }

        insertedInputSides.clear();
    }

    private SplitterSnapshot captureSnapshot() {
        return new SplitterSnapshot(
                reservations.clone(),
                lastProgress.clone(),
                EnumSet.copyOf(insertedInputSides),
                remainderCursor,
                roundRobinCursor);
    }

    private void revertToSnapshot(SplitterSnapshot snapshot) {
        System.arraycopy(snapshot.reservations(), 0, reservations, 0, reservations.length);
        System.arraycopy(snapshot.lastProgress(), 0, lastProgress, 0, lastProgress.length);
        insertedInputSides.clear();
        insertedInputSides.addAll(snapshot.insertedInputSides());
        remainderCursor = snapshot.remainderCursor();
        roundRobinCursor = snapshot.roundRobinCursor();
    }

    private record SplitterSnapshot(
            int[] reservations,
            long[] lastProgress,
            Set<Direction> insertedInputSides,
            int remainderCursor,
            int roundRobinCursor) {
    }

    // ------------------------------------------------------------------
    // Modes and the per-side handler
    // ------------------------------------------------------------------

    public enum SplitMode {
        STRICT,
        OVERFLOW,
        ROUND_ROBIN;

        public SplitMode next() {
            return values()[(ordinal() + 1) % values().length];
        }

        private static SplitMode fromOrdinal(int ordinal) {
            return values()[Math.max(0, Math.min(ordinal, values().length - 1))];
        }

        public String translationKey() {
            return "message.oritechsplitter.smart_splitter.mode." + name().toLowerCase(Locale.ROOT);
        }
    }

    private class SplitterItemHandler implements IItemHandler {

        @Nullable
        private final Direction side;

        private SplitterItemHandler(@Nullable Direction side) {
            this.side = side;
        }

        @Override
        public int getSlots() {
            return 1;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            // Deliberately does NOT probe for a new output.
            //
            // Upstream probes in its read path (getResource), but on 1.21.1 that same call is also
            // how an inserting pipe inspects a *destination*: Oritech's item pipe runs an
            // "is this storage empty" check over every target slot before it inserts. Probing here
            // therefore marked the receiving side as an output, and the insert that followed was
            // rejected - the splitter never filled up. The world update the probe performs also ran
            // on every read, which is far more often than upstream's, and flooded the server.
            //
            // extractItem is the real extraction probe on 1.21.1, so the probe lives there instead.
            return stored;
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            return SmartSplitterBlockEntity.this.insert(side, slot, stack, simulate);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return SmartSplitterBlockEntity.this.extract(side, slot, amount, simulate);
        }

        @Override
        public int getSlotLimit(int slot) {
            // Upstream reports no capacity on output sides, so nothing tries to push items back in.
            if (isOutputSide(side)) {
                return 0;
            }
            return stored.isEmpty() ? DEFAULT_SLOT_LIMIT : stored.getMaxStackSize();
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return !isOutputSide(side);
        }
    }
}
