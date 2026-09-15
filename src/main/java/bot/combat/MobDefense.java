package bot.combat;

import provider.Data;
import provider.DataProvider;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
import tools.StringUtil;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A monster's level, weapon and magic defence and avoid, read from the monster's WZ entry the way a client has
 * them. Avoid in particular isn't available elsewhere: the server's {@code MonsterStats} doesn't load
 * {@code eva}, because the server never decides hits.
 */
public final class MobDefense {
    private static final Map<Integer, DamageModel.Target> CACHE = new ConcurrentHashMap<>();

    /**
     * Holder, so loading this class doesn't touch {@link WZFiles}: its directory is fixed once per JVM
     * on first use, and tests that point it at a temp dir must get there first.
     */
    private static final class Source {
        /** Own provider instance: {@code XMLWZFile#getData} is synchronized, so sharing the server's would contend with spawns. */
        static final DataProvider MOB = DataProviderFactory.getDataProvider(WZFiles.MOB);
    }

    private MobDefense() {}

    /** An unknown id counts as an undefended level-1 monster rather than failing the attack. */
    public static DamageModel.Target of(int monsterId) {
        return CACHE.computeIfAbsent(monsterId, id -> read(Source.MOB, id));
    }

    private static DamageModel.Target read(DataProvider mobSource, int monsterId) {
        Data mob = mobSource.getData(StringUtil.getLeftPaddedStr(monsterId + ".img", '0', 11));
        if (mob == null) {
            return new DamageModel.Target(1, 0, 0);
        }
        return new DamageModel.Target(DataTool.getIntConvert("info/level", mob, 1),
                DataTool.getIntConvert("info/PDDamage", mob, 0),
                DataTool.getIntConvert("info/eva", mob, 0),
                DataTool.getIntConvert("info/MDDamage", mob, 0));
    }
}
