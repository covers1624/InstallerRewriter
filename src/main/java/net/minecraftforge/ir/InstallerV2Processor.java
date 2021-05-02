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

import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import net.covers1624.quack.io.CopyingFileVisitor;
import net.covers1624.quack.io.IOUtils;
import net.covers1624.quack.maven.MavenNotation;
import net.covers1624.quack.util.HashUtils;
import net.minecraftforge.ir.json.Install;
import net.minecraftforge.ir.json.Version;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

import static net.minecraftforge.ir.InstallerRewriter.*;

/**
 * Installer V2 only needs to be copied over.
 * <p>
 * Created by covers1624 on 1/5/21.
 */
@SuppressWarnings ("UnstableApiUsage")
public class InstallerV2Processor implements InstallerProcessor {

    private static final Logger LOGGER = LogManager.getLogger();
    private static final HashFunction SHA1 = Hashing.sha1();

    //Any files in /maven, any json or txt files in the root directory.
    private static final Pattern PATTERN = Pattern.compile("^/$|^/maven/|^/.*\\.txt|^/.*\\.json");

    @Override
    public void process(MavenNotation notation, Path repoPath, Path newInstaller, Path oldJarRoot) throws IOException {
        try (FileSystem fs = IOUtils.getJarFileSystem(newInstaller, true)) {
            Path newJarRoot = fs.getPath("/");
            // Copy everything that matches the regex above.
            Files.walkFileTree(oldJarRoot, new CopyingFileVisitor(oldJarRoot, newJarRoot, e -> PATTERN.matcher("/" + e.toString()).find()));

            Path profileJson = newJarRoot.resolve("install_profile.json");
            if (!Files.exists(profileJson)) {
                LOGGER.error("Missing install_profile.json {}", notation);
                return;
            }

            byte[] bytes = rewriteInstallProfile(notation, Files.newInputStream(profileJson), newJarRoot);
            if (bytes != null) {
                LOGGER.info("Updating install_profile.json for {}", notation);
                Files.delete(profileJson);
                Files.write(profileJson, bytes);
            }
        }
    }

    public byte[] rewriteInstallProfile(MavenNotation notation, InputStream is, Path jarRoot) throws IOException {
        Install install;
        try (Reader reader = new InputStreamReader(is)) {
            install = Utils.GSON.fromJson(reader, Install.class);
        }
        if (install.spec != 0) throw new IllegalStateException("Expected spec 0?");
        boolean changes = false;

        // Rewrite the 'json'.
        Path versionJson = jarRoot.resolve(Objects.requireNonNull(install.json));
        if (!Files.exists(versionJson)) {
            throw new RuntimeException("Missing version json: " + install.json);
        }
        byte[] bytes = rewriteVersionJson(notation, Files.newInputStream(versionJson), jarRoot);
        if (bytes != null) {
            LOGGER.info("Updating json {}.", install.json);
            Files.delete(versionJson);
            Files.write(versionJson, bytes);
        }

        // Ensure Mirror List exists and is updated.
        if (install.mirrorList == null) {
            LOGGER.info("Adding Mirror List to {}", notation);
            install.mirrorList = MIRROR_LIST;
            changes = true;
        } else {
            if (!install.mirrorList.equals(MIRROR_LIST)) {
                LOGGER.info("Updating Mirror List from {} to {}", install.mirrorList, MIRROR_LIST);
                install.mirrorList = MIRROR_LIST;

                changes = true;
            }
        }

        // Process all referenced libraries.
        for (Version.Library library : install.getLibraries()) {
            changes |= rewriteLibrary(library, notation, jarRoot);
        }
        if (!changes) return null;

        return Utils.GSON.toJson(install).getBytes(StandardCharsets.UTF_8);
    }

    public byte[] rewriteVersionJson(MavenNotation notation, InputStream is, Path jarRoot) throws IOException {
        Version version;
        try (Reader reader = new InputStreamReader(is)) {
            version = Utils.GSON.fromJson(reader, Version.class);
        }
        boolean changes = false;

        // Process all referenced libraries.
        for (Version.Library library : version.getLibraries()) {
            changes |= rewriteLibrary(library, notation, jarRoot);
        }

        if (!changes) return null;

        return Utils.GSON.toJson(version).getBytes(StandardCharsets.UTF_8);
    }

    // Rewrite the library entry.
    public static boolean rewriteLibrary(Version.Library lib, MavenNotation notation, Path jarRoot) throws IOException {
        boolean changes = false;
        MavenNotation name = Objects.requireNonNull(lib.name);
        Version.Downloads downloads = Objects.requireNonNull(lib.downloads);
        Version.LibraryDownload artifact = Objects.requireNonNull(downloads.artifact);

        String path = Objects.requireNonNull(artifact.path);
        String url = Objects.requireNonNull(artifact.url);
        String expectedSha1 = Objects.requireNonNull(artifact.sha1);
        int expectedLen = Objects.requireNonNull(artifact.size);

        //Rewrite the URL to the new maven.
        String origUrl = url;
        if (url.startsWith("http://")) {
            url = "https://" + url.substring(7);
        }
        if (url.startsWith(OLD_FORGE_MAVEN)) {
            url = FORGE_MAVEN + url.substring(OLD_FORGE_MAVEN.length());
        }
        if (!origUrl.equals(url)) {
            LOGGER.info("Rewrote URL from {} to {} in {}", origUrl, url, notation);
            artifact.url = url;
            changes = true;
        }

        // Resolve the artifacts path.
        Path artifactPath;
        if (url.isEmpty()) {
            artifactPath = jarRoot.resolve("maven/" + path);
            if (!Files.exists(artifactPath)) {
                throw new RuntimeException("Provided artifact does not exist in /maven: " + artifactPath);
            }
        } else {
            // Download the artifact if necessary
            artifactPath = InstallerRewriter.CACHE_DIR.resolve(path);
            InstallerRewriter.downloadFile(new URL(url), artifactPath);
        }

        // Compute sha1 and length of the artifact.
        HashCode computedHash = HashUtils.hash(SHA1, artifactPath);
        int computedLength = Math.toIntExact(Files.size(artifactPath));

        // Validate the artifact hash matches.
        if (!HashUtils.equals(computedHash, expectedSha1)) {
            LOGGER.warn("Corrected incorrect hash for {}, From: {}, To: {}", name, expectedSha1, computedHash);
            artifact.sha1 = computedHash.toString();
            changes = true;
        }

        // Validate the artifact length matches.
        if (expectedLen != computedLength) {
            LOGGER.warn("Corrected incorrect file length for {}, From: {}, To: {}", name, expectedLen, computedLength);
            artifact.size = computedLength; //If a 4GB library is added, there is other problems.
            changes = true;
        }
        return changes;
    }
}
