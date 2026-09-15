package bot.residents;

import bot.MapleConnection;
import net.opcodes.RecvOpcode;
import net.packet.OutPacket;

/**
 * Client-to-server packets for shops, the Fredrick NPC, chairs and whispers, each written to match the
 * handler that reads it. Modes are {@code PlayerInteractionHandler.Action} codes.
 */
final class ShopPackets {
    /** {@code PlayerInteractionHandler.Action} codes used here. */
    static final int CREATE = 0x00;
    static final int VISIT = 0x04;
    static final int EXIT = 0x0A;
    static final int OPEN_STORE = 0x0B;
    static final int PLAYER_SHOP_BUY = 0x17;
    static final int PUT_ITEM = 0x21;
    static final int MERCHANT_BUY = 0x22;
    static final int TAKE_ITEM_BACK = 0x26;
    static final int MAINTENANCE_OFF = 0x27;
    static final int MERCHANT_ORGANIZE = 0x28;
    static final int MERCHANT_MESO = 0x2B;

    /** {@code CREATE}'s shop type byte: 4 is a Player Shop, 5 a Hired Merchant (the handler treats both alike and decides by the permit id). */
    static final int CREATE_HIRED_MERCHANT = 5;
    /** {@code FredrickHandler} operation that returns stored items and mesos. */
    static final int FREDRICK_RETRIEVE = 0x1A;
    /** {@code WhisperFlag.WHISPER | WhisperFlag.REQUEST}. */
    static final int WHISPER_REQUEST = 0x06;

    private ShopPackets() {
    }

    /**
     * What the real client sends before showing the "open a store" dialog. {@code HiredMerchantRequest}
     * reads no body; it answers {@code ENTRUSTED_SHOP_CHECK_RESULT} 0x07 when a merchant may be opened
     * here, 0x09 when Fredrick still holds items or mesos (opening now would overwrite that storage -
     * {@code HiredMerchant#saveItems} replaces the owner's MERCHANT rows), or a popup otherwise.
     */
    static OutPacket hiredMerchantRequest() {
        return MapleConnection.packet(RecvOpcode.HIRED_MERCHANT_REQUEST.getValue());
    }

    /**
     * {@code CREATE}: byte type, string title, 3 skipped bytes, int permit item id. The permit must be in
     * the CASH inventory ({@code countById >= 1}); for a Hired Merchant it is not consumed - only a Player
     * Shop permit is, under {@code USE_ERASE_PERMIT_ON_OPENSHOP}. The server checks FM room, no chalkboard,
     * no event instance, no shop within ~151px ({@code distanceSq 23000}) and no portal within 120px.
     */
    static OutPacket createHiredMerchant(String title, int permitItemId) {
        OutPacket p = interaction(CREATE);
        p.writeByte(CREATE_HIRED_MERCHANT);
        p.writeString(title);
        p.writeBytes(new byte[3]);
        p.writeInt(permitItemId);
        return p;
    }

    /**
     * {@code PUT_ITEM}: byte inventory type, short slot, short bundles, short per-bundle, int price per
     * bundle. Rejected unless the slot holds at least bundles x perBundle, bundles x perBundle <= 2000,
     * and the shop isn't open yet (or is in owner maintenance).
     */
    static OutPacket putItem(int inventoryType, int slot, int bundles, int perBundle, int pricePerBundle) {
        OutPacket p = interaction(PUT_ITEM);
        p.writeByte(inventoryType);
        p.writeShort(slot);
        p.writeShort(bundles);
        p.writeShort(perBundle);
        p.writeInt(pricePerBundle);
        return p;
    }

    /** {@code OPEN_STORE}: one byte (always 1 from the client) - the merchant goes on the map and is broadcast. */
    static OutPacket openStore() {
        OutPacket p = interaction(OPEN_STORE);
        p.writeByte(1);
        return p;
    }

    /** {@code VISIT}: int map object id of the shop. An owner visiting its own merchant enters maintenance. */
    static OutPacket visit(int objectId) {
        OutPacket p = interaction(VISIT);
        p.writeInt(objectId);
        return p;
    }

    /** {@code BUY}/{@code MERCHANT_BUY}: byte index into the shop's item list, short bundles. */
    static OutPacket buy(boolean hiredMerchant, int index, int bundles) {
        OutPacket p = interaction(hiredMerchant ? MERCHANT_BUY : PLAYER_SHOP_BUY);
        p.writeByte(index);
        p.writeShort(bundles);
        return p;
    }

    static OutPacket exit() {
        return interaction(EXIT);
    }

    /** {@code TAKE_ITEM_BACK}: short index. Only while the merchant is in maintenance; the list shifts down after each. */
    static OutPacket takeItemBack(int index) {
        OutPacket p = interaction(TAKE_ITEM_BACK);
        p.writeShort(index);
        return p;
    }

    static OutPacket merchantMeso() {
        return interaction(MERCHANT_MESO);
    }

    /** Withdraws mesos and drops sold-out entries - and closes the merchant if that leaves it empty. */
    static OutPacket merchantOrganize() {
        return interaction(MERCHANT_ORGANIZE);
    }

    /** Ends maintenance and reopens to visitors (closes the merchant instead if it has no items). */
    static OutPacket maintenanceOff() {
        return interaction(MAINTENANCE_OFF);
    }

    static OutPacket fredrickRetrieve() {
        OutPacket p = MapleConnection.packet(RecvOpcode.FREDRICK_ACTION.getValue());
        p.writeByte(FREDRICK_RETRIEVE);
        return p;
    }

    /** {@code UseChairHandler}: int item id, which must be a chair held in the SETUP inventory. */
    static OutPacket useChair(int itemId) {
        OutPacket p = MapleConnection.packet(RecvOpcode.USE_CHAIR.getValue());
        p.writeInt(itemId);
        return p;
    }

    /** {@code CancelChairHandler}: short seat id; -1 stands up from a portable chair. */
    static OutPacket standUp() {
        OutPacket p = MapleConnection.packet(RecvOpcode.CANCEL_CHAIR.getValue());
        p.writeShort(-1);
        return p;
    }

    /** {@code WhisperHandler}: byte request, string target name, string message (at most 127 characters). */
    static OutPacket whisper(String target, String message) {
        OutPacket p = MapleConnection.packet(RecvOpcode.WHISPER.getValue());
        p.writeByte(WHISPER_REQUEST);
        p.writeString(target);
        p.writeString(message);
        return p;
    }

    private static OutPacket interaction(int mode) {
        OutPacket p = MapleConnection.packet(RecvOpcode.PLAYER_INTERACTION.getValue());
        p.writeByte(mode);
        return p;
    }
}
