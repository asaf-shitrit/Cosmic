package server.life;

import tools.Randomizer;

/**
 * What a {@link FakePlayer} is pretending to be doing, which decides where it walks to next and
 * how long it stands still when it gets there.
 *
 * <p>This exists because uniformly scattered wanderers don't read as a populated world. Real v83
 * towns were lopsided: the Free Market packed with parked vendors, party quest entrances clogged
 * with people waiting on a fifth member, and only a thin stream of players actually crossing a
 * map. Mixing these weights per map reproduces that shape.
 *
 * <p>Training fields looked different again: a few people working the mobs where they spawn,
 * someone walking on to the next group of mobs, and one person parked clear of the spawns
 * recovering. {@link FakePlayerService} reads the map's own spawn points to place those.
 */
public enum FakePlayerActivity {
    /** Crossing the map to somewhere else. Long walks, barely stops. */
    TRAVELLING(1, 3),
    /** Doing the rounds of the NPCs - shops, quest givers, storage. Medium stops. */
    BROWSING(3, 8),
    /** Parked in one spot for a very long time, the way Free Market shops are. */
    VENDING(30, 90),
    /** Loitering in a group, e.g. waiting for a party quest to fill. */
    WAITING(8, 20),
    /**
     * Working a mob spawn: short hops around the mobs it was placed on, staying in one place
     * because that is where the spawn is.
     */
    GRINDING(4, 10),
    /** Walking on to the next group of mobs. Barely stops - it has only just cleared where it was. */
    CLEARING(1, 4),
    /**
     * Sitting clear of the mobs, e.g. out of HP, waiting on a respawn or just logged in. Long
     * stops, and placed away from spawns rather than on them.
     */
    RESTING(20, 45);

    private final int minDwellTicks;
    private final int maxDwellTicks;

    FakePlayerActivity(int minDwellTicks, int maxDwellTicks) {
        this.minDwellTicks = minDwellTicks;
        this.maxDwellTicks = maxDwellTicks;
    }

    /** How many ticks to stand still on arriving somewhere. */
    public int rollDwellTicks() {
        return minDwellTicks + Randomizer.nextInt(maxDwellTicks - minDwellTicks + 1);
    }

    /** Whether this activity sticks close to the spot it was spawned at. */
    public boolean isAnchored() {
        return this == VENDING || this == WAITING || this == GRINDING || this == RESTING;
    }
}
