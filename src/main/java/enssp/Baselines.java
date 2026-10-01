package enssp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Baseline generators used for ablation:
 * <ul>
 *   <li>{@link #randomSearch}: random event sequences under the same budget and stopping rule as
 *       the GA; a sequence is kept when it covers a new sensitivity target.</li>
 *   <li>{@link #ssp}: plain state-sensitivity partitioning without optimisation: one test per
 *       sensitivity target, namely the witness sequence of the first state in which the target
 *       was observed during exploration followed by the exercising event.</li>
 * </ul>
 */
public final class Baselines {

    private Baselines() {}

    public static List<List<Event>> randomSearch(SequenceExecutor exec, SensitivityModel model, EventAlphabet a,
                                                 Random rng, Config cfg, long deadline, long[] evaluations) {
        List<List<Event>> suite = new ArrayList<>();
        Set<String> covered = new HashSet<>();
        long since = 0;
        while (System.currentTimeMillis() < deadline && since < cfg.stagnation && !a.events.isEmpty()
               && covered.size() < model.targets().size()) {
            List<Event> s = new ArrayList<>();
            s.add(a.constructors.get(rng.nextInt(a.constructors.size())));
            int len = 1 + rng.nextInt(cfg.maxLength);
            for (int i = 0; i < len; i++) s.add(a.events.get(rng.nextInt(a.events.size())));
            SequenceExecutor.Trace t = exec.execute(s);
            evaluations[0]++;
            since++;
            boolean fresh = false;
            for (String tg : model.targetsOf(t)) fresh |= covered.add(tg);
            if (fresh) { suite.add(s); since = 0; }
        }
        return suite;
    }

    public static List<List<Event>> ssp(SensitivityModel model, EventAlphabet a) {
        return new ArrayList<>(model.firstWitnesses().values());
    }
}
