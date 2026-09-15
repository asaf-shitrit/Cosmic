/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package constants.skills;

/**
 * @author Tyler
 */
public class Warrior {
    public static final int IMPROVED_HPREC = 1000000;
    public static final int IMPROVED_MAXHP = 1000001;
    public static final int ENDURE = 1000002;
    // The skill book (wz/Skill.wz/100.img.xml) files the active 1st-job skills under 1001xxx; the
    // 1000xxx ids that used to be here do not exist, so SkillFactory.getSkill returned null for them.
    // Correcting IRON_BODY does not change buff classification: its WZ action is "alert2", which
    // SkillFactory already treats as a buff before the explicit IRON_BODY case is reached.
    public static final int IRON_BODY = 1001003;
    public static final int POWER_STRIKE = 1001004;
    public static final int SLASH_BLAST = 1001005;
}
