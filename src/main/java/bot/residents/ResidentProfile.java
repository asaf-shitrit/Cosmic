package bot.residents;

import bot.planning.OkfDocument;
import bot.residents.market.Speciality;

import java.util.List;

/**
 * A resident's stable identity, read from its OKF {@code Bot Profile} ({@code bot-memory/residents/<name>/profile.md}).
 * Level and job here are the character a resident is set up as once; after that the database is ground
 * truth and nothing reads these back as the "current" level.
 *
 * @param jobLine    bot-knowledge's job vocabulary, for the planner
 * @param traits     personality tags, e.g. {@code [cheerful, chatty]}
 * @param roomMapId  the FM room (910000001-910000022) this resident trades in
 * @param bandMin    lowest item level it stocks (equips by {@code reqLevel}; consumables by band)
 * @param shopTitles titles it picks from when no LLM plan names one
 * @param idleLines  canned chatter
 * @param replyLines canned answers when someone talks to it and no LLM reply is available
 */
public record ResidentProfile(String name, int jobId, String jobLine, int level, boolean female, int face, int hair, int skin,
                              Speciality speciality, int bandMin, int bandMax, List<String> traits, int roomMapId,
                              List<String> shopTitles, List<String> idleLines, List<String> replyLines, String blurb) {

    public ResidentProfile {
        if (name == null || !name.matches("[A-Za-z0-9]{3,12}")) {
            throw new IllegalArgumentException("resident name must be 3-12 alphanumeric characters: " + name);
        }
        if (roomMapId < 910000001 || roomMapId > 910000022) {
            throw new IllegalArgumentException(name + ": fm_room must be an FM room, 910000001-910000022");
        }
        if (shopTitles.isEmpty() || idleLines.isEmpty() || replyLines.isEmpty()) {
            throw new IllegalArgumentException(name + ": needs shop_titles, idle_lines and reply_lines");
        }
    }

    /** "potions 1-70" - the shop focus as the planner and its cache key see it. */
    public String focus() {
        return speciality.name().toLowerCase() + " " + bandMin + "-" + bandMax;
    }

    static ResidentProfile fromOkf(OkfDocument doc) {
        String name = doc.string("bot_id", null);
        return new ResidentProfile(
                name,
                required(doc.integer("resident_job"), name, "resident_job"),
                doc.string("job_line", "beginner"),
                required(doc.integer("resident_level"), name, "resident_level"),
                "female".equalsIgnoreCase(doc.string("gender", "male")),
                required(doc.integer("face"), name, "face"),
                required(doc.integer("hair"), name, "hair"),
                doc.integer("skin") == null ? 0 : doc.integer("skin"),
                Speciality.valueOf(doc.string("speciality", "potions").toUpperCase()),
                doc.integer("band_min") == null ? 1 : doc.integer("band_min"),
                doc.integer("band_max") == null ? 200 : doc.integer("band_max"),
                doc.list("personality"),
                required(doc.integer("fm_room"), name, "fm_room"),
                doc.list("shop_titles"),
                doc.list("idle_lines"),
                doc.list("reply_lines"),
                blurb(doc.body()));
    }

    private static int required(Integer value, String name, String field) {
        if (value == null) {
            throw new IllegalArgumentException("resident " + name + " profile is missing " + field);
        }
        return value;
    }

    /** The first paragraph after the heading - the short description the LLM sees. */
    private static String blurb(String body) {
        for (String para : body.strip().split("\n\\s*\n")) {
            String p = para.strip();
            if (!p.isEmpty() && !p.startsWith("#") && !p.startsWith(">")) {
                return p.replaceAll("\\s+", " ");
            }
        }
        return "";
    }
}
