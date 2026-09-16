/*
 * John - Explorer path selector
 * Mushroom Town (10000)
 *
 * Lets a new character skip the beginner-level requirement and immediately
 * take an Explorer first job.  The character is levelled as a Beginner first
 * so AP, HP, MP, and first-job SP remain consistent with normal progression.
 */

var status = -1;
var selectedJob = -1;

var jobs = [
    {
        id: 100,
        level: 10,
        name: "Warrior",
        future: "Hero, Paladin, or Dark Knight",
        city: 102000000,
        cityName: "Perion",
        items: [1302077]
    },
    {
        id: 200,
        level: 8,
        name: "Magician",
        future: "Fire/Poison Arch Mage, Ice/Lightning Arch Mage, or Bishop",
        city: 101000000,
        cityName: "Ellinia",
        items: [1372043]
    },
    {
        id: 300,
        level: 10,
        name: "Bowman",
        future: "Bowmaster or Marksman",
        city: 100000000,
        cityName: "Henesys",
        items: [1452051, 2060000]
    },
    {
        id: 400,
        level: 10,
        name: "Thief",
        future: "Night Lord or Shadower",
        city: 103000000,
        cityName: "Kerning City",
        items: [1472061, 1332063, 2070015]
    },
    {
        id: 500,
        level: 10,
        name: "Pirate",
        future: "Buccaneer or Corsair",
        city: 120000000,
        cityName: "Nautilus Harbor",
        items: [1482000, 1492000, 2330000]
    }
];

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

    if (status == 0) {
        if (cm.getJobId() != 0) {
            cm.sendOk("You have already chosen the path of a #b" + cm.getJobName(cm.getJobId()) + "#k. Train hard and make it your own!");
            cm.dispose();
            return;
        }

        var text = "I'm Avoda. I can send you directly down an Explorer path. Which path do you want to begin?";
        for (var i = 0; i < jobs.length; i++) {
            text += "\r\n#L" + i + "##b" + jobs[i].name + "#k (later " + jobs[i].future + ")#l";
        }
        text += "\r\n#L5#Remain a Beginner and continue the tutorial#l";
        cm.sendSimple(text);
    } else if (status == 1) {
        if (selection == 5) {
            cm.sendOk("No hurry. Follow the tutorial, and the usual job instructors will still be waiting for you on Victoria Island.");
            cm.dispose();
            return;
        }

        if (selection < 0 || selection >= jobs.length) {
            cm.dispose();
            return;
        }

        selectedJob = selection;
        var job = jobs[selectedJob];
        cm.sendYesNo("Choose #b" + job.name + "#k? I will raise you to level " + job.level + ", perform the first job advancement, reset your beginner stats, and give you starter equipment. This choice cannot be undone.");
    } else if (status == 2) {
        if (cm.getJobId() != 0) {
            cm.sendOk("Your path has already been chosen.");
            cm.dispose();
            return;
        }

        var job = jobs[selectedJob];
        if (!cm.canHoldAll(job.items)) {
            cm.sendOk("Please make enough room in your inventories for the starter equipment, then talk to me again.");
            cm.dispose();
            return;
        }

        while (cm.getLevel() < job.level) {
            cm.getPlayer().levelUp(false);
        }

        cm.changeJobById(job.id);
        cm.resetStats();
        giveStarterItems(job.id);

        cm.sendNext("Your new path begins now. You are a #b" + job.name + "#k! I have given you starter equipment and will send you directly to #b" + job.cityName + "#k.");
    } else if (status == 3) {
        var destination = jobs[selectedJob];
        cm.warp(destination.city, 0);
        cm.dispose();
    }
}

function giveStarterItems(jobId) {
    if (jobId == 100) {
        cm.gainItem(1302077, 1);
    } else if (jobId == 200) {
        cm.gainItem(1372043, 1);
    } else if (jobId == 300) {
        cm.gainItem(1452051, 1);
        cm.gainItem(2060000, 1000);
    } else if (jobId == 400) {
        cm.gainItem(1472061, 1);
        cm.gainItem(1332063, 1);
        cm.gainItem(2070015, 500);
    } else if (jobId == 500) {
        cm.gainItem(1482000, 1);
        cm.gainItem(1492000, 1);
        cm.gainItem(2330000, 1000);
    }
}


