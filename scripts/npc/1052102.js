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
            + "They'll join your party, follow you, and help you fight.#b\r\n"
            + "#L0#Call some adventuring companions#l\r\n"
            + "#L1#Who's following me?#l\r\n"
            + "#L2#Send my friends home#l\r\n"
            + "#L3#How do companions work?#l\r\n"
            + "#L4#How do we run Kerning Party Quest?#l");
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
        } else if (choice == 3) {
            cm.sendOk("You can bring up to three companions. They come at your level, fight nearby monsters "
                + "while staying close to you, and follow you between maps. They leave when you log out or change channels.\r\n\r\n"
                + "Below level 30 they fight the way their jobs do: one is a #bWarrior#k with Power Strike, one a "
                + "#bMagician#k (from level 8) casting Energy Bolt and then Magic Claw, and one a #bBowman#k (from level 10) "
                + "shooting Arrow Blow and then Double Shot, with Critical Shot as it grows. Until those levels they are Warriors too.\r\n\r\n"
                + "From level 30 two are #bSpearmen#k who cast Hyper Body on you, and the third is a #bCleric#k who "
                + "heals you and casts Bless. They carry Blue Potions for MP, and the Bowman its own arrows.\r\n\r\n"
                + "Speak to me to see who's with you or send them home.");
            cm.dispose();
        } else if (choice == 4) {
            cm.sendOk("For Kerning Party Quest, you must be level #b21-30#k and lead a party with "
                + "#bthree of your companions#k. Wait until all three arrive, then talk to #bLakelis#k.\r\n\r\n"
                + "Your companions collect coupons and drop their passes for you. Pick those passes up and "
                + "talk to #bCloto#k to clear the stage.\r\n\r\n"
                + "In the rope, platform and barrel puzzles, stay by Cloto, off the puzzle positions. "
                + "Your companions try the positions; check with Cloto when they tell you they're ready. "
                + "After the last fight, gather ten passes and give them to Cloto.");
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
