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

import static net.minecraftforge.ir.InstallerRewriter.FORGE_MAVEN;
import static net.minecraftforge.ir.InstallerRewriter.MIRROR_LIST;
import static net.minecraftforge.ir.InstallerRewriter.OLD_FORGE_MAVEN;
import static net.minecraftforge.ir.Utils.getAsInt;
import static net.minecraftforge.ir.Utils.getAsString;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.covers1624.quack.io.CopyingFileVisitor;
import net.covers1624.quack.io.IOUtils;
import net.covers1624.quack.maven.MavenNotation;

public class MavenUrlProcessor implements InstallerProcessor {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final String MIRROR_BRAND = "https://files.minecraftforge.net/mirror-brand.list";

    @Override
    public void process(ProcessorContext ctx) throws IOException {
        Pair<Path, Path> pathPair = ctx.getFile(ctx.installer);
        try (FileSystem oldFs = IOUtils.getJarFileSystem(pathPair.getLeft(), true);
             FileSystem newFs = IOUtils.getJarFileSystem(pathPair.getRight(), true)
        ) {
            Path oldJarRoot = oldFs.getPath("/");
            Path newJarRoot = newFs.getPath("/");

            Files.walkFileTree(oldJarRoot, new CopyingFileVisitor(oldJarRoot, newJarRoot, e -> true));

            Path profileJson = newJarRoot.resolve("install_profile.json");
            if (!Files.exists(profileJson)) {
                LOGGER.error("Missing install_profile.json {}", ctx.notation);
                return;
            }

            FileTime modified = Files.getLastModifiedTime(profileJson);
            byte[] bytes = rewriteInstallProfile(ctx.notation, Files.newInputStream(profileJson), newJarRoot);
            if (bytes != null) {
                LOGGER.debug("Updating install_profile.json for {}", ctx.notation);
                Files.delete(profileJson);
                Files.write(profileJson, bytes);
                Files.setLastModifiedTime(profileJson, modified);
            }
        }
    }

    // We don't use the object representation of these jsons as we don't want to accidentally nuke data from them.
    public byte[] rewriteInstallProfile(MavenNotation notation, InputStream is, Path jarRoot) throws IOException {
        JsonObject install;
        try (Reader reader = new InputStreamReader(is)) {
            install = Utils.GSON.fromJson(reader, JsonObject.class);
        }
        boolean changed = false;
        if (!install.has("spec"))
            changed = rewriteInstallProfileV1(notation, install);
        else
            changed = rewriteInstallProfileV2(notation, install, jarRoot);

        if (!changed)
            return null;

        return Utils.GSON.toJson(install).getBytes(StandardCharsets.UTF_8);
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
            if (!mirrorList.replace("http:", "https:").equals(MIRROR_BRAND))
                System.currentTimeMillis();
            LOGGER.debug("Updating Mirror List from {} to {}", mirrorList, MIRROR_BRAND);
            install.addProperty("mirrorList", MIRROR_BRAND);
            changed = true;
        }

        return changed;
    }

    private boolean rewriteInstallProfileV2(MavenNotation notation, JsonObject install, Path jarRoot) throws IOException {
        if (getAsInt(install, "spec") != 0) throw new IllegalStateException("Expected spec 0?");
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
            Files.write(versionJson, Utils.GSON.toJson(install).getBytes(StandardCharsets.UTF_8));
            Files.setLastModifiedTime(versionJson, modified);
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
                changed |= rewriteUrl(notation, downloads.getAsJsonObject("artifect"));
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

}
