package app.keplersync.tandem.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TextEditTest {
	private static TextEdit typed(String s) {
		TextEdit e = new TextEdit("");
		for (char c : s.toCharArray()) e.insert(String.valueOf(c), true);
		return e;
	}

	@Test
	void undoTakesBackAWordAndRedoBringsIt() {
		TextEdit e = typed("sky base");
		assertTrue(e.undo());
		assertEquals("sky ", e.text(), "the letters since the last space");
		assertTrue(e.undo());
		assertEquals("sky", e.text(), "the space is its own step");
		assertTrue(e.undo());
		assertEquals("", e.text());
		assertFalse(e.undo());
		assertTrue(e.redo());
		assertTrue(e.redo());
		assertTrue(e.redo());
		assertEquals("sky base", e.text());
		assertFalse(e.redo());
		e.undo();
		e.insert("x", true);
		assertFalse(e.redo(), "typing after an undo drops what was undone");
	}

	@Test
	void selectAllThenTypingReplacesEverything() {
		TextEdit e = typed("old words");
		e.selectAll();
		assertEquals("old words", e.selection());
		e.insert("n", true);
		assertEquals("n", e.text());
		e.insert("e", true);
		e.insert("w", true);
		e.undo();
		assertEquals("old words", e.text(), "what replaced the selection goes in one step");
		assertTrue(e.selected(), "the selection comes back with the words");
	}

	@Test
	void backspaceAndDeleteTakeALetterOrAWord() {
		TextEdit e = typed("one two  three");
		e.backspace(true);
		assertEquals("one two  ", e.text());
		e.backspace(true);
		assertEquals("one ", e.text(), "the spaces go with the word before them");
		e.backspace(false);
		assertEquals("one", e.text());
		e.move(0, false);
		e.backspace(false);
		assertEquals("one", e.text(), "nothing before the start");
		e.delete(false);
		assertEquals("ne", e.text());
		e.insert("a b ", false);
		e.move(0, false);
		e.delete(true);
		assertEquals("b ne", e.text());
		e.move(4, false);
		e.delete(true);
		assertEquals("b ne", e.text(), "nothing after the end");
	}

	@Test
	void arrowsMoveByLetterAndWordAndSelectWithShift() {
		TextEdit e = typed("ab cd");
		e.left(false, false);
		assertEquals(4, e.caret());
		e.left(true, true);
		assertEquals("c", e.selection());
		e.left(true, true);
		assertEquals("ab c", e.selection());
		e.right(false, false);
		assertEquals(4, e.caret(), "without Shift, right goes to the selection's end");
		assertFalse(e.selected());
		e.move(0, false);
		e.right(true, false);
		assertEquals(3, e.caret(), "past the word and its space");
		e.move(99, true);
		assertEquals("cd", e.selection());
		assertEquals("cd", e.cut());
		assertEquals("ab ", e.text());
	}

	@Test
	void aPairedLetterGoesInOnePress() {
		TextEdit e = new TextEdit("a😀");
		e.backspace(false);
		assertEquals("a", e.text());
		e.insert("😀b", false);
		e.move(1, false);
		e.right(false, false);
		assertEquals(3, e.caret());
		e.left(false, false);
		e.delete(false);
		assertEquals("ab", e.text());
	}
}
