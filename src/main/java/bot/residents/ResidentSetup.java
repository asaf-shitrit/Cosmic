package bot.residents;

import client.Character;
import client.Client;
import client.Job;
import client.SkinColor;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import constants.inventory.ItemConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;

import java.util.function.Supplier;

/**
 * What the server does for a resident on its behalf, the way an NPC script or event would: set the
 * character up as its profile describes, and stock it from the resident's suppliers.
 *
 * <p><b>Why server-side at all.</b> A resident is a shopkeeper, not a farmer; where its stock comes from
 * is off-screen. Everything it does <em>with</em> that stock - opening the merchant, listing, repricing,
 * buying from players - goes over the wire through the real handlers.
 *
 * <p><b>Set to a target, never add on top.</b> Every method is idempotent across wakes (the lesson of
 * {@code CompanionLoadout}, where a "grant N" compounded): setup only changes what differs, supply only
 * adds the shortfall to a target quantity, funding only tops mesos up to what a purchase needs.
 *
 * <p>Runs on the resident's own thread while its connection is live, holding the client's action lock
 * so it cannot interleave with a packet handler for the same character.
 */
final class ResidentSetup {
    private static final Logger log = LoggerFactory.getLogger(ResidentSetup.class);

    /** Mushroom House Elf, a Hired Merchant permit ({@code ItemConstants.isHiredMerchant}: 503xxxx). */
    static final int HIRED_MERCHANT_PERMIT = 5030000;
    /** The Relaxer - the plain portable chair NPC shops sell ({@code ItemId.RELAXER}). */
    static final int CHAIR = 3010000;

    private ResidentSetup() {
    }

    /** Runs {@code action} under the client's action lock, retrying briefly if a handler holds it. */
    static <T> T withClientLock(Character chr, Supplier<T> action) {
        Client client = chr.getClient();
        for (int attempt = 0; attempt < 50; attempt++) {
            if (client.tryacquireClient()) {
                try {
                    return action.get();
                } finally {
                    client.releaseClient();
                }
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new IllegalStateException("client busy for " + chr.getName());
    }

    /** Level, job, look, a chair and a merchant permit, as the profile says. Returns what changed, for the log. */
    static String prepare(Character chr, ResidentProfile profile) {
        if (!profile.name().equalsIgnoreCase(chr.getName())) {
            throw new IllegalArgumentException("not this resident's character");
        }
        return withClientLock(chr, () -> {
            StringBuilder changed = new StringBuilder();
            if (chr.getLevel() != profile.level()) {
                chr.setLevel(profile.level());
                chr.setExp(0);
                changed.append(" level");
            }
            Job job = Job.getById(profile.jobId());
            if (job != null && chr.getJob() != job) {
                chr.setJob(job);
                changed.append(" job");
                // A plausible pool for the level; nobody fights, but party/visitor windows show it.
                chr.updateMaxHpMaxMp(50 + profile.level() * 20, 50 + profile.level() * 15);
                chr.updateHpMp(chr.getMaxHp(), chr.getMaxMp());
            }
            int gender = profile.female() ? 1 : 0;
            if (chr.getGender() != gender || chr.getFace() != profile.face() || chr.getHair() != profile.hair()
                    || chr.getSkinColor().getId() != profile.skin()) {
                chr.setGender(gender);
                chr.setFace(profile.face());
                chr.setHair(profile.hair());
                SkinColor skin = SkinColor.getById(profile.skin());
                if (skin != null) {
                    chr.setSkinColor(skin);
                }
                changed.append(" look");
            }
            if (chr.getInventory(InventoryType.SETUP).findById(CHAIR) == null) {
                InventoryManipulator.addById(chr.getClient(), CHAIR, (short) 1, null, -1, ItemConstants.UNTRADEABLE, -1);
                changed.append(" chair");
            }
            if (chr.getInventory(InventoryType.CASH).countById(HIRED_MERCHANT_PERMIT) < 1) {
                InventoryManipulator.addById(chr.getClient(), HIRED_MERCHANT_PERMIT, (short) 1, null, -1, ItemConstants.UNTRADEABLE, -1);
                changed.append(" permit");
            }
            return changed.toString().trim();
        });
    }

    /** Units of {@code itemId} the resident carries, across all its slots. */
    static int carried(Character chr, int itemId) {
        return chr.getInventory(ItemConstants.getInventoryType(itemId)).countById(itemId);
    }

    /**
     * Tops the resident's inventory up to {@code targetUnits} of {@code itemId} from its supplier.
     *
     * @return units added
     */
    static int supply(Character chr, int itemId, int targetUnits) {
        return withClientLock(chr, () -> {
            int have = carried(chr, itemId);
            int missing = targetUnits - have;
            if (missing <= 0) {
                return 0;
            }
            ItemInformationProvider ii = ItemInformationProvider.getInstance();
            boolean equip = ItemConstants.getInventoryType(itemId) == InventoryType.EQUIP;
            short slotMax = equip ? 1 : ii.getSlotMax(chr.getClient(), itemId);
            int added = 0;
            while (added < missing) {
                short chunk = (short) Math.min(slotMax, missing - added);
                if (!InventoryManipulator.checkSpace(chr.getClient(), itemId, chunk, "")
                        || !InventoryManipulator.addById(chr.getClient(), itemId, chunk)) {
                    log.warn("Resident {} has no room for more of item {} ({} of {} added)", chr.getName(), itemId, added, missing);
                    break;
                }
                added += chunk;
            }
            return added;
        });
    }

    /**
     * The fullest slot holding {@code itemId}: PUT_ITEM lists from one slot at a time and needs
     * bundles x perBundle available in that slot.
     */
    static Item fullestSlot(Character chr, int itemId) {
        Item best = null;
        for (Item item : chr.getInventory(ItemConstants.getInventoryType(itemId)).listById(itemId)) {
            if (item.isUntradeable()) {
                continue;
            }
            if (best == null || item.getQuantity() > best.getQuantity()) {
                best = item;
            }
        }
        return best;
    }

    /**
     * Makes sure the resident holds at least {@code mesos} before a purchase. Whatever this adds is new
     * money, which is why the caller has already charged the daily minted-meso cap.
     *
     * @return mesos added
     */
    static int fund(Character chr, int mesos) {
        return withClientLock(chr, () -> {
            int missing = mesos - chr.getMeso();
            if (missing <= 0) {
                return 0;
            }
            chr.gainMeso(missing, false);
            return missing;
        });
    }
}
