package com.hsq08.timelesstimer;

/**
 * What the mod knows about one timer name.
 *
 * @param spec the parsed unit, or {@code null} when the file exists but is malformed; such an
 *             entry is reported as "计时器'&lt;name&gt;'格式错误" when started and still counted by
 *             {@code /timer list}
 */
record UnitEntry(String name, UnitSpec spec) {
}
