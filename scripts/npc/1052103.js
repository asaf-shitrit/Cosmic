/*
    Nella (1052103) - Kerning City

    Lets a player manage the cosmetic "fake player" crowds (server.life.FakePlayerService) on
    their current map: see counts, add some, or clear the map.

    Fake players are cheap (one shared walk tick, no client), but not free, and this NPC is
    reachable by any player repeatedly. Two caps keep that bounded:
      - ADD_CAP: the most this NPC will add in one visit. Matches the !fakeplayer GM command's
        existing per-use cap, so this menu can't do more per click than a GM already could.
      - PER_MAP_CAP: the most fake players this NPC will let accumulate on a single map, across
        any number of visits. Startup crowds top out well under this (FAKE_PLAYERS_PER_TOWN=6 x
        Kerning City's 2.0 density = 12 by default), so this still leaves plenty of headroom for
        a denser crowd while ruling out piling on hundreds through repeated use.
    Both are re-checked against the live count right before spawning, not just at menu time, to
    close most of the window for two players adding at once - though without a Java-side lock
    that window isn't fully closed, just short.
*/

var status = -1;
var mainChoice = -1;

var FakePlayerServiceClass = Java.type('server.life.FakePlayerService');

var ADD_CAP = 30;
var PER_MAP_CAP = 60;

function start() {
    status = -1;
    action(1, 0, 0);
}

function action(mode, type, selection) {
    if (mode == 1) {
        status++;
    } else {
        cm.sendOk("Suit yourself.");
        cm.dispose();
        return;
    }

    var service = FakePlayerServiceClass.getInstance();
    var map = cm.getPlayer().getMap();

    if (status == 0) {
        var here = service.countIn(map);
        var all = service.countAll();
        cm.sendSimple("Name's Nella. I keep an eye on the crowds around here.#b\r\n"
            + "Fake players on this map: #r" + here + "#k\r\n"
            + "Fake players server-wide: #r" + all + "#k\r\n\r\n"
            + "#L0#Add fake players to this map#l\r\n"
            + "#L1#Remove all fake players from this map#l\r\n"
            + "#L2#Never mind#l");
    } else if (status == 1) {
        mainChoice = selection;

        if (mainChoice == 0) {
            var here1 = service.countIn(map);
            var room = PER_MAP_CAP - here1;
            if (room <= 0) {
                cm.sendOk("This map already has " + here1 + " fake players, which is my cap of "
                    + PER_MAP_CAP + " for one map. Clear some out before adding more.");
                cm.dispose();
                return;
            }

            var max = Math.min(ADD_CAP, room);
            var prompt = "How many should I add to this map? I'll add at most #r" + max + "#k this time";
            prompt += (max < ADD_CAP)
                ? (" - this map is getting close to my per-map cap of " + PER_MAP_CAP + ".")
                : ".";
            cm.sendGetNumber(prompt, Math.min(5, max), 1, max);
        } else if (mainChoice == 1) {
            var here2 = service.countIn(map);
            if (here2 <= 0) {
                cm.sendOk("There's nobody fake standing around here for me to clear out.");
                cm.dispose();
                return;
            }
            cm.sendYesNo("Remove all #r" + here2 + "#k fake players from this map? This can't be undone.");
        } else {
            cm.sendOk("Alright, let me know if you need anything.");
            cm.dispose();
        }
    } else if (status == 2) {
        if (mainChoice == 0) {
            var here3 = service.countIn(map);
            var room3 = Math.max(0, PER_MAP_CAP - here3);
            if (room3 <= 0) {
                cm.sendOk("This map filled up to my cap of " + PER_MAP_CAP
                    + " while we were talking. Try again later.");
                cm.dispose();
                return;
            }

            var cap = Math.min(ADD_CAP, room3);
            var requested = selection;
            var toAdd = Math.max(1, Math.min(requested, cap));

            var spawned = service.populate(map, toAdd);
            var msg = "Added " + spawned + " fake player" + (spawned == 1 ? "" : "s") + " to this map.";
            if (requested > cap) {
                msg += " I capped it at " + cap + " (per-visit cap " + ADD_CAP
                    + ", this map's cap " + PER_MAP_CAP + ").";
            }
            if (spawned < toAdd) {
                msg += " Only " + spawned + " of " + toAdd + " found ground to stand on.";
            }
            msg += " Now on this map: " + service.countIn(map) + ".";
            cm.sendOk(msg);
        } else if (mainChoice == 1) {
            var removed = service.despawnAll(map);
            cm.sendOk("Removed " + removed + " fake player" + (removed == 1 ? "" : "s") + " from this map.");
        }
        cm.dispose();
    } else {
        cm.dispose();
    }
}
