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

import static net.covers1624.quack.util.SneakyUtils.sneak;
import static net.minecraftforge.ir.InstallerRewriter.FORGE_MAVEN;
import static net.minecraftforge.ir.InstallerRewriter.MIRROR_LIST;
import static net.minecraftforge.ir.InstallerRewriter.OLD_FORGE_MAVEN;
import static net.minecraftforge.ir.Utils.getAsInt;
import static net.minecraftforge.ir.Utils.getAsString;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.Collectors;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.covers1624.quack.io.CopyingFileVisitor;
import net.covers1624.quack.io.IOUtils;
import net.covers1624.quack.maven.MavenNotation;
import net.covers1624.quack.util.HashUtils;

public class MavenUrlProcessor implements InstallerProcessor {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final String MIRROR_BRAND = "https://files.minecraftforge.net/mirror-brand.list";

    @Override
    public boolean process(ProcessorContext ctx) throws IOException {
        Pair<Path, Path> pathPair = ctx.getFile(ctx.installer);
        try (FileSystem oldFs = IOUtils.getJarFileSystem(pathPair.getLeft(), true);
             FileSystem newFs = IOUtils.getJarFileSystem(pathPair.getRight(), true)
        ) {
            Path oldJarRoot = oldFs.getPath("/");
            Path newJarRoot = newFs.getPath("/");

            Files.walkFileTree(oldJarRoot, new CopyingFileVisitor(oldJarRoot, newJarRoot, e -> true));

            boolean changed = rewriteInstallProfile(ctx.notation, oldJarRoot, newJarRoot);
            changed |= validateSignatures(newJarRoot);
            Files.setLastModifiedTime(pathPair.getRight(), Files.getLastModifiedTime(pathPair.getLeft()));
            return changed;
        }
    }

    // We don't use the object representation of these jsons as we don't want to accidentally nuke data from them.
    private boolean rewriteInstallProfile(MavenNotation notation, Path oldJarRoot, Path newJarRoot) throws IOException {
        Path profileJson = newJarRoot.resolve("install_profile.json");
        if (!Files.exists(profileJson)) {
            LOGGER.error("Missing install_profile.json {}", notation);
            return false;
        }

        FileTime modified = Files.getLastModifiedTime(profileJson);

        JsonObject install;
        try (Reader reader = new InputStreamReader(Files.newInputStream(profileJson))) {
            install = Utils.GSON.fromJson(reader, JsonObject.class);
        }

        boolean changed = false;
        if (!install.has("spec"))
            changed = rewriteInstallProfileV1(notation, install);
        else
            changed = rewriteInstallProfileV2(notation, install, newJarRoot);

        if (!changed)
            return false;

        byte[] bytes = Utils.GSON.toJson(install).getBytes(StandardCharsets.UTF_8);
        LOGGER.debug("Updating install_profile.json for {}", notation);
        Files.delete(profileJson);
        Files.write(profileJson, bytes);
        Files.setLastModifiedTime(profileJson, modified);
        return true;
    }

    private boolean rewriteInstallProfileV1(MavenNotation notation, JsonObject profile) {
        if (!profile.has("install")) throw new IllegalStateException("Invalid V1 install_profile.json, missing install entry");
        if (!profile.has("versionInfo")) throw new IllegalStateException("Invalid V1 install_profile.json, missing versionInfo entry");
        boolean changed = false;
        JsonObject version = profile.getAsJsonObject("versionInfo");
        changed |= rewriteVersionJson(notation, version);

        JsonObject install = profile.getAsJsonObject("install");
        // Ensure Mirror List exists and is updated.
        String mirrorList = getAsString(install, "mirrorList", null);
        if (mirrorList == null) {
            LOGGER.debug("Adding Mirror List to {}", notation);
            install.addProperty("mirrorList", MIRROR_BRAND);
            changed = true;
        } else if (!mirrorList.equals(MIRROR_BRAND)) {
            LOGGER.debug("Updating Mirror List from {} to {}", mirrorList, MIRROR_BRAND);
            install.addProperty("mirrorList", MIRROR_BRAND);
            changed = true;
        }

        return changed;
    }

    private boolean rewriteInstallProfileV2(MavenNotation notation, JsonObject install, Path jarRoot) throws IOException {
        if (getAsInt(install, "spec") != 0)
            return false;
        boolean changed = false;

        // Rewrite the 'json'.
        String json = getAsString(install, "json");
        Path versionJson = jarRoot.resolve(json);
        if (!Files.exists(versionJson)) {
            throw new RuntimeException("Missing version json: " + json);
        }

        JsonObject version;
        try (Reader reader = new InputStreamReader(Files.newInputStream(versionJson))) {
            version = Utils.GSON.fromJson(reader, JsonObject.class);
        }

        if (rewriteVersionJson(notation, version)) {
            LOGGER.debug("Updating json {}.", json);
            FileTime modified = Files.getLastModifiedTime(versionJson);
            Files.delete(versionJson);
            Files.write(versionJson, Utils.GSON.toJson(version).getBytes(StandardCharsets.UTF_8));
            Files.setLastModifiedTime(versionJson, modified);
            changed = true;
        }

        // Ensure Mirror List exists and is updated.
        String mirrorList = getAsString(install, "mirrorList", null);
        if (mirrorList == null) {
            LOGGER.debug("Adding Mirror List to {}", notation);
            install.addProperty("mirrorList", MIRROR_LIST);
            changed = true;
        } else if (!mirrorList.equals(MIRROR_LIST)) {
            LOGGER.debug("Updating Mirror List from {} to {}", mirrorList, MIRROR_LIST);
            install.addProperty("mirrorList", MIRROR_LIST);
            changed = true;
        }

        // Process all referenced libraries.
        for (JsonElement library : install.getAsJsonArray("libraries")) {
            if (!library.isJsonObject()) throw new RuntimeException("Expected JsonObject.");
            changed |= rewriteLibrary(notation, library.getAsJsonObject());
        }
        return changed;
    }

    public boolean rewriteVersionJson(MavenNotation notation, JsonObject version) {
        boolean changed = false;

        for (JsonElement library : version.getAsJsonArray("libraries")) {
            if (!library.isJsonObject()) throw new RuntimeException("Expected JsonObject.");
            changed |= rewriteLibrary(notation, library.getAsJsonObject());
        }

        return changed;
    }

    public static boolean rewriteLibrary(MavenNotation notation, JsonObject lib) {
        boolean changed = false;
        changed |= rewriteUrl(notation, lib);
        if (lib.has("downloads")) {
            JsonObject downloads = lib.getAsJsonObject("downloads");
            if (downloads.has("artifact")) {
                changed |= rewriteUrl(notation, downloads.getAsJsonObject("artifact"));
            }
            if (downloads.has("classifiers")) {
                JsonObject classifiers = lib.getAsJsonObject("classifiers");
                for (String key : classifiers.keySet()) {
                    changed |= rewriteUrl(notation, classifiers.getAsJsonObject(key));
                }
            }
        }
        return changed;
    }

    private static boolean rewriteUrl(MavenNotation notation, JsonObject json) {
        if (!json.has("url"))
            return false;

        String url = getAsString(json, "url");
        if (url == null || url.isEmpty())
            return false;

        String origUrl = url;
        if (url.startsWith("http://")) {
            url = "https://" + url.substring(7);
        }

        if (url.startsWith(OLD_FORGE_MAVEN)) {
            url = FORGE_MAVEN + url.substring(OLD_FORGE_MAVEN.length());
        }

        if (!origUrl.equals(url)) {
            LOGGER.debug("Rewrote URL from {} to {} in {}", origUrl, url, notation);
            json.addProperty("url", url);
            return true;
        }

        return false;
    }

    private static boolean validateSignatures(Path root) throws IOException {
        boolean invalid = false;
        Path manifest = root.resolve("META-INF/MANIFEST.MF");
        if (!Files.exists(manifest))
            return false;

        Manifest mf = null;
        try (InputStream is = Files.newInputStream(manifest)) {
            mf = new Manifest(is);
        }

        for (Entry<String, Attributes> entry : mf.getEntries().entrySet()) {
            String name = entry.getKey().toString();
            Path target = root.resolve(name);

            for (String key : entry.getValue().keySet().stream().map(Object::toString).collect(Collectors.toList())) {
                if (key.endsWith("-Digest")) {
                    if (!Files.exists(target) || !Files.isRegularFile(target)) // Hashes can exist in the manifest even if the files dont exist.
                        continue;

                    HashFunction func = null;
                    switch (key.substring(0, key.length() - "-Digest".length())) {
                        case "SHA-256": func = Hashing.sha256(); break; // Only one i've seen, but might as well support he others.
                        case "SHA-1":   func = Hashing.sha1();   break;
                        case "SHA-512": func = Hashing.sha512(); break;
                        case "MD5":     func = Hashing.md5();    break;
                        default: throw new IOException("Unknown manifest signature format: " + key);
                    }
                    String actual = HashUtils.hash(func, target).toString();
                    String expected = HashCode.fromBytes(Base64.getDecoder().decode(entry.getValue().getValue(key))).toString();
                    if (!expected.equals(actual)) {
                        LOGGER.info("Installer manifest hash mismatch, stripping signatures");
                        invalid = true;
                        break;
                    }
                }
            }

            if (invalid)
                break;
        }

        if (invalid) {
            // cleanup manifest
            Iterator<Entry<String, Attributes>> itr = mf.getEntries().entrySet().iterator();
            while (itr.hasNext()) {
                Entry<String, Attributes> entry = itr.next();
                Attributes attrs = entry.getValue();
                attrs.keySet().removeIf(e -> e.toString().endsWith("-Digest"));
                if (attrs.isEmpty())
                    itr.remove();
            }
            FileTime modified = Files.getLastModifiedTime(manifest);
            try (OutputStream os = Files.newOutputStream(manifest, StandardOpenOption.TRUNCATE_EXISTING)) {
                mf.write(os);
                os.flush();
            }
            Files.setLastModifiedTime(manifest, modified);

            Files.list(root.resolve("META-INF"))
            .filter(Files::isRegularFile)
            .filter(e -> {
                // Find all signing metadata files.
                String s = e.getFileName().toString();
                return s.endsWith(".SF") || s.endsWith(".DSA") || s.endsWith(".RSA") || s.endsWith(".EC");
            })
            .forEach(sneak(Files::delete));
        }
        return invalid;
    }
}
