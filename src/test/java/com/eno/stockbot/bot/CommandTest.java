package com.eno.stockbot.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class CommandTest {

    @Test
    void parsesNameAndArgs() {
        Command c = Command.parse("/add 2330 2317").orElseThrow();
        assertEquals("add", c.name());
        assertEquals(List.of("2330", "2317"), c.args());
    }

    @Test
    void stripsBotNameAndSupportsCommasAndDuplicates() {
        Command c = Command.parse("  /ADD@MyStockBot 2330,2317，00878 2330 ").orElseThrow();
        assertEquals("add", c.name());
        assertEquals(List.of("2330", "2317", "00878"), c.args());
    }

    @Test
    void uppercasesArgs() {
        assertEquals(List.of("2881A"), Command.parse("/add 2881a").orElseThrow().args());
    }

    @Test
    void parsesBareSymbolListFromForceReply() {
        assertEquals(List.of("2330", "2317", "00878", "2881A"), Command.args(" 2330 2317,00878、2881a 2330 "));
        assertTrue(Command.args("  ").isEmpty());
        assertTrue(Command.args(null).isEmpty());
    }

    @Test
    void ignoresNonCommands() {
        assertTrue(Command.parse("hello").isEmpty());
        assertTrue(Command.parse("/").isEmpty());
        assertTrue(Command.parse(null).isEmpty());
        assertTrue(Command.parse("/@bot").isEmpty());
    }
}
