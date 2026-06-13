/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.api.command.datatypes;

import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.utils.Helper;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

public enum RelativeFile implements IDatatypePost<File, File> {
    INSTANCE;

    @Override
    public File apply(IDatatypeContext ctx, File original) throws CommandException {
        if (original == null) {
            original = new File("./");
        }

        Path path;
        try {
            path = FileSystems.getDefault().getPath(ctx.getConsumer().getString());
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("invalid path");
        }

        if (path.isAbsolute()) {
            throw new IllegalArgumentException("absolute paths are not allowed");
        }

        File base = getCanonicalFileUnchecked(original);
        File resolved = getCanonicalFileUnchecked(base.toPath().resolve(path).toFile());

        if (!resolved.toPath().startsWith(base.toPath())) {
            throw new IllegalArgumentException("path traverses outside base directory");
        }

        return resolved;
    }

    @Override
    public Stream<String> tabComplete(IDatatypeContext ctx) {
        return Stream.empty();
    }

    /**
     * Seriously
     *
     * @param file File
     * @return Canonical file of file
     * @author LoganDark
     */
    private static File getCanonicalFileUnchecked(File file) {
        try {
            return file.getCanonicalFile();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Stream<String> tabComplete(IArgConsumer consumer, File base0) throws CommandException {
        // I will not make the caller deal with this, seriously
        // Tab complete code is beautiful and I'm not going to bloat it with dumb ass checked exception bullshit -LoganDark

        // lol owned -Brady

        File base = getCanonicalFileUnchecked(base0);
        String currentPathStringThing = consumer.getString();
        Path currentPath = FileSystems.getDefault().getPath(currentPathStringThing);

        if (currentPath.isAbsolute()) {
            return Stream.empty();
        }

        Path basePath = base.toPath();
        boolean useParent = !currentPathStringThing.isEmpty() && !currentPathStringThing.endsWith(File.separator);
        File currentFile = new File(base, currentPathStringThing);
        File searchDir = getCanonicalFileUnchecked(useParent ? currentFile.getParentFile() : currentFile);

        if (!searchDir.toPath().startsWith(basePath)) {
            return Stream.empty();
        }

        File[] files = searchDir.listFiles();
        if (files == null) {
            return Stream.empty();
        }

        return Stream.of(files)
                .map(f -> basePath.relativize(f.toPath()).toString() + (f.isDirectory() ? File.separator : ""))
                .filter(s -> s.toLowerCase(Locale.US).startsWith(currentPathStringThing.toLowerCase(Locale.US)))
                .filter(s -> !s.contains(" "));
    }

    @Deprecated
    public static File gameDir() {
        return gameDir(Helper.mc);
    }

    public static File gameDir(Minecraft mc) {
        File gameDir = mc.gameDirectory.getAbsoluteFile();
        if (gameDir.getName().equals(".")) {
            return gameDir.getParentFile();
        }
        return gameDir;
    }
}
