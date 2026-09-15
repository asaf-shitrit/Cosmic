package bot.combat;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The numbers the companion code copies out of WZ data, checked against the extracted files.
 *
 * <p>Parsed with plain DOM and XPath on purpose, never the server's WZ reader: that one initialises
 * {@code GameConstants}, which fixes {@code WZFiles.DIRECTORY} for the whole test JVM and breaks
 * {@code MobSkillFactoryTest} whenever it runs later.
 */
class CompanionWzDataTest {
    private static final XPath XPATH = XPathFactory.newInstance().newXPath();

    private static Document read(Path path) throws Exception {
        assumeTrue(Files.exists(path), "extracted WZ data not present: " + path);
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(path.toFile());
    }

    /** An int-valued child of {@code parentXPath} (any WZ node type), 0 when absent. */
    private static int value(Document doc, String parentXPath, String name) throws Exception {
        String v = XPATH.evaluate(parentXPath + "/*[@name='" + name + "']/@value", doc);
        return v.isEmpty() ? 0 : Integer.parseInt(v);
    }

    @Test
    void wandAndBowRequirementsMatchCharacterWz() throws Exception {
        for (CompanionLoadout.Weapon weapon : List.of(CompanionLoadout.WANDS, CompanionLoadout.BOWS).stream()
                .flatMap(List::stream).toList()) {
            Document doc = read(Path.of("wz", "Character.wz", "Weapon", String.format("%08d.img.xml", weapon.id())));
            String info = "/imgdir/imgdir[@name='info']";
            assertEquals(weapon, new CompanionLoadout.Weapon(weapon.id(), value(doc, info, "reqLevel"),
                    value(doc, info, "reqSTR"), value(doc, info, "reqDEX"), value(doc, info, "reqINT"),
                    value(doc, info, "reqLUK")), "item " + weapon.id());
        }
    }

    @Test
    void arrowsAreBowArrowsInStacksOfTwoThousand() throws Exception {
        Document doc = read(Path.of("wz", "Item.wz", "Consume", "0206.img.xml"));
        assertEquals(2000, value(doc, "/imgdir/imgdir[@name='02060000']/imgdir[@name='info']", "slotMax"));
        assertEquals(0, value(doc, "/imgdir/imgdir[@name='02060000']/imgdir[@name='info']", "incPAD"));
    }

    @Test
    void spellMasteryFollowsTheSkillLevelLikeWeaponMastery() throws Exception {
        Document doc = read(Path.of("wz", "Skill.wz", "200.img.xml"));
        for (int skill : new int[]{2001004, 2001005}) {
            for (int level = 1; level <= 20; level++) {
                String node = "/imgdir/imgdir[@name='skill']/imgdir[@name='" + skill + "']/imgdir[@name='level']/imgdir[@name='" + level + "']";
                // Skill.wz mastery m is 10 + 5m percent (Energy Bolt's text: level 1 "mastery 15%", level 20 "60%")
                assertEquals(CombatMath.masteryPercentForLevel(level), 10 + 5 * value(doc, node, "mastery"),
                        "skill " + skill + " level " + level);
            }
        }
        String claw20 = "/imgdir/imgdir[@name='skill']/imgdir[@name='2001005']/imgdir[@name='level']/imgdir[@name='20']";
        assertEquals(40, value(doc, claw20, "mad"));
        assertEquals(2, value(doc, claw20, "attackCount"));
    }

    @Test
    void bowmanSkillDataMatchesWhatTheModelAssumes() throws Exception {
        Document doc = read(Path.of("wz", "Skill.wz", "300.img.xml"));
        String level = "/imgdir/imgdir[@name='skill']/imgdir[@name='%d']/imgdir[@name='level']/imgdir[@name='%d']";
        assertEquals(40, value(doc, String.format(level, 3000001, 20), "prop"));
        assertEquals(200, value(doc, String.format(level, 3000001, 20), "damage"));
        assertEquals(130, value(doc, String.format(level, 3001005, 20), "damage"));
        assertEquals(2, value(doc, String.format(level, 3001005, 20), "bulletCount"));
        assertEquals(5, value(doc, String.format(level, 3000000, 5), "x"));
    }

    @Test
    void ligatorHasTheMagicDefenceTheMagicTestsUse() throws Exception {
        Document doc = read(Path.of("wz", "Mob.wz", "9300001.img.xml"));
        String info = "/imgdir/imgdir[@name='info']";
        assertEquals(new DamageModel.Target(32, 90, 10, 80), new DamageModel.Target(value(doc, info, "level"),
                value(doc, info, "PDDamage"), value(doc, info, "eva"), value(doc, info, "MDDamage")));
    }
}
