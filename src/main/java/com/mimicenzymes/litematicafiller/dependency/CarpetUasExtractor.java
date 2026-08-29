package com.mimicenzymes.litematicafiller.dependency;

import com.mimicenzymes.litematicafiller.dependency.CarpetUasTransport.OpenV2Result;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ShulkerBoxBlock;

/**
 * Client session that opens a shulker box from the player inventory through
 * Carpet UAS Addition, mirroring the printer's fenced transaction design.
 * <p>
 * Open-v2 (raw slot, acknowledged) is preferred and needs no inventory
 * changes. The legacy protocol opens only the main-hand box, so the session
 * first swaps the box into the selected hotbar slot with an authoritative
 * inventory click, waits for the slot update before sending the payload, and
 * swaps the box back once the menu has closed so slot-based bookkeeping in
 * the state machines stays valid.
 */
public final class CarpetUasExtractor implements IShulkerExtractor {
    private static final CarpetUasExtractor INSTANCE = new CarpetUasExtractor();

    private enum Stage {
        IDLE,
        V2_OPEN,
        V1_SWAP_WAIT,
        V1_MENU_WAIT,
        V1_RESTORE_WAIT
    }

    private static final int V2_OPEN_TIMEOUT_TICKS = 80;
    private static final int V1_SWAP_TIMEOUT_TICKS = 10;
    private static final int V1_MENU_TIMEOUT_TICKS = 30;
    private static final int V1_RESTORE_TIMEOUT_TICKS = 10;

    private Stage stage = Stage.IDLE;
    private long requestId = -1L;
    private int shulkerSlot = -1;
    private int handSlot = -1;
    private int originalSelection = -1;
    private int protectedSlot = -1;
    private boolean menuSeen;
    private int ticks;
    private ItemStack expectedShulker = ItemStack.EMPTY;
    private LocalPlayer sessionPlayer;

    private CarpetUasExtractor() {
    }

    public static CarpetUasExtractor getInstance() {
        return INSTANCE;
    }

    public static boolean isAvailable() {
        return CarpetUasTransport.canSendOpenV2()
                || CarpetUasTransport.canSendOpenPayload();
    }

    public synchronized boolean isActive() {
        return stage != Stage.IDLE;
    }

    /**
     * Marks a player slot whose content must not be displaced by the v1 hand
     * swap (e.g. the slot the stash phase is about to quick-move).
     */
    public synchronized void setProtectedSlot(int slot) {
        this.protectedSlot = slot;
    }

    @Override
    public synchronized boolean requestOpenShulker(int playerSlotIndex) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (stage != Stage.IDLE
                || !isAvailable()
                || player == null
                || client.level == null
                || client.gameMode == null
                || player.isSpectator()
                || player.containerMenu != player.inventoryMenu
                || !player.inventoryMenu.getCarried().isEmpty()) {
            return false;
        }

        Inventory inventory = player.getInventory();
        if (playerSlotIndex < 0
                || playerSlotIndex >= Math.min(inventory.getContainerSize(), 36)) {
            return false;
        }
        ItemStack stack = inventory.getItem(playerSlotIndex);
        if (stack.getCount() != 1 || !isShulkerBox(stack)) {
            return false;
        }

        if (CarpetUasTransport.canSendOpenV2()) {
            long sent = CarpetUasTransport.sendOpenV2(playerSlotIndex);
            if (sent <= 0L) return false;
            beginSession(player, Stage.V2_OPEN, playerSlotIndex, -1, stack);
            requestId = sent;
            return true;
        }

        if (CarpetUasTransport.canSendOpenPayload()) {
            int selected = inventory.getSelectedSlot();
            if (playerSlotIndex == selected) {
                // The box is already the main-hand item; open it directly.
                if (!CarpetUasTransport.sendOpenPayload()) {
                    return false;
                }
                beginSession(player, Stage.V1_MENU_WAIT, playerSlotIndex, selected, stack);
                return true;
            }

            int hand = pickV1HandSlot(inventory, playerSlotIndex, selected);
            if (hand < 0) {
                // Every candidate would displace tracked content; refuse
                // instead of risking a wrong-slot interaction downstream.
                return false;
            }
            if (hand != selected) {
                // The payload opens the main-hand box, so the server must be
                // told to carry the chosen hotbar slot before the swap.
                inventory.setSelectedSlot(hand);
                player.connection.send(new ServerboundSetCarriedItemPacket(hand));
            }
            int uiSlot = playerSlotIndex < 9
                    ? playerSlotIndex + 36
                    : playerSlotIndex;
            client.gameMode.handleContainerInput(
                    player.inventoryMenu.containerId,
                    uiSlot,
                    hand,
                    ContainerInput.SWAP,
                    player);
            beginSession(player, Stage.V1_SWAP_WAIT, playerSlotIndex, hand, stack);
            originalSelection = hand != selected ? selected : -1;
            return true;
        }
        return false;
    }

    /**
     * Chooses the hotbar slot that will carry the box while the menu is open.
     * Prefers slots whose content is not displaced, and never pushes another
     * shulker box out of the hotbar, so slot-based bookkeeping in the state
     * machines stays intact until the session swaps everything back.
     */
    private int pickV1HandSlot(Inventory inventory, int shulkerSlot, int selected) {
        if (inventory.getItem(selected).isEmpty()) {
            return selected;
        }
        for (int slot = 0; slot < 9; slot++) {
            if (slot != shulkerSlot && inventory.getItem(slot).isEmpty()) {
                return slot;
            }
        }
        if (selected != protectedSlot && !isShulkerBox(inventory.getItem(selected))) {
            return selected;
        }
        for (int slot = 0; slot < 9; slot++) {
            if (slot != shulkerSlot && slot != protectedSlot
                    && !isShulkerBox(inventory.getItem(slot))) {
                return slot;
            }
        }
        return -1;
    }

    public synchronized void tick() {
        if (stage == Stage.IDLE) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || player != sessionPlayer) {
            clear(true);
            return;
        }

        switch (stage) {
            case V2_OPEN -> tickV2Open();
            case V1_SWAP_WAIT -> tickV1SwapWait(player);
            case V1_MENU_WAIT -> tickV1MenuWait(player);
            case V1_RESTORE_WAIT -> tickV1RestoreWait(player);
            default -> {
            }
        }
    }

    private void tickV2Open() {
        OpenV2Result response = CarpetUasTransport.pollOpenV2(requestId);
        if (response != null) {
            // The menu itself gates the follow-up extraction; a failed or
            // mismatched ACK simply leaves the state machine's UI timeout
            // to abort the task.
            clear(false);
            return;
        }
        if (++ticks >= V2_OPEN_TIMEOUT_TICKS) {
            CarpetUasTransport.forgetOpenV2(requestId);
            clear(false);
        }
    }

    private void tickV1SwapWait(LocalPlayer player) {
        ItemStack selected = player.getInventory().getItem(handSlot);
        if (stacksMatch(selected, expectedShulker)) {
            if (CarpetUasTransport.sendOpenPayload()) {
                stage = Stage.V1_MENU_WAIT;
                ticks = 0;
            } else {
                clear(false);
            }
            return;
        }
        if (++ticks >= V1_SWAP_TIMEOUT_TICKS) {
            clear(false);
        }
    }

    private void tickV1MenuWait(LocalPlayer player) {
        if (player.containerMenu instanceof ShulkerBoxMenu) {
            menuSeen = true;
            ticks = 0;
            return;
        }
        if (!menuSeen) {
            if (++ticks >= V1_MENU_TIMEOUT_TICKS) {
                // The open never arrived; undo the swap so the inventory
                // returns to its pre-request arrangement.
                beginRestore(player);
            }
            return;
        }
        if (player.containerMenu == player.inventoryMenu) {
            beginRestore(player);
            return;
        }
        // A replacement screen is player-owned; wait briefly for it to close.
        if (++ticks >= V1_MENU_TIMEOUT_TICKS) {
            clear(false);
        }
    }

    private void tickV1RestoreWait(LocalPlayer player) {
        ItemStack restored = player.getInventory().getItem(shulkerSlot);
        if (stacksMatch(restored, expectedShulker)) {
            clear(false);
            return;
        }
        if (++ticks >= V1_RESTORE_TIMEOUT_TICKS) {
            clear(false);
        }
    }

    private void beginRestore(LocalPlayer player) {
        Minecraft client = Minecraft.getInstance();
        if (client.gameMode == null
                || player.containerMenu != player.inventoryMenu
                || handSlot < 0
                || handSlot == shulkerSlot) {
            clear(false);
            return;
        }
        int uiSlot = shulkerSlot < 9 ? shulkerSlot + 36 : shulkerSlot;
        client.gameMode.handleContainerInput(
                player.inventoryMenu.containerId,
                uiSlot,
                handSlot,
                ContainerInput.SWAP,
                player);
        if (originalSelection >= 0 && originalSelection < 9) {
            player.getInventory().setSelectedSlot(originalSelection);
            player.connection.send(new ServerboundSetCarriedItemPacket(originalSelection));
        }
        stage = Stage.V1_RESTORE_WAIT;
        ticks = 0;
    }

    public synchronized void reset() {
        clear(true);
    }

    private void beginSession(
            LocalPlayer player,
            Stage newStage,
            int slot,
            int hand,
            ItemStack stack
    ) {
        stage = newStage;
        requestId = -1L;
        shulkerSlot = slot;
        handSlot = hand;
        originalSelection = -1;
        menuSeen = false;
        ticks = 0;
        expectedShulker = stack.copy();
        sessionPlayer = player;
    }

    private void clear(boolean forget) {
        if (forget && stage == Stage.V2_OPEN && requestId > 0L) {
            CarpetUasTransport.forgetOpenV2(requestId);
        }
        stage = Stage.IDLE;
        requestId = -1L;
        shulkerSlot = -1;
        handSlot = -1;
        originalSelection = -1;
        protectedSlot = -1;
        menuSeen = false;
        ticks = 0;
        expectedShulker = ItemStack.EMPTY;
        sessionPlayer = null;
    }

    private static boolean isShulkerBox(ItemStack stack) {
        return stack.getItem() instanceof BlockItem bi
                && bi.getBlock() instanceof ShulkerBoxBlock;
    }

    private static boolean stacksMatch(ItemStack first, ItemStack second) {
        if (first.isEmpty() || second.isEmpty()) {
            return first.isEmpty() && second.isEmpty();
        }
        return ItemStack.isSameItemSameComponents(first, second);
    }
}
