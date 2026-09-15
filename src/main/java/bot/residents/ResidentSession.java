package bot.residents;

import bot.Action;
import bot.ActionExecutor;
import bot.MapPortals;
import bot.MapleConnection;
import bot.WorldState;
import bot.budget.HumanPresence;
import bot.budget.UsageLedger;
import bot.planning.Decision;
import bot.planning.Objective;
import bot.residents.market.CatalogItem;
import bot.residents.market.MarketPricing;
import client.Character;
import client.inventory.Item;
import client.inventory.ItemFactory;
import constants.inventory.ItemConstants;
import net.opcodes.RecvOpcode;
import net.opcodes.SendOpcode;
import net.packet.InPacket;
import net.server.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.maps.HiredMerchant;
import server.maps.MapleMap;
import server.maps.PlayerShop;
import server.maps.PlayerShopItem;

import java.awt.Point;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import java.util.random.RandomGenerator;

/**
 * One wake of one resident, from its channel connection being up to logging out: a scripted sequence
 * that executes the objective the planner handed it. No LLM call is made from here except a chat reply,
 * and that runs on a separate executor and is only polled from the packet loop.
 *
 * <ol>
 *   <li>Set the character up (once), get into the Free Market (a server warp to the entrance, as a town's
 *       FM portal does), collect from Fredrick if a closed merchant left stock with him, and walk through
 *       the entrance portal into its room.</li>
 *   <li>Run the shop: if its merchant is still open, enter maintenance, withdraw sales, take the listings
 *       back and re-list them at the new prices with fresh stock; otherwise open a new Hired Merchant.</li>
 *   <li>Maybe browse the players' shops and buy under the price rule and the daily minted-meso cap.</li>
 *   <li>Idle beside the merchant on a chair until the session ends: occasional canned chatter while a
 *       player is around, and replies when someone whispers or names it in chat.</li>
 * </ol>
 *
 * <p>Each step waits for the server's own state to change (the merchant exists, the item count grew)
 * rather than trusting that a sent packet worked - most shop rejections are silent. Every wait is bounded
 * and the whole session has a hard wall-clock deadline.
 */
final class ResidentSession {
    private static final Logger log = LoggerFactory.getLogger(ResidentSession.class);

    static final String MESOS_PAID_COUNTER = "residents.mesos_paid";
    private static final int WORLD_ID = 0;
    private static final int READ_TICK_MS = 250;
    private static final int STEP_PX = 110;
    private static final long STEP_MS = 200;
    private static final int MAX_SHOP_ENTRIES = 16;
    private static final int SHOP_CHECK_OK = 0x07;
    private static final int SHOP_CHECK_FREDRICK = 0x09;
    private static final int FREDRICK_UI = 0x23;
    private static final int FREDRICK_RETRIEVED = 0x1E;
    private static final int WHISPER_RECEIVED = 0x12;
    private static final int ROOM = 5;
    private static final long REPLY_GAP_MS = 3000;
    private static final int MAX_QUEUED_CHATS = 5;

    /** Thrown out of any wait when the session is over, so every step unwinds the same way. */
    static final class SessionOver extends RuntimeException {
        SessionOver(String why) {
            super(why, null, false, false);
        }
    }

    record ChatEvent(String speaker, String message, boolean whisper) {}

    private final ResidentProfile profile;
    private final ShopAssessment assessment;
    private final Decision decision;
    private final ResidentServices services;
    private final MapleConnection conn;
    private final int charId;
    private final long idleUntil;
    private final long hardDeadline;
    private final BooleanSupplier stopRequested;
    private final RandomGenerator rng;
    private final Runnable onInWorld;

    private final WorldState world;
    private final ActionExecutor executor;
    private final ResidentMemory memory;
    private final Map<Integer, String> names = new HashMap<>();
    private final Deque<ChatEvent> chats = new ArrayDeque<>();
    private Future<String> pendingReply;
    private ChatEvent pendingFor;
    private long lastReplyAt;
    private boolean inWorldSignalled;

    private volatile Integer shopCheckResult;
    private volatile boolean fredrickOpened;
    private volatile Integer fredrickResult;

    // for the episode
    private final List<String> notes = new ArrayList<>();
    private final Set<String> talkedTo = new LinkedHashSet<>();
    private int mesosCollected;
    private int chatReplies;

    ResidentSession(ResidentProfile profile, ShopAssessment assessment, Decision decision, ResidentServices services,
                    MapleConnection conn, int charId, long sessionMs, BooleanSupplier stopRequested, RandomGenerator rng,
                    Runnable onInWorld) {
        this.profile = profile;
        this.assessment = assessment;
        this.decision = decision;
        this.services = services;
        this.conn = conn;
        this.charId = charId;
        long now = System.currentTimeMillis();
        this.idleUntil = now + sessionMs;
        this.hardDeadline = now + sessionMs + 4 * 60_000L;
        this.stopRequested = stopRequested;
        this.rng = rng;
        this.onInWorld = onInWorld;
        this.world = new WorldState(charId);
        this.executor = new ActionExecutor(conn, world);
        this.memory = new ResidentMemory(services.memoryRoot(), profile.name());
    }

    /** Runs the wake. Returns a one-line outcome for the director's log. */
    String run() throws IOException {
        conn.setReadTimeoutMs(READ_TICK_MS);
        String outcome;
        try {
            enterWorld();
            prepareCharacter();
            reachFreeMarket();
            collectFromFredrickIfNeeded();
            travelTo(profile.roomMapId());
            runShop();
            browseAndBuy();
            travelTo(profile.roomMapId());
            idle();
            outcome = "completed";
        } catch (SessionOver e) {
            outcome = e.getMessage();
        } finally {
            ResidentStore.saveMarket(profile.name(), assessment.market);
        }
        standUpQuietly();
        writeEpisode(outcome);
        return outcome;
    }

    // ---- getting there ----

    private void enterWorld() throws IOException {
        if (!await(() -> world.getMapChangeCount() > 0 && self() != null && self().isLoggedinWorld(), 30_000)) {
            throw new SessionOver("never entered the world");
        }
    }

    private void prepareCharacter() {
        String changed = ResidentSetup.prepare(self(), profile);
        if (!changed.isEmpty()) {
            log.info("Resident {} set up: {}", profile.name(), changed);
        }
    }

    private void reachFreeMarket() throws IOException {
        Character chr = self();
        if (FreeMarket.isFreeMarket(chr.getMapId())) {
            return;
        }
        MapleMap entrance = FreeMarket.map(WORLD_ID, services.config().channel(), FreeMarket.ENTRANCE);
        int changes = world.getMapChangeCount();
        // What a town's "Free Market" portal does: a server-side warp to the entrance.
        chr.changeMap(entrance, entrance.getPortal(0));
        if (!await(() -> world.getMapChangeCount() > changes && world.getSelfMapId() == FreeMarket.ENTRANCE, 10_000)) {
            throw new SessionOver("warp to the Free Market entrance never arrived");
        }
    }

    private void collectFromFredrickIfNeeded() throws IOException {
        Character chr = self();
        if (FreeMarket.ownMerchant(WORLD_ID, charId) != null) {
            return;
        }
        List<?> stored;
        try {
            stored = ItemFactory.MERCHANT.loadItems(charId, false);
        } catch (SQLException e) {
            log.warn("Resident {} couldn't check Fredrick's storage", profile.name(), e);
            return;
        }
        if (stored.isEmpty() && chr.getMerchantMeso() == 0) {
            return;
        }
        clearStaleMerchantFlag(chr);
        travelTo(FreeMarket.ENTRANCE);
        walkTo(new Point(FreeMarket.FREDRICK_POSITION.x + 50, FreeMarket.FREDRICK_POSITION.y));
        Optional<WorldState.NpcSighting> fredrick = awaitNpc(FreeMarket.FREDRICK_NPC);
        if (fredrick.isEmpty()) {
            log.warn("Resident {} couldn't find Fredrick in the entrance", profile.name());
            return;
        }
        fredrickOpened = false;
        fredrickResult = null;
        executor.execute(new Action.TalkToNpc(fredrick.get().objectId()));
        if (!await(() -> fredrickOpened, 5000)) {
            log.warn("Resident {}: Fredrick didn't open his storage window", profile.name());
            return;
        }
        conn.send(ShopPackets.fredrickRetrieve());
        if (await(() -> fredrickResult != null, 5000) && fredrickResult == FREDRICK_RETRIEVED) {
            notes.add("collected " + stored.size() + " stored item stack(s) from Fredrick");
            log.info("Resident {} collected {} item stack(s) from Fredrick", profile.name(), stored.size());
        } else {
            log.warn("Resident {}: Fredrick retrieval answered {}", profile.name(), fredrickResult);
        }
    }

    /**
     * {@code HasMerchant} is only cleared when a merchant closes normally; after a hard server stop it can
     * stay set with no merchant anywhere, and then both Fredrick and the merchant request refuse ("you have
     * a store open") forever. Clearing it is what {@code HiredMerchant#forceClose} would have done.
     */
    private void clearStaleMerchantFlag(Character chr) {
        if (chr.hasMerchant() && FreeMarket.ownMerchant(WORLD_ID, charId) == null) {
            log.warn("Resident {} had a stale HasMerchant flag with no open merchant; clearing it", profile.name());
            chr.setHasMerchant(false);
        }
    }

    // ---- the shop ----

    private void runShop() throws IOException {
        if (!(decision.objective() instanceof Objective.RunShop shop)) {
            notes.add("no shop this wake (" + Objective.summary(decision.objective()) + ")");
            return;
        }
        HiredMerchant hm = FreeMarket.ownMerchant(WORLD_ID, charId);
        if (hm != null && (hm.getChannel() != services.config().channel() || hm.getMapId() != profile.roomMapId())) {
            notes.add("left the merchant open elsewhere alone (channel " + hm.getChannel() + ", map " + hm.getMapId() + ")");
            return;
        }
        if (hm != null) {
            maintain(hm, shop);
        } else {
            open(shop);
        }
    }

    private void maintain(HiredMerchant hm, Objective.RunShop shop) throws IOException {
        walkTo(new Point(hm.getPosition().x + 60, hm.getPosition().y));
        conn.send(ShopPackets.visit(hm.getObjectId()));
        if (!await(() -> self().getHiredMerchant() == hm && !hm.isOpen(), 5000)) {
            log.warn("Resident {} couldn't enter maintenance on its merchant", profile.name());
            return;
        }
        int before = self().getMerchantMeso();
        if (before > 0) {
            int mesoBefore = self().getMeso();
            conn.send(ShopPackets.merchantMeso());
            if (await(() -> self().getMerchantMeso() == 0, 4000)) {
                mesosCollected += self().getMeso() - mesoBefore;
            }
        }
        List<PlayerShopItem> snapshot = new ArrayList<>(hm.getItems());
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            PlayerShopItem it = snapshot.get(i);
            if (!it.isExist() || it.getBundles() <= 0) {
                continue;
            }
            int size = hm.getItems().size();
            conn.send(ShopPackets.takeItemBack(i));
            if (!await(() -> hm.getItems().size() < size, 4000)) {
                log.warn("Resident {} couldn't take back shop entry {}", profile.name(), i);
            }
        }
        int listed = list(shop, () -> hm.getItems().size());
        if (hm.getItems().stream().anyMatch(it -> it.isExist() && it.getBundles() > 0)) {
            conn.send(ShopPackets.merchantOrganize());
            await(() -> hm.getItems().stream().allMatch(PlayerShopItem::isExist), 3000);
        }
        conn.send(ShopPackets.maintenanceOff());
        boolean reopened = await(() -> self().getHiredMerchant() == null, 4000) && hm.isOpen();
        notes.add((reopened ? "restocked and repriced" : "FAILED to reopen") + " its merchant \"" + hm.getDescription() + "\": "
                + listed + " listings, collected " + mesosCollected + " mesos");
        log.info("Resident {} {} merchant in {}: {} listings, {} mesos collected (plan via {})", profile.name(),
                reopened ? "restocked" : "failed to reopen", profile.roomMapId(), listed, mesosCollected, decision.path());
    }

    private void open(Objective.RunShop shop) throws IOException {
        Character chr = self();
        clearStaleMerchantFlag(chr);
        MapleMap room = chr.getMap();
        Optional<Point> spot = FreeMarket.freeSpot(room, Math.floorMod(profile.name().hashCode(), 12));
        if (spot.isEmpty()) {
            notes.add("room was full, no merchant opened");
            log.warn("Resident {} found no free merchant spot in {}", profile.name(), room.getId());
            return;
        }
        walkTo(spot.get());
        pumpFor(500);
        shopCheckResult = null;
        conn.send(ShopPackets.hiredMerchantRequest());
        if (!await(() -> shopCheckResult != null, 5000) || shopCheckResult != SHOP_CHECK_OK) {
            log.warn("Resident {}: merchant request answered {} (0x09 = Fredrick still holds stock)", profile.name(), shopCheckResult);
            notes.add("couldn't open a merchant (check result " + shopCheckResult + ")");
            return;
        }
        conn.send(ShopPackets.createHiredMerchant(shop.title(), ResidentSetup.HIRED_MERCHANT_PERMIT));
        if (!await(() -> self().getHiredMerchant() != null, 5000)) {
            log.warn("Resident {}: the server didn't create the merchant at {}", profile.name(), spot.get());
            notes.add("merchant creation was refused");
            return;
        }
        HiredMerchant pending = self().getHiredMerchant();
        int listed = list(shop, () -> pending.getItems().size());
        if (listed == 0) {
            conn.send(ShopPackets.exit());
            await(() -> self().getHiredMerchant() == null, 3000);
            notes.add("had nothing it could list");
            return;
        }
        conn.send(ShopPackets.openStore());
        boolean opened = await(() -> {
            HiredMerchant live = FreeMarket.ownMerchant(WORLD_ID, charId);
            return live != null && live.isOpen() && live.getMap() != null;
        }, 5000);
        notes.add((opened ? "opened" : "FAILED to open") + " a Hired Merchant \"" + shop.title() + "\" with " + listed + " listings");
        log.info("Resident {} {} Hired Merchant \"{}\" in {} at {} with {} listings (plan via {})", profile.name(),
                opened ? "opened" : "failed to open", shop.title(), profile.roomMapId(), spot.get().x + "," + spot.get().y, listed, decision.path());
    }

    /**
     * Lists the plan into the merchant the resident is currently inside (created or in maintenance),
     * topping stock up from its supplier first. One PUT_ITEM per inventory slot; each is confirmed by
     * the merchant's item count growing.
     *
     * @return listings made
     */
    private int list(Objective.RunShop shop, java.util.function.IntSupplier entryCount) throws IOException {
        int made = 0;
        Map<Integer, Integer> listedUnits = new HashMap<>();
        for (Objective.Listing l : shop.listings()) {
            int units = l.bundles() * l.perBundle();
            ResidentSetup.supply(self(), l.itemId(), units);
            int bundlesLeft = l.bundles();
            while (bundlesLeft > 0 && entryCount.getAsInt() < MAX_SHOP_ENTRIES) {
                Item slot = ResidentSetup.fullestSlot(self(), l.itemId());
                if (slot == null) {
                    break;
                }
                int bundles = Math.min(bundlesLeft, slot.getQuantity() / l.perBundle());
                if (bundles <= 0) {
                    break;
                }
                int before = entryCount.getAsInt();
                conn.send(ShopPackets.putItem(ItemConstants.getInventoryType(l.itemId()).getType(), slot.getPosition(), bundles,
                        l.perBundle(), l.pricePerBundle()));
                if (!await(() -> entryCount.getAsInt() > before, 4000)) {
                    log.warn("Resident {}: listing item {} x{} from slot {} wasn't accepted", profile.name(), l.itemId(), bundles,
                            slot.getPosition());
                    break;
                }
                bundlesLeft -= bundles;
                listedUnits.merge(l.itemId(), bundles * l.perBundle(), Integer::sum);
                made++;
            }
        }
        for (int itemId : assessment.market.listed().keySet()) {
            assessment.market.setListedUnits(itemId, 0);
        }
        listedUnits.forEach(assessment.market::setListedUnits);
        return made;
    }

    // ---- buying from players ----

    private void browseAndBuy() throws IOException {
        ResidentConfig config = services.config();
        if (config.maxPurchasesPerWake() <= 0 || rng.nextDouble() >= config.browseChance()) {
            return;
        }
        record Offer(FreeMarket.Listing listing, CatalogItem item, int fair) {}
        List<Offer> offers = new ArrayList<>();
        for (FreeMarket.Listing l : FreeMarket.listings(WORLD_ID, config.channel(), charId)) {
            if (HumanPresence.isBotName(l.seller())) {
                continue;       // residents trade with players, not with each other
            }
            CatalogItem item = assessment.catalog.stream().filter(c -> c.itemId() == l.itemId()).findFirst().orElse(null);
            if (item == null) {
                continue;
            }
            int fair = MarketPricing.fairBuyUnitPrice(assessment.market, item);
            if (l.unitPrice() <= fair) {
                offers.add(new Offer(l, item, fair));
            } else {
                log.info("Resident {} passed on {}'s {} at {}/unit: fair price is at most {}", profile.name(), l.seller(), item.name(),
                        l.unitPrice(), fair);
            }
        }
        offers.sort(Comparator.comparingDouble(o -> (double) o.listing().unitPrice() / o.fair()));
        int purchases = 0;
        for (Offer offer : offers) {
            if (purchases >= config.maxPurchasesPerWake()) {
                break;
            }
            FreeMarket.Listing l = offer.listing();
            int bundles = Math.min(l.bundles(), offer.item().perBundle() == 1 ? 1 : 3);
            long used = services.ledger().used(MESOS_PAID_COUNTER, UsageLedger.Window.DAY);
            long room = config.dailyMesoCap() - used;
            while (bundles > 0 && (long) bundles * l.pricePerBundle() > room) {
                bundles--;
            }
            if (bundles == 0 || !services.ledger().tryConsume(MESOS_PAID_COUNTER, UsageLedger.Window.DAY,
                    (long) bundles * l.pricePerBundle(), config.dailyMesoCap())) {
                log.info("Resident {} won't buy {}'s {} for {}: daily minted-meso cap reached ({} of {} paid to players today)",
                        profile.name(), l.seller(), offer.item().name(), l.pricePerBundle(), used, config.dailyMesoCap());
                notes.add("stopped buying: daily meso cap reached");
                break;
            }
            int total = bundles * l.pricePerBundle();
            log.info("Planner: {} -> {} via RULE (unit price {} <= fair {}; {} of {} daily mesos committed)", profile.name(),
                    Objective.summary(new Objective.BuyFrom(l.seller(), l.itemId(), bundles, l.pricePerBundle())), l.unitPrice(),
                    offer.fair(), used + total, config.dailyMesoCap());
            if (buy(l, bundles, total)) {
                purchases++;
                notes.add("bought " + bundles * l.perBundle() + " " + offer.item().name() + " from " + l.seller() + " for " + total);
            } else {
                services.ledger().refund(MESOS_PAID_COUNTER, UsageLedger.Window.DAY, total);
            }
        }
    }

    private boolean buy(FreeMarket.Listing l, int bundles, int total) throws IOException {
        travelTo(l.mapId());
        walkTo(new Point(l.position().x - 60, l.position().y));
        Character chr = self();
        Object shop = chr.getMap().getMapObject(l.objectId());
        List<PlayerShopItem> items = shop instanceof HiredMerchant hm ? hm.getItems() : shop instanceof PlayerShop ps ? ps.getItems() : null;
        if (items == null || l.index() >= items.size()) {
            log.info("Resident {}: {}'s shop is gone or changed", profile.name(), l.seller());
            return false;
        }
        PlayerShopItem entry = items.get(l.index());
        if (entry.getItem().getItemId() != l.itemId() || entry.getPrice() != l.pricePerBundle() || entry.getBundles() < bundles) {
            log.info("Resident {}: {}'s listing changed before it could buy", profile.name(), l.seller());
            return false;
        }
        ResidentSetup.fund(chr, total);
        int carriedBefore = ResidentSetup.carried(chr, l.itemId());
        int mesoBefore = chr.getMeso();
        conn.send(ShopPackets.visit(l.objectId()));
        boolean inside = await(() -> self().getHiredMerchant() == shop || self().getPlayerShop() == shop, 5000);
        boolean bought = false;
        if (inside) {
            conn.send(ShopPackets.buy(l.hiredMerchant(), l.index(), bundles));
            bought = await(() -> ResidentSetup.carried(self(), l.itemId()) > carriedBefore, 5000);
            conn.send(ShopPackets.exit());
            await(() -> self().getHiredMerchant() == null && self().getPlayerShop() == null, 3000);
        }
        int paid = mesoBefore - self().getMeso();
        log.info("Resident {} {} {} bundle(s) of item {} from {} ({} mesos paid)", profile.name(), bought ? "bought" : "failed to buy",
                bundles, l.itemId(), l.seller(), paid);
        return bought;
    }

    // ---- idling ----

    private void idle() throws IOException {
        HiredMerchant hm = FreeMarket.ownMerchant(WORLD_ID, charId);
        Point base = hm != null && hm.getMapId() == world.getSelfMapId() ? hm.getPosition() : self().getPosition();
        walkTo(new Point(base.x + (rng.nextBoolean() ? 70 : -70), base.y));
        sit();
        long nextChatter = System.currentTimeMillis() + 60_000 + rng.nextInt(120_000);
        long nextShuffle = System.currentTimeMillis() + 120_000 + rng.nextInt(180_000);
        while (System.currentTimeMillis() < idleUntil) {
            pumpOnce();
            long now = System.currentTimeMillis();
            if (now >= nextChatter) {
                nextChatter = now + 120_000 + rng.nextInt(180_000);
                if (humanOnMap()) {
                    executor.execute(new Action.Say(ResidentChat.idleLine(profile, rng)));
                }
            }
            if (now >= nextShuffle) {
                nextShuffle = now + 180_000 + rng.nextInt(240_000);
                conn.send(ShopPackets.standUp());
                Point p = self().getPosition();
                walkTo(new Point(base.x + (p.x > base.x ? -70 : 70), base.y));
                sit();
            }
        }
    }

    private void sit() throws IOException {
        pumpFor(400);
        conn.send(ShopPackets.useChair(ResidentSetup.CHAIR));
    }

    private void standUpQuietly() {
        try {
            conn.send(ShopPackets.standUp());
        } catch (IOException ignored) {
            // the connection is going away anyway
        }
    }

    private boolean humanOnMap() {
        return names.values().stream().anyMatch(n -> !HumanPresence.isBotName(n));
    }

    // ---- movement ----

    /** Walks in short steps a spectator sees as movement, not one teleport. */
    private void walkTo(Point target) throws IOException {
        Point from = self().getPosition();
        double dist = from.distance(target);
        int steps = Math.max(1, (int) Math.ceil(dist / STEP_PX));
        for (int i = 1; i <= steps; i++) {
            Point step = new Point(from.x + (target.x - from.x) * i / steps, from.y + (target.y - from.y) * i / steps);
            executor.execute(new Action.MoveTo(step));
            pumpFor(STEP_MS);
        }
    }

    /** Between the entrance and FM rooms through the real portals (walk within range, then CHANGE_MAP). */
    private void travelTo(int mapId) throws IOException {
        if (world.getSelfMapId() == mapId) {
            return;
        }
        if (world.getSelfMapId() != FreeMarket.ENTRANCE && mapId != FreeMarket.ENTRANCE) {
            travelTo(FreeMarket.ENTRANCE);
        }
        int from = world.getSelfMapId();
        MapPortals.PortalInfo portal = MapPortals.leadingTo(from, mapId)
                .orElseThrow(() -> new SessionOver("no portal from " + from + " to " + mapId));
        walkTo(portal.position());
        for (int attempt = 1; attempt <= 3; attempt++) {
            int changes = world.getMapChangeCount();
            executor.execute(new Action.UsePortal(portal.name()));
            if (await(() -> world.getMapChangeCount() > changes && world.getSelfMapId() == mapId, 5000)) {
                pumpFor(600);   // let the new map's spawns arrive before acting on it
                return;
            }
        }
        throw new SessionOver("portal " + portal.name() + " to " + mapId + " didn't take it there");
    }

    private Optional<WorldState.NpcSighting> awaitNpc(int npcId) throws IOException {
        await(() -> world.getNpcs().stream().anyMatch(n -> n.npcId() == npcId), 4000);
        return world.getNpcs().stream().filter(n -> n.npcId() == npcId).findFirst();
    }

    // ---- the packet loop ----

    private Character self() {
        return Server.getInstance().getWorld(WORLD_ID).getPlayerStorage().getCharacterById(charId);
    }

    private boolean await(BooleanSupplier condition, long timeoutMs) throws IOException {
        long until = System.currentTimeMillis() + timeoutMs;
        while (true) {
            if (safely(condition)) {
                return true;
            }
            if (System.currentTimeMillis() >= until) {
                return false;
            }
            pumpOnce();
        }
    }

    private static boolean safely(BooleanSupplier condition) {
        try {
            return condition.getAsBoolean();
        } catch (RuntimeException e) {
            return false;       // e.g. the character between channel storage states
        }
    }

    private void pumpFor(long ms) throws IOException {
        long until = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < until) {
            pumpOnce();
        }
    }

    private void pumpOnce() throws IOException {
        if (stopRequested.getAsBoolean()) {
            throw new SessionOver("stopped");
        }
        if (System.currentTimeMillis() > hardDeadline) {
            throw new SessionOver("hit its hard deadline");
        }
        try {
            dispatch(conn.receive());
        } catch (SocketTimeoutException e) {
            // quiet tick
        }
        executor.tick();   // paced chat leaves only on a driver tick (see Speech)
        serviceChat();
    }

    private void dispatch(InPacket p) throws IOException {
        int opcode = p.readShort() & 0xFFFF;
        if (opcode == SendOpcode.SET_FIELD.getValue()) {
            world.onSetField(p);
            names.clear();
            // Mandatory after every SET_FIELD or ChangeMapHandler ignores every portal (README section 5).
            conn.send(MapleConnection.packet(RecvOpcode.PLAYER_MAP_TRANSFER.getValue()));
            if (!inWorldSignalled) {
                inWorldSignalled = true;
                onInWorld.run();
            }
        } else if (opcode == SendOpcode.ENTRUSTED_SHOP_CHECK_RESULT.getValue()) {
            shopCheckResult = p.readByte() & 0xFF;
        } else if (opcode == SendOpcode.FREDRICK.getValue()) {
            fredrickOpened = (p.readByte() & 0xFF) == FREDRICK_UI;
        } else if (opcode == SendOpcode.FREDRICK_MESSAGE.getValue()) {
            fredrickResult = p.readByte() & 0xFF;
        } else if (opcode == SendOpcode.WHISPER.getValue()) {
            if ((p.readByte() & 0xFF) == WHISPER_RECEIVED) {
                String sender = p.readString();
                p.readByte();               // channel
                p.readByte();               // from a GM
                queueChat(new ChatEvent(sender, p.readString(), true));
            }
        } else if (opcode == SendOpcode.CHATTEXT.getValue()) {
            int from = p.readInt();
            p.readByte();                   // GM flag
            String text = p.readString();
            String speaker = names.get(from);
            if (from != charId && speaker != null && !HumanPresence.isBotName(speaker) && ResidentChat.addresses(text, profile.name())) {
                queueChat(new ChatEvent(speaker, text, false));
            }
        } else if (opcode == SendOpcode.SPAWN_PLAYER.getValue()) {
            int id = p.readInt();
            p.readByte();                   // level
            names.put(id, p.readString());
            p.seek(2);
            world.accept(opcode, p);
        } else if (opcode == SendOpcode.REMOVE_PLAYER_FROM_MAP.getValue()) {
            names.remove(p.readInt());
            p.seek(2);
            world.accept(opcode, p);
        } else if (opcode == SendOpcode.PLAYER_INTERACTION.getValue()) {
            if (p.available() >= 3 && p.readByte() == ROOM && p.readByte() == 0) {
                log.info("Resident {} got mini-room error {}", profile.name(), p.readByte());
            }
        } else if (opcode == SendOpcode.SERVERMESSAGE.getValue()) {
            int type = p.readByte();
            if (type == 1) {                // popup: how most shop refusals explain themselves
                log.info("Resident {} got popup: {}", profile.name(), p.readString());
            }
        } else {
            world.accept(opcode, p);
        }
    }

    // ---- chat ----

    private void queueChat(ChatEvent event) {
        if (chats.size() < MAX_QUEUED_CHATS) {
            chats.addLast(event);
        }
    }

    private void serviceChat() throws IOException {
        if (pendingReply != null) {
            if (!pendingReply.isDone()) {
                return;
            }
            String reply;
            boolean fromLlm;
            try {
                reply = pendingReply.get();
                fromLlm = reply != null;
            } catch (Exception e) {
                reply = null;
                fromLlm = false;
            }
            if (reply == null) {
                reply = ResidentChat.cannedReply(profile, rng);
            }
            send(pendingFor, reply);
            log.info("Resident {} replied to {} ({}, {}): {} chars", profile.name(), pendingFor.speaker(),
                    pendingFor.whisper() ? "whisper" : "chat", fromLlm ? "LLM" : "canned", reply.length());
            chatReplies++;
            pendingReply = null;
            pendingFor = null;
            lastReplyAt = System.currentTimeMillis();
            return;
        }
        if (chats.isEmpty() || System.currentTimeMillis() - lastReplyAt < REPLY_GAP_MS) {
            return;
        }
        ChatEvent event = chats.pollFirst();
        talkedTo.add(event.speaker());
        String remembered = memory.metPlayer(event.speaker(), Instant.now());
        String shopSummary = shopSummary();
        pendingFor = event;
        pendingReply = services.chatExecutor().submit(() -> ResidentChat.llmReply(services.gateway(), profile, event.speaker(),
                event.message(), remembered, shopSummary, services.config().chatRepliesPerHour()));
    }

    /**
     * What the merchant really lists, with real prices. Seen live: told only "selling 4 kinds of potions",
     * the model quoted a White Potion price the shop didn't have.
     */
    private String shopSummary() {
        HiredMerchant hm = FreeMarket.ownMerchant(WORLD_ID, charId);
        if (hm == null) {
            return "no shop open right now";
        }
        List<String> parts = new ArrayList<>();
        for (PlayerShopItem it : new ArrayList<>(hm.getItems())) {
            if (it.isExist() && it.getBundles() > 0) {
                CatalogItem item = assessment.catalog.stream().filter(c -> c.itemId() == it.getItem().getItemId()).findFirst().orElse(null);
                parts.add((item == null ? "item " + it.getItem().getItemId() : item.name()) + " x" + it.getItem().getQuantity()
                        + " for " + it.getPrice() + " mesos");
            }
        }
        return "\"" + hm.getDescription() + "\": " + (parts.isEmpty() ? "sold out" : String.join(", ", parts));
    }

    private void send(ChatEvent to, String line) throws IOException {
        if (to.whisper()) {
            conn.send(ShopPackets.whisper(to.speaker(), line));
        } else {
            // A reply, not idle chatter: the speech layer pauses for the read before it types.
            executor.execute(new Action.Reply(to.message(), line));
        }
    }

    // ---- memory ----

    private void writeEpisode(String outcome) {
        List<String> sales = new ArrayList<>();
        assessment.soldUnits.forEach((id, units) -> {
            if (units > 0) {
                CatalogItem item = assessment.catalog.stream().filter(c -> c.itemId() == id).findFirst().orElse(null);
                sales.add(units + " " + (item == null ? "item " + id : item.name()));
            }
        });
        String summary = "Wake " + outcome + ". Plan via " + decision.path() + ". Sold since last wake: "
                + (sales.isEmpty() ? "nothing" : String.join(", ", sales)) + ". " + String.join("; ", notes)
                + (talkedTo.isEmpty() ? "" : ". Talked with " + String.join(", ", talkedTo)) + ".";
        memory.append(Instant.now(), "wake", summary);
        log.info("Resident {} episode: {} ({} chat replies)", profile.name(), summary, chatReplies);
    }
}
