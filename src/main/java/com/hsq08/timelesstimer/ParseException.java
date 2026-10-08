package com.hsq08.timelesstimer;

/** Thrown when a configuration file cannot be parsed; carries the offending line. */
class ParseException extends Exception {

    private static final long serialVersionUID = 1L;

    private final int line;

    ParseException(int line, String message) {
        super(line > 0 ? "第 " + line + " 行：" + message : message);
        this.line = line;
    }

    ParseException(String message) {
        this(0, message);
    }

    int line() {
        return line;
    }
}
