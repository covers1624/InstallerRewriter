/*
 * Installer Rewriter
 * Copyright (c) 2021.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1
 * of the License.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */
package net.minecraftforge.ir;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.covers1624.quack.gson.MavenNotationAdapter;
import net.covers1624.quack.maven.MavenNotation;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.artifact.repository.metadata.Versioning;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;

/**
 * Created by covers1624 on 30/4/21.
 */
public class Utils {

    private static final MetadataXpp3Reader METADATA_XPP3_READER = new MetadataXpp3Reader();
    public static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(MavenNotation.class, new MavenNotationAdapter())
            .setPrettyPrinting()
            .create();

    public static Path JAVA_HOME = calcJavaHome();
    public static String EXE_SUFFIX = System.getProperty("os.name").contains("windows") ? ".exe" : "";

    public static List<String> parseVersions(Path file) throws IOException {
        if (Files.exists(file)) {
            try (InputStream is = Files.newInputStream(file)) {
                Metadata metadata = METADATA_XPP3_READER.read(is);
                Versioning versioning = metadata.getVersioning();
                if (versioning != null) {
                    return versioning.getVersions();
                }
            } catch (XmlPullParserException e) {
                throw new IOException("Failed to parse file.", e);
            }
        }
        return Collections.emptyList();
    }

    public static Path makeParents(Path file) throws IOException {
        if (Files.notExists(file.getParent())) {
            Files.createDirectories(file.getParent());
        }
        return file;
    }

    public static Path getJavaExecutable() {
        return JAVA_HOME.resolve("bin/java" + EXE_SUFFIX);
    }

    public static Path getJarSignExecutable() {
        return JAVA_HOME.resolve("bin/jarsigner" + EXE_SUFFIX);
    }

    public static Path calcJavaHome() {
        Path home = Paths.get(System.getProperty("java.home")).toAbsolutePath().normalize();
        if (home.getFileName().toString().equalsIgnoreCase("jre") && Files.exists(home.getParent().resolve("bin/java"))) {
            return home.getParent();
        }
        return home;
    }
}
