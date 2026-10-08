package com.hsq08.timelesstimer;

/**
 * A fully validated timer unit: everything the engine needs to run one timer.
 *
 * @param description optional human readable description
 * @param type        {@code countdown} or {@code schedule} after normalisation
 * @param exec        the command that is dispatched as the console when the timer fires
 * @param autostart   whether the timer starts with the server; when the file does not say,
 *                    {@code countdown} defaults to false and {@code schedule} to true
 * @param next        the timer started right after this one finishes naturally; never {@code null}
 * @param scheduler   the original scheduler expression, kept for diagnostics
 */
record UnitSpec(String description, String type, String exec, boolean autostart, String next,
                String scheduler, TimerSpec timing) implements java.util.function.Supplier<UnitSpec> {

    static UnitSpec from(UnitFiles.UnitData data) throws ParseException {
        String type = TimerSpec.normaliseType(data.type());
        TimerSpec timing = TimerSpec.parse(type, data.scheduler());
        boolean autostart = data.hasAutostart()
                ? data.autostart()
                // Documented default: countdown does not start by itself, schedule does.
                : TimerSpec.TYPE_SCHEDULE.equals(type);
        String next = data.next() == null ? "" : data.next();
        String description = data.description() == null ? "" : data.description();
        return new UnitSpec(description, type, data.exec(), autostart, next, data.scheduler(), timing);
    }

    boolean isCountdown() {
        return timing.isCountdown();
    }

    @Override
    public UnitSpec get() {
        return this;
    }
}
