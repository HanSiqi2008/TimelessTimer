package com.hsq08.timelesstimer;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The {@code scheduler} expression of a timer unit.
 *
 * <p>Both supported shapes use exactly five whitespace separated fields.</p>
 *
 * <ul>
 *   <li>{@code countdown} - "seconds minutes hours days *": the first four fields are a duration
 *       (seconds 0-59, minutes 0-59, hours 0-23, days 0-3650) and the fifth field must be
 *       {@code *}. Commas and any other symbol are rejected.</li>
 *   <li>{@code schedule} - "minutes hours day-of-month month day-of-week" in the usual Jenkins
 *       order, each field a number or a comma separated list of numbers. {@code *} means "any".
 *       Ranges ({@code 1-5}) and steps ({@code *&#47;5}) are not supported, and there are no
 *       {@code H} aliases. Day of week accepts 0-7 where both 0 and 7 mean Sunday.</li>
 * </ul>
 */
final class TimerSpec {

    static final String TYPE_COUNTDOWN = "countdown";
    static final String TYPE_SCHEDULE = "schedule";

    private final boolean countdown;
    /** countdown: the full duration in seconds; schedule: meaningless. */
    private final long durationSeconds;
    /** schedule: the parsed cron expression; countdown: {@code null}. */
    private final Cron cron;

    private TimerSpec(boolean countdown, long durationSeconds, Cron cron) {
        this.countdown = countdown;
        this.durationSeconds = durationSeconds;
        this.cron = cron;
    }

    boolean isCountdown() {
        return countdown;
    }

    long durationSeconds() {
        return durationSeconds;
    }

    /** Normalises a user supplied type name, defaulting to {@code countdown} when absent. */
    static String normaliseType(String rawType) throws ParseException {
        if (rawType == null || rawType.isBlank()) {
            return TYPE_COUNTDOWN;
        }
        String value = rawType.trim().toLowerCase(Locale.ROOT);
        if (!TYPE_COUNTDOWN.equals(value) && !TYPE_SCHEDULE.equals(value)) {
            throw new ParseException("type 只能是 \"countdown\" 或 \"schedule\"，当前为 \"" + rawType + "\"");
        }
        return value;
    }

    /**
     * Parses a {@code scheduler} value for the given (already normalised) type.
     *
     * @param type one of {@link #TYPE_COUNTDOWN} or {@link #TYPE_SCHEDULE}
     */
    static TimerSpec parse(String type, String scheduler) throws ParseException {
        if (scheduler == null || scheduler.isBlank()) {
            throw new ParseException("scheduler 不能为空");
        }
        String[] fields = scheduler.trim().split("\\s+");
        if (fields.length != 5) {
            throw new ParseException("scheduler 必须由 5 个字段组成（当前 " + fields.length + " 个）：\"" + scheduler + "\"");
        }
        if (TYPE_SCHEDULE.equals(type)) {
            return new TimerSpec(false, 0L, Cron.parse(fields));
        }
        return new TimerSpec(true, parseDuration(fields), null);
    }

    private static long parseDuration(String[] fields) throws ParseException {
        if (!"*".equals(fields[4])) {
            throw new ParseException("countdown 模式的第 5 个字段必须为 *，当前为 \"" + fields[4] + "\"");
        }
        long seconds = field(fields[0], 0, 59, "秒");
        long minutes = field(fields[1], 0, 59, "分");
        long hours = field(fields[2], 0, 23, "时");
        long days = field(fields[3], 0, 3650, "天");
        return seconds + minutes * 60L + hours * 3600L + days * 86400L;
    }

    private static long field(String raw, int min, int max, String label) throws ParseException {
        if (!raw.matches("[0-9]{1,4}")) {
            throw new ParseException("countdown 模式的" + label + "字段必须是纯数字（不能使用逗号或其他符号），当前为 \""
                    + raw + "\"");
        }
        long value = Long.parseLong(raw);
        if (value < min || value > max) {
            throw new ParseException("countdown 模式的" + label + "字段超出范围 " + min + "~" + max + "，当前为 " + value);
        }
        return value;
    }

    /** @return the moment a freshly started countdown will fire */
    long countdownTargetMillis(long startMillis) {
        return startMillis + durationSeconds * 1000L;
    }

    /** @return {@code true} when {@code now} satisfies a schedule expression */
    boolean matches(LocalDateTime now) {
        return cron != null && cron.matches(now);
    }

    /** @return the first minute strictly after {@code after} that satisfies a schedule expression */
    LocalDateTime nextAfter(LocalDateTime after) {
        if (cron == null) {
            return null;
        }
        LocalDateTime candidate = after.plusMinutes(1).withSecond(0).withNano(0);
        // A year of minutes is enough: every supported expression repeats within a year.
        for (int i = 0; i < 366 * 24 * 60 * 2; i++) {
            if (cron.matches(candidate)) {
                return candidate;
            }
            candidate = candidate.plusMinutes(1);
        }
        return null;
    }

    /** A five field cron expression restricted to numbers, {@code *} and comma separated lists. */
    private static final class Cron {

        private final List<Integer> minutes;
        private final List<Integer> hours;
        private final List<Integer> daysOfMonth;
        private final List<Integer> months;
        private final List<Integer> daysOfWeek;
        private final boolean anyDayOfMonth;
        private final boolean anyDayOfWeek;

        private Cron(List<Integer> minutes, List<Integer> hours, List<Integer> daysOfMonth, List<Integer> months,
                     List<Integer> daysOfWeek, boolean anyDayOfMonth, boolean anyDayOfWeek) {
            this.minutes = minutes;
            this.hours = hours;
            this.daysOfMonth = daysOfMonth;
            this.months = months;
            this.daysOfWeek = daysOfWeek;
            this.anyDayOfMonth = anyDayOfMonth;
            this.anyDayOfWeek = anyDayOfWeek;
        }

        static Cron parse(String[] f) throws ParseException {
            boolean anyDom = "*".equals(f[2]);
            boolean anyDow = "*".equals(f[4]);
            List<Integer> minutes = parseField(f[0], 0, 59, "分钟");
            List<Integer> hours = parseField(f[1], 0, 23, "小时");
            List<Integer> daysOfMonth = anyDom ? null : parseField(f[2], 1, 31, "一月第几天");
            List<Integer> months = parseField(f[3], 1, 12, "月份");
            List<Integer> daysOfWeek = anyDow ? null : parseField(f[4], 0, 7, "一周第几天");
            if (daysOfWeek != null) {
                // 7 and 0 both mean Sunday.
                List<Integer> normalised = new ArrayList<>(daysOfWeek.size());
                for (int value : daysOfWeek) {
                    int day = value == 7 ? 0 : value;
                    if (!normalised.contains(day)) {
                        normalised.add(day);
                    }
                }
                daysOfWeek = normalised;
            }
            return new Cron(minutes, hours, daysOfMonth, months, daysOfWeek, anyDom, anyDow);
        }

        private static List<Integer> parseField(String raw, int min, int max, String label) throws ParseException {
            if ("*".equals(raw)) {
                return null;
            }
            if (raw.isEmpty() || raw.contains(" ") || raw.contains("/") || raw.contains("-")) {
                throw new ParseException("schedule 模式的" + label + "字段不支持范围、步长或 H 等标识：\"" + raw + "\"");
            }
            List<Integer> values = new ArrayList<>();
            for (String part : raw.split(",", -1)) {
                String token = part.trim();
                if (!token.matches("[0-9]{1,2}")) {
                    throw new ParseException("schedule 模式的" + label + "字段必须是数字或用逗号分隔的数字列表，当前为 \""
                            + raw + "\"");
                }
                int value = Integer.parseInt(token);
                if (value < min || value > max) {
                    throw new ParseException("schedule 模式的" + label + "字段超出范围 " + min + "~" + max + "，当前为 "
                            + value);
                }
                if (!values.contains(value)) {
                    values.add(value);
                }
            }
            if (values.isEmpty()) {
                throw new ParseException("schedule 模式的" + label + "字段为空");
            }
            return values;
        }

        boolean matches(LocalDateTime time) {
            if (minutes != null && !minutes.contains(time.getMinute())) {
                return false;
            }
            if (hours != null && !hours.contains(time.getHour())) {
                return false;
            }
            if (months != null && !months.contains(time.getMonthValue())) {
                return false;
            }
            boolean dayOfMonthHit = !anyDayOfMonth && daysOfMonth.contains(time.getDayOfMonth());
            // java.time: MONDAY = 1 ... SUNDAY = 7, and the expression uses 0 for Sunday.
            int dayOfWeek = time.getDayOfWeek().getValue() % 7;
            boolean dayOfWeekHit = !anyDayOfWeek && daysOfWeek.contains(dayOfWeek);

            if (!anyDayOfMonth && !anyDayOfWeek) {
                // Classic cron semantics: when both day fields are restricted, either one may match.
                return dayOfMonthHit || dayOfWeekHit;
            }
            if (!anyDayOfMonth) {
                return dayOfMonthHit;
            }
            if (!anyDayOfWeek) {
                return dayOfWeekHit;
            }
            return true;
        }
    }
}
