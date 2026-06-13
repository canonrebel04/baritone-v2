package baritone.api.command.datatypes;

import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.Assert.*;

public class RelativeFileTest {
    private static class DummyArgConsumer implements baritone.api.command.argument.IArgConsumer {
        private final String string;
        public DummyArgConsumer(String string) { this.string = string; }

        @Override public String getString() { return string; }
        @Override public boolean is(Class<?> type) { return false; }
        @Override public boolean is(Class<?> type, int index) { return false; }
        @Override public java.util.LinkedList<baritone.api.command.argument.ICommandArgument> getArgs() { return new java.util.LinkedList<>(); }

        @Override public boolean has(int num) { return false; }
        @Override public boolean hasAny() { return false; }
        @Override public boolean hasExactlyOne() { return false; }
        @Override public boolean hasAtMostOne() { return false; }
        @Override public boolean hasAtMost(int num) { return false; }
        @Override public boolean hasExactly(int num) { return false; }
        @Override public baritone.api.command.argument.ICommandArgument peek() { return null; }
        @Override public baritone.api.command.argument.ICommandArgument peek(int index) { return null; }
        @Override public String peekString() { return null; }
        @Override public String peekString(int index) { return null; }

        @Override public <E extends Enum<?>> E peekEnum(Class<E> enumClass) { return null; }
        @Override public <E extends Enum<?>> E peekEnum(Class<E> enumClass, int index) { return null; }


        @Override public <E extends Enum<?>> E peekEnumOrNull(Class<E> enumClass) { return null; }
        @Override public <E extends Enum<?>> E peekEnumOrNull(Class<E> enumClass, int index) { return null; }

        @Override public <T> T peekAs(Class<T> type) { return null; }
        @Override public <T> T peekAs(Class<T> type, int index) { return null; }
        @Override public <T> T peekAsOrDefault(Class<T> type, T def) { return null; }
        @Override public <T> T peekAsOrDefault(Class<T> type, T def, int index) { return null; }
        @Override public <T> T peekAsOrNull(Class<T> type) { return null; }
        @Override public <T> T peekAsOrNull(Class<T> type, int index) { return null; }

        @Override public <T, O, D extends IDatatypePost<T, O>> T peekDatatypePost(D datatype, O original) { return null; }
        @Override public <T, O, D extends IDatatypePost<T, O>> T peekDatatypePostOrDefault(D datatype, O original, T _default) { return null; }
        @Override public <T, O, D extends IDatatypePost<T, O>> T peekDatatypePostOrNull(D datatype, O original) { return null; }
        @Override public <T, O> T peekDatatypeOrNull(IDatatypePost<T, O> datatype) { return null; }



        @Override public <T, O> T peekDatatype(IDatatypePost<T, O> datatype, O original) { return null; }
        @Override public <T, O> T peekDatatype(IDatatypePost<T, O> datatype) { return null; }

        @Override public <T> T peekDatatype(IDatatypeFor<T> datatype) { return null; }


        @Override public <T, D extends IDatatypeFor<T>> T peekDatatypeFor(Class<D> datatype) { return null; }
        @Override public <T, D extends IDatatypeFor<T>> T peekDatatypeForOrDefault(Class<D> datatype, T def) { return null; }
        @Override public <T, D extends IDatatypeFor<T>> T peekDatatypeForOrNull(Class<D> datatype) { return null; }
        @Override public <T> T peekDatatypeOrNull(IDatatypeFor<T> datatype) { return null; }

        @Override public baritone.api.command.argument.ICommandArgument get() { return null; }

        @Override public <E extends Enum<?>> E getEnum(Class<E> enumClass) { return null; }
        @Override public <E extends Enum<?>> E getEnumOrDefault(Class<E> enumClass, E def) { return null; }
        @Override public <E extends Enum<?>> E getEnumOrNull(Class<E> enumClass) { return null; }

        @Override public <T> T getAs(Class<T> type) { return null; }
        @Override public <T> T getAsOrDefault(Class<T> type, T def) { return null; }
        @Override public <T> T getAsOrNull(Class<T> type) { return null; }

        @Override public <T, O, D extends IDatatypePost<T, O>> T getDatatypePost(D datatype, O original) { return null; }
        @Override public <T, O, D extends IDatatypePost<T, O>> T getDatatypePostOrDefault(D datatype, O original, T _default) { return null; }
        @Override public <T, O, D extends IDatatypePost<T, O>> T getDatatypePostOrNull(D datatype, O original) { return null; }

        @Override public <T, D extends IDatatypeFor<T>> T getDatatypeFor(D datatype) { return null; }
        @Override public <T, D extends IDatatypeFor<T>> T getDatatypeForOrDefault(D datatype, T def) { return null; }
        @Override public <T, D extends IDatatypeFor<T>> T getDatatypeForOrNull(D datatype) { return null; }

        @Override public <T extends IDatatype> java.util.stream.Stream<String> tabCompleteDatatype(T datatype) { return null; }

        @Override public String rawRest() { return null; }

        @Override public void requireMin(int min) {}
        @Override public void requireMax(int max) {}
        @Override public void requireExactly(int args) {}

        @Override public boolean hasConsumed() { return false; }
        @Override public baritone.api.command.argument.ICommandArgument consumed() { return null; }
        @Override public java.util.LinkedList<baritone.api.command.argument.ICommandArgument> getConsumed() { return new java.util.LinkedList<>(); }
        @Override public String consumedString() { return null; }
        @Override public baritone.api.command.argument.IArgConsumer copy() { return null; }
    }


    private static class DummyDatatypeContext implements IDatatypeContext {
        private final IArgConsumer consumer;
        public DummyDatatypeContext(IArgConsumer consumer) { this.consumer = consumer; }
        @Override public IArgConsumer getConsumer() { return consumer; }
        @Override public baritone.api.IBaritone getBaritone() { return null; }
    }


    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void testApplyValidPath() throws CommandException, IOException {
        File baseDir = folder.newFolder().getCanonicalFile();
        File validFile = new File(baseDir, "test.txt");
        validFile.createNewFile();

        baritone.api.command.argument.IArgConsumer consumer = new DummyArgConsumer("");
        consumer = new DummyArgConsumer("test.txt");

        IDatatypeContext ctx = new DummyDatatypeContext(consumer);


        File result = RelativeFile.INSTANCE.apply(ctx, baseDir);
        assertEquals(validFile.getCanonicalFile(), result);
    }

    @Test
    public void testApplyAbsolutePath() throws IOException, CommandException {
        File baseDir = folder.newFolder().getCanonicalFile();

        baritone.api.command.argument.IArgConsumer consumer = new DummyArgConsumer("");
        consumer = new DummyArgConsumer(folder.getRoot().getAbsolutePath());

        IDatatypeContext ctx = new DummyDatatypeContext(consumer);


        try {
            RelativeFile.INSTANCE.apply(ctx, baseDir);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException exception) {
            assertEquals("absolute paths are not allowed", exception.getMessage());
        }
    }

    @Test
    public void testApplyPathTraversal() throws IOException, CommandException {
        File baseDir = folder.newFolder().getCanonicalFile();
        File subdir = new File(baseDir, "subdir");
        subdir.mkdir();

        baritone.api.command.argument.IArgConsumer consumer = new DummyArgConsumer("");
        consumer = new DummyArgConsumer("../outside.txt");

        IDatatypeContext ctx = new DummyDatatypeContext(consumer);


        try {
            RelativeFile.INSTANCE.apply(ctx, subdir);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException exception) {
            assertEquals("path traverses outside base directory", exception.getMessage());
        }
    }

    @Test
    public void testTabCompleteValidPath() throws CommandException, IOException {
        File baseDir = folder.newFolder().getCanonicalFile();
        File validFile = new File(baseDir, "testfile.txt");
        validFile.createNewFile();

        baritone.api.command.argument.IArgConsumer consumer = new DummyArgConsumer("");
        consumer = new DummyArgConsumer("test");

        Stream<String> completions = RelativeFile.tabComplete(consumer, baseDir);

        assertTrue(completions.anyMatch(s -> s.equals("testfile.txt")));
    }

    @Test
    public void testTabCompleteAbsolutePath() throws CommandException, IOException {
        File baseDir = folder.newFolder().getCanonicalFile();

        baritone.api.command.argument.IArgConsumer consumer = new DummyArgConsumer("");
        consumer = new DummyArgConsumer(baseDir.getAbsolutePath());

        Stream<String> completions = RelativeFile.tabComplete(consumer, baseDir);
        assertEquals(0, completions.count());
    }

    @Test
    public void testTabCompletePathTraversal() throws CommandException, IOException {
        File baseDir = folder.newFolder().getCanonicalFile();
        File subdir = new File(baseDir, "subdir");
        subdir.mkdir();

        baritone.api.command.argument.IArgConsumer consumer = new DummyArgConsumer("");
        consumer = new DummyArgConsumer("../");

        Stream<String> completions = RelativeFile.tabComplete(consumer, subdir);

        assertEquals(0, completions.count());
    }
}
