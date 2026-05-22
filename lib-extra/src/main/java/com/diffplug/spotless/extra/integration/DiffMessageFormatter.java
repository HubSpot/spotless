/*
 * Copyright 2016-2023 DiffPlug
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.diffplug.spotless.extra.integration;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Objects;

import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.MyersDiff;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;

import com.diffplug.common.base.CharMatcher;
import com.diffplug.common.base.Errors;
import com.diffplug.common.base.Preconditions;
import com.diffplug.common.base.Splitter;
import com.diffplug.spotless.Formatter;
import com.diffplug.spotless.LineEnding;
import com.diffplug.spotless.PaddedCell;

/** Formats the messages of failed spotlessCheck invocations with a nice diff message. */
public final class DiffMessageFormatter {
	public static Builder builder() {
		return new Builder();
	}

	interface CleanProvider {

		Path getRootDir();

		Charset getEncoding();

		String getFormatted(File file, String rawUnix);
	}

	private static class CleanProviderFormatter implements CleanProvider {
		private final Formatter formatter;

		CleanProviderFormatter(Formatter formatter) {
			this.formatter = Objects.requireNonNull(formatter);
		}

		@Override
		public Path getRootDir() {
			return formatter.getRootDir();
		}

		@Override
		public Charset getEncoding() {
			return formatter.getEncoding();
		}

		@Override
		public String getFormatted(File file, String rawUnix) {
			String unix = PaddedCell.check(formatter, file, rawUnix).canonical();
			return formatter.computeLineEndings(unix, file);
		}
	}

	private static class CleanProviderFolder implements CleanProvider {
		private final Path rootDir;
		private final Path cleanDir;
		private final Charset encoding;

		CleanProviderFolder(Path rootDir, Path cleanDir, String encoding) {
			this.rootDir = rootDir;
			this.cleanDir = cleanDir;
			this.encoding = Charset.forName(encoding);
		}

		@Override
		public Path getRootDir() {
			return rootDir;
		}

		@Override
		public Charset getEncoding() {
			return encoding;
		}

		@Override
		public String getFormatted(File file, String rawUnix) {
			Path clean = cleanDir.resolve(rootDir.relativize(file.toPath()));
			byte[] content = Errors.rethrow().get(() -> Files.readAllBytes(clean));
			return new String(content, encoding);
		}
	}

	public static class Builder {
		private Builder() {}

		private String runToFix;
		private CleanProvider formatter;
		private List<File> problemFiles;
		/**
		 * When set, the full uncapped unified diff for all violation files is written here.
		 * Unlike the human-readable error message, this patch uses standard unified diff format
		 * with no visible-whitespace substitution, making it suitable for machine consumption
		 * (e.g. applying via patch tools). Appended to if the file already exists.
		 */
		private Path patchOutputFile;

		/** "Run 'gradlew spotlessApply' to fix these violations." */
		public Builder runToFix(String runToFix) {
			this.runToFix = Objects.requireNonNull(runToFix);
			return this;
		}

		public Builder formatter(Formatter formatter) {
			this.formatter = new CleanProviderFormatter(formatter);
			return this;
		}

		public Builder formatterFolder(Path rootDir, Path cleanDir, String encoding) {
			this.formatter = new CleanProviderFolder(rootDir, cleanDir, encoding);
			return this;
		}

		public Builder problemFiles(List<File> problemFiles) {
			this.problemFiles = Objects.requireNonNull(problemFiles);
			Preconditions.checkArgument(!problemFiles.isEmpty(), "cannot be empty");
			return this;
		}

		public Builder patchOutputFile(Path patchOutputFile) {
			this.patchOutputFile = patchOutputFile;
			return this;
		}

		/** Returns the error message. */
		public String getMessage() {
			try {
				Objects.requireNonNull(runToFix, "runToFix");
				Objects.requireNonNull(formatter, "formatter");
				Objects.requireNonNull(problemFiles, "problemFiles");
				DiffMessageFormatter diffFormater = new DiffMessageFormatter(formatter, problemFiles, patchOutputFile);
				return "The following files had format violations:\n"
						+ diffFormater.buffer
						+ runToFix;
			} catch (IOException e) {
				throw Errors.asRuntime(e);
			}
		}
	}

	private static final int MAX_CHECK_MESSAGE_LINES = 50;
	public static final int MAX_FILES_TO_LIST = 10;

	private final StringBuilder buffer = new StringBuilder(MAX_CHECK_MESSAGE_LINES * 64);
	private int numLines = 0;

	private final CleanProvider formatter;

	private DiffMessageFormatter(CleanProvider formatter, List<File> problemFiles, Path patchOutputFile) throws IOException {
		this.formatter = Objects.requireNonNull(formatter, "formatter");
		ListIterator<File> problemIter = problemFiles.listIterator();
		while (problemIter.hasNext() && numLines < MAX_CHECK_MESSAGE_LINES) {
			File file = problemIter.next();
			addFile(relativePath(file) + "\n" + diff(file));
		}
		if (problemIter.hasNext()) {
			int remainingFiles = problemFiles.size() - problemIter.nextIndex();
			if (remainingFiles >= MAX_FILES_TO_LIST) {
				buffer.append("Violations also present in ").append(remainingFiles).append(" other files.\n");
			} else {
				buffer.append("Violations also present in:\n");
				while (problemIter.hasNext()) {
					addIntendedLine(NORMAL_INDENT, relativePath(problemIter.next()));
				}
			}
		}
		if (patchOutputFile != null) {
			writePatchFile(problemFiles, patchOutputFile);
		}
	}

	private void writePatchFile(List<File> problemFiles, Path outputFile) throws IOException {
		StringBuilder patch = new StringBuilder();
		for (File file : problemFiles) {
			String entry = cleanPatchEntry(file);
			if (entry != null) {
				patch.append(entry);
			}
		}
		if (patch.length() > 0) {
			Files.createDirectories(outputFile.getParent());
			Files.writeString(outputFile, patch.toString(), StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		}
	}

	/**
	 * Returns a unified diff patch entry for the given file, without the visible-whitespace
	 * substitution used in the human-readable error message. Returns null if there is no
	 * content difference (e.g. a line-ending-only violation, which cannot be expressed as a
	 * platform-neutral patch).
	 */
	private String cleanPatchEntry(File file) throws IOException {
		String raw = new String(Files.readAllBytes(file.toPath()), formatter.getEncoding());
		String rawUnix = LineEnding.toUnix(raw);
		String formatted = formatter.getFormatted(file, rawUnix);
		String formattedUnix = LineEnding.toUnix(formatted);

		if (rawUnix.equals(formattedUnix)) {
			return null;
		}

		RawText a = new RawText(rawUnix.getBytes(StandardCharsets.UTF_8));
		RawText b = new RawText(formattedUnix.getBytes(StandardCharsets.UTF_8));
		EditList edits = new EditList();
		edits.addAll(MyersDiff.INSTANCE.diff(RawTextComparator.DEFAULT, a, b));

		if (edits.isEmpty()) {
			return null;
		}

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (DiffFormatter df = new DiffFormatter(out)) {
			df.format(edits, a, b);
		}
		String hunks = out.toString(StandardCharsets.UTF_8.name());
		hunks = hunks.replace("\\ No newline at end of file\n", "");

		String relPath = relativePath(file);
		return "--- " + relPath + "\n+++ " + relPath + "\n" + hunks;
	}

	private String relativePath(File file) {
		return formatter.getRootDir().relativize(file.toPath()).toString();
	}

	private static final int MIN_LINES_PER_FILE = 4;
	private static final Splitter NEWLINE_SPLITTER = Splitter.on('\n');

	private void addFile(String arg) {
		// at the very least, we'll print this about a file:
		//     0.txt
		//         @@ -1,2 +1,2 @@,
		//         -1\\r\\n,
		//         -2\\r\\n,
		//     ... (more lines that didn't fit)
		List<String> lines = NEWLINE_SPLITTER.splitToList(arg);
		if (!lines.isEmpty()) {
			addIntendedLine(NORMAL_INDENT, lines.get(0));
		}
		for (int i = 1; i < Math.min(MIN_LINES_PER_FILE, lines.size()); ++i) {
			addIntendedLine(DIFF_INDENT, lines.get(i));
		}

		// then we'll print the rest that can fit
		ListIterator<String> iter = lines.listIterator(Math.min(MIN_LINES_PER_FILE, lines.size()));
		while (iter.hasNext() && numLines < MAX_CHECK_MESSAGE_LINES) {
			addIntendedLine(DIFF_INDENT, iter.next());
		}

		if (numLines >= MAX_CHECK_MESSAGE_LINES) {
			// we're out of space
			if (iter.hasNext()) {
				int linesLeft = lines.size() - iter.nextIndex();
				addIntendedLine(NORMAL_INDENT, "... (" + linesLeft + " more lines that didn't fit)");
			}
		}
	}

	private static final String NORMAL_INDENT = "    ";
	private static final String DIFF_INDENT = NORMAL_INDENT + NORMAL_INDENT;

	private void addIntendedLine(String indent, String line) {
		buffer.append(indent);
		buffer.append(line);
		buffer.append('\n');
		++numLines;
	}

	/**
	 * Returns a git-style diff between the contents of the given file and what those contents would
	 * look like if formatted using the given formatter. Does not end with any newline
	 * sequence (\n, \r, \r\n).
	 */
	private String diff(File file) throws IOException {
		return diff(formatter, file).getValue();
	}

	/**
	 * Returns a map entry with value being a git-style diff between the contents of the given file and what those contents would
	 * look like if formatted using the given formatter. Does not end with any newline
	 * sequence (\n, \r, \r\n). The key of the map entry is the 0-based line where the first difference occurred.
	 */
	public static Map.Entry<Integer, String> diff(Formatter formatter, File file) throws IOException {
		return diff(new CleanProviderFormatter(formatter), file);
	}

	private static Map.Entry<Integer, String> diff(CleanProvider formatter, File file) throws IOException {
		String raw = new String(Files.readAllBytes(file.toPath()), formatter.getEncoding());
		String rawUnix = LineEnding.toUnix(raw);
		String formatted = formatter.getFormatted(file, rawUnix);
		String formattedUnix = LineEnding.toUnix(formatted);

		if (rawUnix.equals(formattedUnix)) {
			// the formatting is fine, so it's a line-ending issue
			return diffWhitespaceLineEndings(raw, formatted, false, true);
		} else {
			return diffWhitespaceLineEndings(rawUnix, formattedUnix, true, false);
		}
	}

	/**
	 * Returns a map entry with value being a git-style diff between the two unix strings and key being the 0-based line of the first difference (in the dirty string)
	 * <p>
	 * Output has no trailing newlines.
	 * <p>
	 * Boolean args determine whether whitespace or line endings will be visible.
	 */
	private static Map.Entry<Integer, String> diffWhitespaceLineEndings(String dirty, String clean, boolean whitespace, boolean lineEndings) throws IOException {
		dirty = visibleWhitespaceLineEndings(dirty, whitespace, lineEndings);
		clean = visibleWhitespaceLineEndings(clean, whitespace, lineEndings);

		RawText a = new RawText(dirty.getBytes(StandardCharsets.UTF_8));
		RawText b = new RawText(clean.getBytes(StandardCharsets.UTF_8));
		EditList edits = new EditList();
		edits.addAll(MyersDiff.INSTANCE.diff(RawTextComparator.DEFAULT, a, b));

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (DiffFormatter formatter = new DiffFormatter(out)) {
			formatter.format(edits, a, b);
		}
		String formatted = out.toString(StandardCharsets.UTF_8.name());

		// we don't need the diff to show this, since we display newlines ourselves
		formatted = formatted.replace("\\ No newline at end of file\n", "");
		return Map.entry(getLineOfFirstDifference(edits), NEWLINE_MATCHER.trimTrailingFrom(formatted));
	}

	private static int getLineOfFirstDifference(EditList edits) {
		return edits.stream().mapToInt(Edit::getBeginA).min().getAsInt();
	}

	private static final CharMatcher NEWLINE_MATCHER = CharMatcher.is('\n');

	/**
	 * Makes the whitespace and/or the lineEndings visible.
	 * <p>
	 * MyersDiff wants inputs with only unix line endings.  So this ensures that that is the case.
	 */
	private static String visibleWhitespaceLineEndings(String input, boolean whitespace, boolean lineEndings) {
		if (whitespace) {
			input = input.replace(' ', MIDDLE_DOT).replace("\t", "\\t");
		}
		if (lineEndings) {
			input = input.replace("\n", "\\n\n").replace("\r", "\\r");
		} else {
			// we want only \n, so if we didn't replace them above, we'll replace them here.
			input = input.replace("\r", "");
		}
		return input;
	}

	private static final char MIDDLE_DOT = '\u00b7';
}
