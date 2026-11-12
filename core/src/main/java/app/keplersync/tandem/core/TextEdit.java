package app.keplersync.tandem.core;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * What a text field holds while it's edited: its words, the caret, a selection and the steps to undo. It draws nothing;
 * a field asks it what the arrows, Backspace, Ctrl+A and Ctrl+Z do.
 */
public final class TextEdit {
	private static final int HISTORY = 100;

	private static final class State {
		final String text;
		final int caret, anchor;

		State(String text, int caret, int anchor) {
			this.text = text;
			this.caret = caret;
			this.anchor = anchor;
		}
	}

	private String text;
	/** The caret, and where the selection started: the same place when nothing is selected. */
	private int caret, anchor;
	private final Deque<State> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
	/** The last change was a typed letter, so the next one joins its undo step: Ctrl+Z takes a word back, not a letter. */
	private boolean typing;

	public TextEdit(String initial) {
		text = initial == null ? "" : initial;
		caret = anchor = text.length();
	}

	public String text() {
		return text;
	}

	public int caret() {
		return caret;
	}

	public int from() {
		return Math.min(caret, anchor);
	}

	public int to() {
		return Math.max(caret, anchor);
	}

	public boolean selected() {
		return caret != anchor;
	}

	public String selection() {
		return text.substring(from(), to());
	}

	/** Puts [s] where the caret is, in place of the selection. [typed] letters share one undo step up to the next space. */
	public void insert(String s, boolean typed) {
		if (s.isEmpty() && !selected()) return;
		remember(typed && !s.equals(" "), selected());
		int at = from();
		text = text.substring(0, at) + s + text.substring(to());
		caret = anchor = at + s.length();
	}

	/** Backspace: the selection, else the letter before the caret, or with [word] everything back to the word's start. */
	public void backspace(boolean word) {
		if (!selected()) {
			if (caret == 0) return;
			anchor = word ? wordLeft(caret) : before(caret);
		}
		insert("", false);
	}

	/** Delete: the selection, else the letter after the caret, or with [word] everything up to the next word. */
	public void delete(boolean word) {
		if (!selected()) {
			if (caret == text.length()) return;
			anchor = word ? wordRight(caret) : after(caret);
		}
		insert("", false);
	}

	public void left(boolean word, boolean select) {
		if (!select && selected()) move(from(), false);
		else move(word ? wordLeft(caret) : before(caret), select);
	}

	public void right(boolean word, boolean select) {
		if (!select && selected()) move(to(), false);
		else move(word ? wordRight(caret) : after(caret), select);
	}

	/** The caret goes to [index]; with [select], what's between there and where the selection started is selected. */
	public void move(int index, boolean select) {
		caret = Math.max(0, Math.min(text.length(), index));
		if (!select) anchor = caret;
		typing = false;
	}

	public void selectAll() {
		anchor = 0;
		caret = text.length();
		typing = false;
	}

	/** Takes the selection out and returns it. */
	public String cut() {
		String s = selection();
		insert("", false);
		return s;
	}

	public boolean undo() {
		return step(undo, redo);
	}

	public boolean redo() {
		return step(redo, undo);
	}

	private boolean step(Deque<State> from, Deque<State> to) {
		if (from.isEmpty()) return false;
		to.push(new State(text, caret, anchor));
		State s = from.pop();
		text = s.text;
		caret = s.caret;
		anchor = s.anchor;
		typing = false;
		return true;
	}

	/** Keeps the words as they are for undo, unless this change [joins] a run of typing; one that [starts] a run always does. */
	private void remember(boolean joins, boolean starts) {
		if (starts || !(joins && typing)) {
			undo.push(new State(text, caret, anchor));
			if (undo.size() > HISTORY) undo.removeLast();
		}
		redo.clear();
		typing = joins;
	}

	/** One letter back, or two for a letter made of a pair (an emoji). */
	private int before(int i) {
		if (i <= 0) return 0;
		return i >= 2 && Character.isLowSurrogate(text.charAt(i - 1)) && Character.isHighSurrogate(text.charAt(i - 2)) ? i - 2 : i - 1;
	}

	private int after(int i) {
		if (i >= text.length()) return text.length();
		return i + 1 < text.length() && Character.isHighSurrogate(text.charAt(i)) && Character.isLowSurrogate(text.charAt(i + 1)) ? i + 2 : i + 1;
	}

	private int wordLeft(int i) {
		while (i > 0 && text.charAt(i - 1) == ' ') i--;
		while (i > 0 && text.charAt(i - 1) != ' ') i--;
		return i;
	}

	private int wordRight(int i) {
		while (i < text.length() && text.charAt(i) != ' ') i++;
		while (i < text.length() && text.charAt(i) == ' ') i++;
		return i;
	}
}
