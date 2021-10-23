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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.covers1624.quack.io.CopyingFileVisitor;
import net.covers1624.quack.io.IOUtils;
import net.covers1624.quack.maven.MavenNotation;
import net.covers1624.quack.util.HashUtils;
import org.apache.commons.lang3.tuple.Pair;
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
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;
import static net.minecraftforge.ir.InstallerRewriter.*;
import static net.minecraftforge.ir.Utils.getAsInt;
import static net.minecraftforge.ir.Utils.getAsString;

/**
 * Installer V2 only needs to be copied over.
 * <p>
 * Created by covers1624 on 1/5/21.
 */
@SuppressWarnings ("UnstableApiUsage")
public class InstallerV2Processor implements InstallerProcessor {

    private static final Logger LOGGER = LogManager.getLogger();
    private static final HashFunction SHA1 = Hashing.sha1();

    // Exclude any classes and the META-INF folder from being copied over.
    private static final Pattern PATTERN = Pattern.compile("^/META-INF/.*$|/.*.class$");

    @Override
    public boolean process(ProcessorContext ctx) throws IOException {
        Pair<Path, Path> pathPair = ctx.getFile(ctx.installer);
        try (FileSystem oldFs = IOUtils.getJarFileSystem(pathPair.getLeft(), true);
             FileSystem newFs = IOUtils.getJarFileSystem(pathPair.getRight(), true)
        ) {
            Path oldJarRoot = oldFs.getPath("/");
            Path newJarRoot = newFs.getPath("/");
            // Copy everything that is not matched by the regex above.
            Files.walkFileTree(oldJarRoot, new CopyingFileVisitor(oldJarRoot, newJarRoot, e -> !PATTERN.matcher("/" + e.toString()).find()));

            Path profileJson = newJarRoot.resolve("install_profile.json");
            if (!Files.exists(profileJson)) {
                LOGGER.error("Missing install_profile.json {}", ctx.notation);
                return false;
            }

            byte[] bytes = rewriteInstallProfile(ctx.notation, Files.newInputStream(profileJson), newJarRoot);
            if (bytes != null) {
                LOGGER.debug("Updating install_profile.json for {}", ctx.notation);
                Files.delete(profileJson);
                Files.write(profileJson, bytes);
                return true;
            }
            return false;
        }
    }

    // We don't use the object representation of these jsons as we don't want to accidentally nuke data from them.
    public byte[] rewriteInstallProfile(MavenNotation notation, InputStream is, Path jarRoot) throws IOException {
        JsonObject install;
        try (Reader reader = new InputStreamReader(is)) {
            install = Utils.GSON.fromJson(reader, JsonObject.class);
        }
        if (getAsInt(install, "spec") != 0) throw new IllegalStateException("Expected spec 0?");
        boolean changes = false;

        // Rewrite the 'json'.
        String json = getAsString(install, "json");
        Path versionJson = jarRoot.resolve(json);
        if (!Files.exists(versionJson)) {
            throw new RuntimeException("Missing version json: " + json);
        }
        byte[] bytes = rewriteVersionJson(notation, Files.newInputStream(versionJson), jarRoot);
        if (bytes != null) {
            LOGGER.debug("Updating json {}.", json);
            Files.delete(versionJson);
            Files.write(versionJson, bytes);
        }

        // Ensure Mirror List exists and is updated.
        String mirrorList = getAsString(install, "mirrorList", null);
        if (mirrorList == null) {
            LOGGER.debug("Adding Mirror List to {}", notation);
            install.addProperty("mirrorList", MIRROR_LIST);
            changes = true;
        } else {
            if (!mirrorList.equals(MIRROR_LIST)) {
                LOGGER.debug("Updating Mirror List from {} to {}", mirrorList, MIRROR_LIST);
                install.addProperty("mirrorList", MIRROR_LIST);
                changes = true;
            }
        }

        // Process all referenced libraries.
        for (JsonElement library : install.getAsJsonArray("libraries")) {
            if (!library.isJsonObject()) throw new RuntimeException("Expected JsonObject.");
            changes |= rewriteLibrary(library.getAsJsonObject(), notation, jarRoot);
        }
        if (!changes) return null;

        return Utils.GSON.toJson(install).getBytes(StandardCharsets.UTF_8);
    }

    public byte[] rewriteVersionJson(MavenNotation notation, InputStream is, Path jarRoot) throws IOException {
        JsonObject version;
        try (Reader reader = new InputStreamReader(is)) {
            version = Utils.GSON.fromJson(reader, JsonObject.class);
        }
        boolean changes = false;

        // Process all referenced libraries.
        for (JsonElement library : version.getAsJsonArray("libraries")) {
            if (!library.isJsonObject()) throw new RuntimeException("Expected JsonObject.");
            changes |= rewriteLibrary(library.getAsJsonObject(), notation, jarRoot);
        }

        if (!changes) return null;

        return Utils.GSON.toJson(version).getBytes(StandardCharsets.UTF_8);
    }

    // Rewrite the library entry.
    public static boolean rewriteLibrary(JsonObject lib, MavenNotation notation, Path jarRoot) throws IOException {
        boolean changes = false;
        MavenNotation name = MavenNotation.parse(getAsString(lib, "name"));
        JsonObject downloads = requireNonNull(lib.getAsJsonObject("downloads"));
        JsonObject artifact = requireNonNull(downloads.getAsJsonObject("artifact"));

        String path = getAsString(artifact, "path");
        String url = getAsString(artifact, "url");
        String expectedSha1 = getAsString(artifact, "sha1");
        int expectedLen = getAsInt(artifact, "size");

        //Rewrite the URL to the new maven.
        String origUrl = url;
        if (url.startsWith("http://")) {
            url = "https://" + url.substring(7);
        }
        if (url.startsWith(OLD_FORGE_MAVEN)) {
            url = FORGE_MAVEN + url.substring(OLD_FORGE_MAVEN.length());
        }
        if (!origUrl.equals(url)) {
            LOGGER.debug("Rewrote URL from {} to {} in {}", origUrl, url, notation);
            artifact.addProperty("url", url);
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
            artifact.addProperty("sha1", computedHash.toString());
            changes = true;
        }

        // Validate the artifact length matches.
        if (expectedLen != computedLength) {
            LOGGER.warn("Corrected incorrect file length for {}, From: {}, To: {}", name, expectedLen, computedLength);
            artifact.addProperty("size", computedLength);
            changes = true;
        }
        return changes;
    }
}
