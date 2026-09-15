/*
 * Shumi (1052102), Kerning City - summons real bot players into the talking player's party.
 *
 * The bots are genuine clients run by bot.party.BotPartySupervisor inside the server; this script
 * is only the menu. Every decision (caps, cooldown, party room, level) is made in Java, which
 * returns ready-to-show text, so the rules live in one place.
 */
var status;
var choice;

function supervisor() {
    try {
        return Java.type('bot.party.BotPartySupervisor').getInstance();
    } catch (e) {
        return null;    // server build without the supervisor
    }
}

function start() {
    status = -1;
    action(1, 0, 0);
}

function action(mode, type, selection) {
    if (mode != 1) {
        cm.dispose();
        return;
    }
    status++;

    var sup = supervisor();
    if (sup == null) {
        cm.sendOk("My friends are all busy today. Come back another time!");
        cm.dispose();
        return;
    }

    if (status == 0) {
        cm.sendSimple("Heading out alone? I know a few adventurers who'd happily tag along with you. "
            + "They'll join your party and follow you around.#b\r\n"
            + "#L0#Call some friends to follow me#l\r\n"
            + "#L1#Who's following me?#l\r\n"
            + "#L2#Send my friends home#l");
    } else if (status == 1) {
        choice = selection;
        if (choice == 0) {
            var room = sup.summonCapacity(cm.getPlayer());
            if (room <= 0) {
                cm.sendOk(sup.summonBlockedReason(cm.getPlayer()));
                cm.dispose();
            } else {
                cm.sendGetNumber("How many should I call? (1 - " + room + ")", 1, 1, room);
            }
        } else if (choice == 1) {
            cm.sendOk(sup.describe(cm.getPlayer()));
            cm.dispose();
        } else if (choice == 2) {
            cm.sendOk(sup.dismiss(cm.getPlayer()));
            cm.dispose();
        } else {
            cm.dispose();
        }
    } else if (status == 2 && choice == 0) {
        cm.sendOk(sup.summon(cm.getPlayer(), selection));
        cm.dispose();
    } else {
        cm.dispose();
    }
}
