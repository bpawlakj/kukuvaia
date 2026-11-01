package ai.kukuvaia.agent.daemon.def;

/**
 * What to do when a job is triggered while a previous run is still active.
 *
 * @param mode          {@code SKIP} (skip-if-running, finding #17) or {@code ALLOW}
 * @param maxConcurrent with {@code ALLOW}: how many runs of this job may be active at once
 */
public record Overlap(Mode mode, int maxConcurrent) {

    public enum Mode { SKIP, ALLOW }

    public Overlap {
        if (mode == null) mode = Mode.SKIP;
        if (mode == Mode.SKIP) maxConcurrent = 1;
        if (maxConcurrent < 1) maxConcurrent = 1;
    }

    public static Overlap skip() {
        return new Overlap(Mode.SKIP, 1);
    }

    public static Overlap allow(int maxConcurrent) {
        return new Overlap(Mode.ALLOW, maxConcurrent);
    }
}
