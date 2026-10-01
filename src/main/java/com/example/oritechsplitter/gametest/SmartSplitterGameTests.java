package com.example.oritechsplitter.gametest;

import com.example.oritechsplitter.OritechSplitter;
import com.example.oritechsplitter.block.SmartSplitterBlock;
import com.example.oritechsplitter.block.entity.SmartSplitterBlockEntity;
import com.example.oritechsplitter.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;
import rearth.oritech.api.item.ItemApi;

/**
 * Behavioural tests for the ported Smart Splitter.
 *
 * <p>These cover the parts of the port that could not be checked by compiling: the reservation
 * bookkeeping, the simulate/commit split that replaces upstream's transaction journal, the side
 * learning rules and the three distribution modes.
 *
 * <p>Run with {@code ./gradlew runGameTestServer}.
 */
@GameTestHolder(OritechSplitter.MOD_ID)
@PrefixGameTestTemplate(false)
public class SmartSplitterGameTests {

    /** Relative position inside the {@code empty} structure. */
    private static final BlockPos SPLITTER_POS = new BlockPos(2, 2, 2);

    private static final int STACK = 64;

    private static SmartSplitterBlockEntity placeSplitter(GameTestHelper helper,
                                                          SmartSplitterBlock.SideMode north,
                                                          SmartSplitterBlock.SideMode south) {
        BlockState state = ModBlocks.SMART_SPLITTER.get().defaultBlockState()
                .setValue(SmartSplitterBlock.NORTH, north)
                .setValue(SmartSplitterBlock.SOUTH, south);
        helper.setBlock(SPLITTER_POS, state);
        return helper.getBlockEntity(SPLITTER_POS);
    }

    private static IItemHandler side(SmartSplitterBlockEntity splitter, Direction direction) {
        return splitter.getItemHandler(direction);
    }

    private static ItemStack stone(int count) {
        return new ItemStack(Items.STONE, count);
    }

    /** Strict mode reserves an even share per output and never hands out more than the share. */
    @GameTest(template = "empty", skyAccess = true)
    public static void strictModeSplitsEvenly(GameTestHelper helper) {
        SmartSplitterBlockEntity splitter = placeSplitter(helper,
                SmartSplitterBlock.SideMode.OUTPUT, SmartSplitterBlock.SideMode.OUTPUT);

        ItemStack leftover = side(splitter, Direction.UP).insertItem(0, stone(8), false);
        helper.assertTrue(leftover.isEmpty(), "the splitter should accept all 8 items, leftover=" + leftover.getCount());

        ItemStack north = side(splitter, Direction.NORTH).extractItem(0, STACK, false);
        helper.assertTrue(north.getCount() == 4, "north should be reserved exactly half, got " + north.getCount());

        ItemStack south = side(splitter, Direction.SOUTH).extractItem(0, STACK, false);
        helper.assertTrue(south.getCount() == 4, "south should get the other half, got " + south.getCount());

        helper.assertTrue(side(splitter, Direction.UP).getStackInSlot(0).isEmpty(),
                "the splitter should be empty after both shares were taken");
        helper.succeed();
    }

    /**
     * A simulated extraction must not consume the reservation or the round robin turn, which is what
     * upstream's transaction rollback guarantees.
     */
    @GameTest(template = "empty", skyAccess = true)
    public static void simulatedExtractionDoesNotConsumeReservation(GameTestHelper helper) {
        SmartSplitterBlockEntity splitter = placeSplitter(helper,
                SmartSplitterBlock.SideMode.OUTPUT, SmartSplitterBlock.SideMode.OUTPUT);

        side(splitter, Direction.UP).insertItem(0, stone(8), false);

        ItemStack simulated = side(splitter, Direction.NORTH).extractItem(0, STACK, true);
        helper.assertTrue(simulated.getCount() == 4, "a simulation should report the reserved 4, got " + simulated.getCount());

        ItemStack north = side(splitter, Direction.NORTH).extractItem(0, STACK, false);
        helper.assertTrue(north.getCount() == 4,
                "after simulating, north must still receive its full reservation, got " + north.getCount());

        ItemStack south = side(splitter, Direction.SOUTH).extractItem(0, STACK, false);
        helper.assertTrue(south.getCount() == 4,
                "north's simulation must not have eaten into south's share, got " + south.getCount());

        helper.succeed();
    }

    /** Items may not be pushed back into a side configured as an output. */
    @GameTest(template = "empty", skyAccess = true)
    public static void outputSideRefusesInsertion(GameTestHelper helper) {
        SmartSplitterBlockEntity splitter = placeSplitter(helper,
                SmartSplitterBlock.SideMode.OUTPUT, SmartSplitterBlock.SideMode.CLOSED);

        IItemHandler north = side(splitter, Direction.NORTH);
        ItemStack leftover = north.insertItem(0, stone(4), false);

        helper.assertTrue(leftover.getCount() == 4, "an output side must reject the whole stack");
        helper.assertTrue(north.getStackInSlot(0).isEmpty(), "nothing should have been stored");
        helper.assertTrue(north.getSlotLimit(0) == 0, "an output side should report no capacity");
        helper.succeed();
    }

    /** A committed insertion through a horizontal side turns that side into an input. */
    @GameTest(template = "empty", skyAccess = true)
    public static void horizontalInsertionMarksInput(GameTestHelper helper) {
        SmartSplitterBlockEntity splitter = placeSplitter(helper,
                SmartSplitterBlock.SideMode.CLOSED, SmartSplitterBlock.SideMode.CLOSED);

        side(splitter, Direction.WEST).insertItem(0, stone(4), false);

        helper.assertBlockProperty(SPLITTER_POS, SmartSplitterBlock.WEST, SmartSplitterBlock.SideMode.INPUT);
        helper.assertBlockProperty(SPLITTER_POS, SmartSplitterBlock.NORTH, SmartSplitterBlock.SideMode.CLOSED);
        helper.succeed();
    }

    /** A simulated insertion is not committed, so it must not mark the side as an input. */
    @GameTest(template = "empty", skyAccess = true)
    public static void simulatedInsertionDoesNotMarkInput(GameTestHelper helper) {
        SmartSplitterBlockEntity splitter = placeSplitter(helper,
                SmartSplitterBlock.SideMode.CLOSED, SmartSplitterBlock.SideMode.CLOSED);

        side(splitter, Direction.WEST).insertItem(0, stone(4), true);

        helper.assertBlockProperty(SPLITTER_POS, SmartSplitterBlock.WEST, SmartSplitterBlock.SideMode.CLOSED);
        helper.succeed();
    }

    /**
     * Inspecting a side for extraction marks it as an output, even when the splitter is empty and the
     * extraction is only simulated.
     */
    @GameTest(template = "empty", skyAccess = true)
    public static void extractionProbeMarksOutput(GameTestHelper helper) {
        SmartSplitterBlockEntity splitter = placeSplitter(helper,
                SmartSplitterBlock.SideMode.CLOSED, SmartSplitterBlock.SideMode.CLOSED);

        ItemStack probed = side(splitter, Direction.EAST).extractItem(0, STACK, true);
        helper.assertTrue(probed.isEmpty(), "an empty splitter cannot hand anything out");

        helper.assertBlockProperty(SPLITTER_POS, SmartSplitterBlock.EAST, SmartSplitterBlock.SideMode.OUTPUT);
        helper.succeed();
    }

    /**
     * Reading the inventory must not change any side mode.
     *
     * <p>Regression test: the output probe used to run inside {@code getStackInSlot}, but an
     * inserting pipe reads a destination's slots before it inserts. That marked the receiving side as
     * an output and every insert was rejected, so the splitter never filled up.
     */
    @GameTest(template = "empty", skyAccess = true)
    public static void readingInventoryDoesNotMarkOutput(GameTestHelper helper) {
        SmartSplitterBlockEntity splitter = placeSplitter(helper,
                SmartSplitterBlock.SideMode.CLOSED, SmartSplitterBlock.SideMode.CLOSED);

        IItemHandler west = side(splitter, Direction.WEST);
        west.getStackInSlot(0);
        west.getSlots();
        west.getSlotLimit(0);
        west.isItemValid(0, stone(1));

        helper.assertBlockProperty(SPLITTER_POS, SmartSplitterBlock.WEST, SmartSplitterBlock.SideMode.CLOSED);
        helper.assertBlockProperty(SPLITTER_POS, SmartSplitterBlock.NORTH, SmartSplitterBlock.SideMode.CLOSED);
        helper.succeed();
    }

    /**
     * Reproduces the order Oritech's item pipe uses: it reads every destination slot to decide
     * whether the destination was empty, and only then inserts. That read must not stop the insert,
     * and the same side must afterwards be able to hand items on to a second pipe.
     */
    @GameTest(template = "empty", skyAccess = true)
    public static void pipeStyleReadThenInsertStillFills(GameTestHelper helper) {
        SmartSplitterBlockEntity splitter = placeSplitter(helper,
                SmartSplitterBlock.SideMode.CLOSED, SmartSplitterBlock.SideMode.CLOSED);

        IItemHandler west = side(splitter, Direction.WEST);

        // The destination emptiness check the pipe performs before inserting.
        boolean wasEmpty = true;
        for (int slot = 0; slot < west.getSlots(); slot++) {
            if (!west.getStackInSlot(slot).isEmpty()) {
                wasEmpty = false;
            }
        }
        helper.assertTrue(wasEmpty, "the splitter starts empty");

        ItemStack leftover = west.insertItem(0, stone(8), false);
        helper.assertTrue(leftover.isEmpty(),
                "a side that was only read must still accept items, leftover=" + leftover.getCount());
        helper.assertBlockProperty(SPLITTER_POS, SmartSplitterBlock.WEST, SmartSplitterBlock.SideMode.INPUT);

        // A second pipe now extracts from the north side, which is what turns it into an output.
        ItemStack north = side(splitter, Direction.NORTH).extractItem(0, STACK, false);
        helper.assertTrue(north.getCount() == 8,
                "the whole stack should be available to the only output, got " + north.getCount());
        helper.assertBlockProperty(SPLITTER_POS, SmartSplitterBlock.NORTH, SmartSplitterBlock.SideMode.OUTPUT);
        helper.succeed();
    }

    /**
     * Drives the splitter through Oritech's own item API - the exact wrapper its item pipes use -
     * so the contract between the two mods is covered rather than just the raw handler.
     *
     * <p>{@code ItemApi.BLOCK} resolves through the standard item handler capability and wraps it in
     * {@code NeoforgeStoragerWrapper}, which maps {@code insert} to
     * {@code ItemHandlerHelper.insertItem} and {@code extractFromSlot} to {@code extractItem}.
     */
    @GameTest(template = "empty", skyAccess = true)
    public static void oritechItemApiTransfersThroughTheSplitter(GameTestHelper helper) {
        placeSplitter(helper, SmartSplitterBlock.SideMode.CLOSED, SmartSplitterBlock.SideMode.CLOSED);
        BlockPos absolutePos = helper.absolutePos(SPLITTER_POS);
        ServerLevel level = helper.getLevel();

        // Destination side, in the order Oritech's pipe uses: read the slots, then insert.
        ItemApi.InventoryStorage destination = ItemApi.BLOCK.find(level, absolutePos, Direction.WEST);
        helper.assertTrue(destination != null, "the splitter must expose the item handler capability");

        boolean wasEmpty = true;
        for (int slot = 0; slot < destination.getSlotCount(); slot++) {
            if (!destination.getStackInSlot(slot).isEmpty()) {
                wasEmpty = false;
            }
        }
        helper.assertTrue(wasEmpty, "the splitter starts empty");

        int inserted = destination.insert(stone(8), false);
        helper.assertTrue(inserted == 8,
                "Oritech's insert must be accepted, got " + inserted);
        helper.assertBlockProperty(SPLITTER_POS, SmartSplitterBlock.WEST, SmartSplitterBlock.SideMode.INPUT);

        // Source side: simulate the extraction, then commit it.
        ItemApi.InventoryStorage source = ItemApi.BLOCK.find(level, absolutePos, Direction.NORTH);
        helper.assertTrue(source != null, "the splitter must expose the item handler capability");
        helper.assertTrue(source.supportsExtraction(), "the splitter must advertise extraction");

        int canTake = source.extractFromSlot(stone(8), 0, true);
        helper.assertTrue(canTake == 8,
                "the simulated probe must offer the whole stack, got " + canTake);
        helper.assertBlockProperty(SPLITTER_POS, SmartSplitterBlock.NORTH, SmartSplitterBlock.SideMode.OUTPUT);

        int extracted = source.extract(stone(8), false);
        helper.assertTrue(extracted == 8,
                "the committed extraction must move the whole stack, got " + extracted);
        helper.assertTrue(source.getStackInSlot(0).isEmpty(), "the splitter should be empty afterwards");
        helper.succeed();
    }

    /** Round robin lets exactly one output extract at a time and rotates after a committed take. */
    @GameTest(template = "empty", skyAccess = true)
    public static void roundRobinRotatesOutputs(GameTestHelper helper) {
        SmartSplitterBlockEntity splitter = placeSplitter(helper,
                SmartSplitterBlock.SideMode.OUTPUT, SmartSplitterBlock.SideMode.OUTPUT);

        splitter.cycleMode(); // STRICT -> OVERFLOW
        splitter.cycleMode(); // OVERFLOW -> ROUND_ROBIN

        side(splitter, Direction.UP).insertItem(0, stone(8), false);

        ItemStack wrongTurn = side(splitter, Direction.SOUTH).extractItem(0, STACK, false);
        helper.assertTrue(wrongTurn.isEmpty(), "only the current output may extract in round robin");

        ItemStack north = side(splitter, Direction.NORTH).extractItem(0, STACK, false);
        helper.assertTrue(north.getCount() == 8,
                "round robin hands the whole stack to the current output, got " + north.getCount());

        // The turn advanced to south, but the splitter is empty now, so nothing can be taken.
        ItemStack after = side(splitter, Direction.SOUTH).extractItem(0, STACK, false);
        helper.assertTrue(after.isEmpty(), "the splitter is empty, so the next turn yields nothing");
        helper.succeed();
    }

    /** A simulated extraction must not consume the round robin turn. */
    @GameTest(template = "empty", skyAccess = true)
    public static void simulatedExtractionKeepsTurn(GameTestHelper helper) {
        SmartSplitterBlockEntity splitter = placeSplitter(helper,
                SmartSplitterBlock.SideMode.OUTPUT, SmartSplitterBlock.SideMode.OUTPUT);

        splitter.cycleMode();
        splitter.cycleMode(); // ROUND_ROBIN

        side(splitter, Direction.UP).insertItem(0, stone(8), false);

        ItemStack simulated = side(splitter, Direction.NORTH).extractItem(0, STACK, true);
        helper.assertTrue(simulated.getCount() == 8, "the current output should be offered the whole stack");

        ItemStack north = side(splitter, Direction.NORTH).extractItem(0, STACK, false);
        helper.assertTrue(north.getCount() == 8,
                "the turn must survive a simulation, got " + north.getCount());
        helper.succeed();
    }

    /**
     * Overflow hands a stalled output's share to an output that is still moving.
     *
     * <p>Note that an output only counts as a recipient once it has no reservation left, so the
     * moving output has to take its own share first. If every output is stale there is nobody to
     * hand the items to and upstream deliberately does nothing.
     */
    @GameTest(template = "empty", skyAccess = true, timeoutTicks = 400)
    public static void overflowReclaimsStaleShare(GameTestHelper helper) {
        SmartSplitterBlockEntity splitter = placeSplitter(helper,
                SmartSplitterBlock.SideMode.OUTPUT, SmartSplitterBlock.SideMode.OUTPUT);

        splitter.cycleMode(); // STRICT -> OVERFLOW

        side(splitter, Direction.UP).insertItem(0, stone(8), false);

        // South takes its own share straight away, which keeps it an eligible recipient.
        ItemStack ownShare = side(splitter, Direction.SOUTH).extractItem(0, STACK, false);
        helper.assertTrue(ownShare.getCount() == 4,
                "south should take its own reserved share first, got " + ownShare.getCount());

        // North never extracts, so after OVERFLOW_DELAY its 4 reserved items become available.
        helper.runAfterDelay(SmartSplitterBlockEntity.OVERFLOW_DELAY * 2, () -> {
            ItemStack reclaimed = side(splitter, Direction.SOUTH).extractItem(0, STACK, false);
            helper.assertTrue(reclaimed.getCount() == 4,
                    "overflow should have reclaimed north's stale share, got " + reclaimed.getCount());
            helper.succeed();
        });
    }

    /** Strict mode keeps holding a stalled output's share instead of giving it away. */
    @GameTest(template = "empty", skyAccess = true, timeoutTicks = 400)
    public static void strictModeHoldsStaleShare(GameTestHelper helper) {
        SmartSplitterBlockEntity splitter = placeSplitter(helper,
                SmartSplitterBlock.SideMode.OUTPUT, SmartSplitterBlock.SideMode.OUTPUT);

        side(splitter, Direction.UP).insertItem(0, stone(8), false);

        helper.runAfterDelay(SmartSplitterBlockEntity.OVERFLOW_DELAY * 2, () -> {
            ItemStack south = side(splitter, Direction.SOUTH).extractItem(0, STACK, false);
            helper.assertTrue(south.getCount() == 4,
                    "strict mode must not redistribute a stalled share, got " + south.getCount());
            helper.succeed();
        });
    }

    /** The splitter holds a single stack and refuses a second item type. */
    @GameTest(template = "empty", skyAccess = true)
    public static void refusesToMixItemTypes(GameTestHelper helper) {
        SmartSplitterBlockEntity splitter = placeSplitter(helper,
                SmartSplitterBlock.SideMode.OUTPUT, SmartSplitterBlock.SideMode.OUTPUT);

        side(splitter, Direction.UP).insertItem(0, stone(32), false);
        ItemStack leftover = side(splitter, Direction.UP).insertItem(0, new ItemStack(Items.DIRT, 16), false);

        helper.assertTrue(leftover.getCount() == 16, "a different item type must be rejected outright");
        helper.succeed();
    }

    /** Without a side, the handler is insert-only, mirroring upstream's unsided lookup. */
    @GameTest(template = "empty", skyAccess = true)
    public static void unsidedHandlerIsInsertOnly(GameTestHelper helper) {
        SmartSplitterBlockEntity splitter = placeSplitter(helper,
                SmartSplitterBlock.SideMode.OUTPUT, SmartSplitterBlock.SideMode.OUTPUT);

        IItemHandler unsided = splitter.getItemHandler(null);
        ItemStack leftover = unsided.insertItem(0, stone(8), false);
        helper.assertTrue(leftover.isEmpty(), "the unsided handler should accept items");

        ItemStack taken = unsided.extractItem(0, STACK, false);
        helper.assertTrue(taken.isEmpty(), "the unsided handler must not extract");

        ItemStack north = side(splitter, Direction.NORTH).extractItem(0, STACK, false);
        helper.assertTrue(north.getCount() == 4, "the reservation made by the unsided insert should hold");
        helper.succeed();
    }

    /** The stored stack is dropped together with the block. */
    @GameTest(template = "empty", skyAccess = true)
    public static void dropsStoredStack(GameTestHelper helper) {
        SmartSplitterBlockEntity splitter = placeSplitter(helper,
                SmartSplitterBlock.SideMode.OUTPUT, SmartSplitterBlock.SideMode.CLOSED);

        side(splitter, Direction.UP).insertItem(0, stone(7), false);

        java.util.List<ItemStack> drops = new java.util.ArrayList<>();
        splitter.addDrops(drops);

        helper.assertTrue(drops.size() == 1 && drops.get(0).getCount() == 7,
                "the stored stack should be dropped once");
        helper.assertTrue(splitter.getItemHandler(Direction.UP).getStackInSlot(0).isEmpty(),
                "the splitter should be emptied after its contents were dropped");
        helper.succeed();
    }
}
