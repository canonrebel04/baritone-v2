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

package baritone.gradle.task;

import baritone.gradle.getitem.DatasetGenerator;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.IOException;
import java.util.zip.ZipFile;

/**
 * Plan A1: extracts the vanilla recipe / loot / tag dataset for Get Item from the Minecraft jar on the compile
 * classpath into {@code <outputDir>/baritone/getitem/dataset.json.gz}.
 */
public abstract class GetItemDatasetTask extends DefaultTask {

    @Classpath
    public abstract ConfigurableFileCollection getMinecraftClasspath();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    @TaskAction
    public void generate() throws IOException {
        File jar = null;
        for (File f : getMinecraftClasspath().getFiles()) {
            if (!f.isFile() || !f.getName().endsWith(".jar") || !f.getName().contains("minecraft")) continue;
            try (ZipFile zip = new ZipFile(f)) {
                if (zip.getEntry("data/minecraft/recipe/crafting_table.json") != null) {
                    jar = f;
                    break;
                }
            }
        }
        if (jar == null) {
            throw new GradleException("Get Item dataset: no Minecraft jar with vanilla data found on the classpath");
        }
        File out = new File(getOutputDir().get().getAsFile(), "baritone/getitem/dataset.json.gz");
        DatasetGenerator.write(DatasetGenerator.generate(jar), out);
        getLogger().lifecycle("Get Item dataset generated from " + jar.getName() + " -> " + out);
    }
}
