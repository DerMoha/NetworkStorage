package com.dermoha.networkstorage.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemUtilsTest {

    @Test
    void formatEnchantmentNameSingleWord() {
        assertEquals("Sharpness", ItemUtils.formatEnchantmentName("sharpness"));
    }

    @Test
    void formatEnchantmentNameMultiWord() {
        assertEquals("Sweeping Edge", ItemUtils.formatEnchantmentName("sweeping_edge"));
    }

    @Test
    void formatEnchantmentNameThreeWords() {
        assertEquals("Luck Of The Sea", ItemUtils.formatEnchantmentName("luck_of_the_sea"));
    }

    @Test
    void formatEnchantmentNameEmptyKey() {
        assertEquals("", ItemUtils.formatEnchantmentName(""));
    }

    @Test
    void formatEnchantmentNameUppercaseKey() {
        // Implementation lowercases the raw name first, so input case is irrelevant.
        assertEquals("Sharpness", ItemUtils.formatEnchantmentName("SHARPNESS"));
    }

    @Test
    void formatEnchantmentNameAdjacentUnderscores() {
        // "a__b" → "a  b" → split to ["a", "", "b"]; empty middle word is skipped.
        assertEquals("A B", ItemUtils.formatEnchantmentName("a__b"));
    }

    @Test
    void formatEnchantmentNameLeadingUnderscore() {
        // "_b" → " b" → split to ["", "b"]; empty leading word is skipped.
        assertEquals("B", ItemUtils.formatEnchantmentName("_b"));
    }

    @Test
    void formatEnchantmentNameTrailingUnderscore() {
        // "b_" → "b " → split to ["b"] (trailing empty stripped by split).
        assertEquals("B", ItemUtils.formatEnchantmentName("b_"));
    }

    @Test
    void toRomanRange() {
        assertEquals("I", ItemUtils.toRoman(1));
        assertEquals("II", ItemUtils.toRoman(2));
        assertEquals("III", ItemUtils.toRoman(3));
        assertEquals("IV", ItemUtils.toRoman(4));
        assertEquals("V", ItemUtils.toRoman(5));
        assertEquals("VI", ItemUtils.toRoman(6));
        assertEquals("VII", ItemUtils.toRoman(7));
        assertEquals("VIII", ItemUtils.toRoman(8));
        assertEquals("IX", ItemUtils.toRoman(9));
        assertEquals("X", ItemUtils.toRoman(10));
    }

    @Test
    void toRomanZeroReturnsEmpty() {
        assertEquals("", ItemUtils.toRoman(0));
    }

    @Test
    void toRomanNegativeReturnsEmpty() {
        assertEquals("", ItemUtils.toRoman(-1));
    }

    @Test
    void toRomanAboveMaxReturnsDecimal() {
        // Fall-through to String.valueOf for unsupported numbers.
        assertEquals("11", ItemUtils.toRoman(11));
        assertEquals("25", ItemUtils.toRoman(25));
    }

    @Test
    void matchesEnchantmentByName() {
        assertTrue(ItemUtils.matchesEnchantment("sharpness", 5, "sharpness"));
    }

    @Test
    void matchesEnchantmentByRawKeySubstring() {
        assertTrue(ItemUtils.matchesEnchantment("sharpness", 5, "sharp"));
    }

    @Test
    void matchesEnchantmentByNumericLevel() {
        assertTrue(ItemUtils.matchesEnchantment("sharpness", 5, "5"));
    }

    @Test
    void matchesEnchantmentByRomanLevel() {
        assertTrue(ItemUtils.matchesEnchantment("sharpness", 5, "v"));
    }

    @Test
    void matchesEnchantmentNameAndLevel() {
        assertTrue(ItemUtils.matchesEnchantment("sharpness", 5, "sharpness 5"));
    }

    @Test
    void matchesEnchantmentRomanNameAndLevel() {
        assertTrue(ItemUtils.matchesEnchantment("sharpness", 4, "sharpness iv"));
    }

    @Test
    void matchesEnchantmentWrongLevel() {
        assertFalse(ItemUtils.matchesEnchantment("sharpness", 5, "sharpness 3"));
    }

    @Test
    void matchesEnchantmentWrongName() {
        assertFalse(ItemUtils.matchesEnchantment("sharpness", 5, "efficiency"));
    }

    @Test
    void matchesEnchantmentNoMatch() {
        assertFalse(ItemUtils.matchesEnchantment("sharpness", 5, "xyz"));
    }

    @Test
    void matchesEnchantmentEmptyFilterMatchesAll() {
        // Every string contains "" — call site already short-circuits on empty filter,
        // but matchesEnchantment itself returns true for an empty filter.
        assertTrue(ItemUtils.matchesEnchantment("sharpness", 5, ""));
    }

    @Test
    void matchesEnchantmentMixedCaseFilterDoesNotMatch() {
        // The filter parameter contract is "lowerCaseFilter" — the implementation
        // does NOT lowercase the filter internally. The caller is responsible for
        // lowercasing (see TerminalGUI.updateInventory).
        assertFalse(ItemUtils.matchesEnchantment("sharpness", 5, "SHARP"));
    }

    @Test
    void matchesEnchantmentLeadingTrailingWhitespace() {
        // split("\\s+") trims surrounding whitespace.
        assertTrue(ItemUtils.matchesEnchantment("sharpness", 5, "  sharpness  "));
    }
}
