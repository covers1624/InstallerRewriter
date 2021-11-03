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
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.covers1624.quack.maven.MavenNotation;

public class MavenUrlProcessor implements InstallerProcessor {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final String MIRROR_BRAND = "https://files.minecraftforge.net/mirror-brand.list";
    private static final String INSTALL_PROFILE = "install_profile.json";

    @Override
    public InstallerFormat process(MavenNotation notation, JarContents content, InstallerFormat format) throws IOException {
        if (!content.contains(INSTALL_PROFILE)) {
            LOGGER.error("Missing {} in {}", INSTALL_PROFILE, notation);
            return format;
        }

        JsonObject install;
        try (Reader reader = new InputStreamReader(content.getInput(INSTALL_PROFILE))) {
            install = Utils.GSON.fromJson(reader, JsonObject.class);
        }

        boolean changed = false;
        switch (format) {
            case V1:
                changed = rewriteInstallProfileV1(notation, install);
                break;
            case V2:
                changed = rewriteInstallProfileV2(notation, install, content);
                break;
        }

        if (changed) {
            byte[] bytes = Utils.GSON.toJson(install).getBytes(StandardCharsets.UTF_8);
            LOGGER.debug("Updating {} for {}", INSTALL_PROFILE, notation);
            content.write(INSTALL_PROFILE, bytes);
        }

        return format;
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

    private boolean rewriteInstallProfileV2(MavenNotation notation, JsonObject install, JarContents jar) throws IOException {
        if (getAsInt(install, "spec") != 0)
            return false;
        boolean changed = false;

        // Rewrite the 'json'.
        String json = getAsString(install, "json");
        if (!jar.contains(json))
            throw new RuntimeException("Missing version json: " + json);

        JsonObject version;
        try (Reader reader = new InputStreamReader(jar.getInput(json))) {
            version = Utils.GSON.fromJson(reader, JsonObject.class);
        }

        if (rewriteVersionJson(notation, version)) {
            LOGGER.debug("Updating json {}.", json);
            jar.write(json, Utils.GSON.toJson(version).getBytes(StandardCharsets.UTF_8));
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

        String name = getAsString(lib, "name");
        if ("net.minecraftforge_temp.legacy:legacyfixer:1.0".equals(name)) {
            lib.addProperty("name", "net.minecraftforge:legacyfixer:1.0");
            changed = true;
        } else if ("org.ow2.asm:asm:4.1-all".equals(name)) {
            lib.addProperty("name", "org.ow2.asm:asm-all:4.1");
            changed = true;
        }


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
}
