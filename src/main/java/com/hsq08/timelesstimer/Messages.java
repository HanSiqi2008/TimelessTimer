package com.hsq08.timelesstimer;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

/** Formats every chat message the mod prints. */
final class Messages {

    private final String name;

    Messages(String name) {
        this.name = name;
    }

    String name() {
        return name;
    }

    /** The fixed prefix, e.g. {@code [TimelessTimer]: }. */
    String prefix() {
        return "[" + name + "]: ";
    }

    /** The {@code /timer list} header, e.g. {@code [TimelessTimer]:} - deliberately without a space. */
    String header() {
        return "[" + name + "]:";
    }

    /** A message carrying the configured prefix. */
    Component prefixed(String text) {
        return Component.literal(prefix() + text);
    }

    /** The list header component, which has no trailing space. */
    Component headerComponent() {
        return Component.literal(header());
    }

    /** An error message: same prefix, red so that it stands out in chat. */
    Component error(String text) {
        return Component.literal(prefix() + text).withStyle(ChatFormatting.RED);
    }

    /** A message without any prefix, used for the timer list body. */
    Component plain(String text) {
        return Component.literal(text);
    }

    void send(CommandSourceStack source, String text) {
        source.sendSuccess(() -> prefixed(text), false);
    }

    void sendError(CommandSourceStack source, String text) {
        source.sendFailure(error(text));
    }

    void sendPlain(CommandSourceStack source, String text) {
        source.sendSuccess(() -> plain(text), false);
    }
}
