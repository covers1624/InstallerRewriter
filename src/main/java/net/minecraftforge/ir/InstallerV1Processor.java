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

import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import net.covers1624.quack.io.IOUtils;
import net.covers1624.quack.maven.MavenNotation;
import net.covers1624.quack.util.HashUtils;
import net.covers1624.quack.util.SneakyUtils;
import net.minecraftforge.ir.json.Install;
import net.minecraftforge.ir.json.Manifest;
import net.minecraftforge.ir.json.V1InstallProfile;
import net.minecraftforge.ir.json.Version;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static net.minecraftforge.ir.InstallerRewriter.*;
import static net.minecraftforge.ir.Utils.makeParents;

/**
 * Created by covers1624 on 1/5/21.
 */
@SuppressWarnings ("UnstableApiUsage")
public class InstallerV1Processor implements InstallerProcessor {

    private static final Logger LOGGER = LogManager.getLogger();

    private static final HashFunction SHA1 = Hashing.sha1();

    private static final URL VERSION_MANIFEST = SneakyUtils.sneaky(() -> new URL("https://launchermeta.mojang.com/mc/game/version_manifest.json"));

    private static final List<String> comment = Arrays.asList(
            "Please do not automate the download and installation of Forge.",
            "Our efforts are supported by ads from the download page.",
            "If you MUST automate this, please consider supporting the project through https://www.patreon.com/LexManos/"
    );

    public static final Map<MavenNotation, MavenNotation> REPLACEMENTS = ImmutableMap.<MavenNotation, MavenNotation>builder()
            .put(MavenNotation.parse("org.ow2.asm:asm:4.1-all"), MavenNotation.parse("org.ow2.asm:asm-all:4.1"))
            .build();

    @Override
    public void process(MavenNotation notation, Path repoPath, Path newInstaller, Path oldJarRoot) throws IOException {
        MavenNotation baseNotation = notation.withClassifier(null).withExtension("jar");
        try (FileSystem fs = IOUtils.getJarFileSystem(newInstaller, true)) {
            Path newJarRoot = fs.getPath("/");

            Path oldProfileFile = oldJarRoot.resolve("install_profile.json");
            if (Files.notExists(oldProfileFile)) {
                LOGGER.error("Old installer does not have 'install_profile.json'");
                return;
            }

            V1InstallProfile v1Profile;
            try (BufferedReader reader = Files.newBufferedReader(oldProfileFile)) {
                v1Profile = Utils.GSON.fromJson(reader, V1InstallProfile.class);
            }
            V1InstallProfile.Install v1Install = Objects.requireNonNull(v1Profile.install);

            String filePathStr = Objects.requireNonNull(v1Install.filePath);
            Path filePath = oldJarRoot.resolve(filePathStr);
            Path universalJar = newJarRoot.resolve("maven").resolve(baseNotation.toPath());
            if (!Files.exists(filePath)) {
                LOGGER.error("'filePath' does not exist in old jar. {}", filePathStr);
                return;
            }

            //Load libraries referenced in the 'Class-Path' manifest attribute.
            Set<MavenNotation> classpathLibraries = new HashSet<>();
            //We load things with a ZipInputStream, as jar-in-jar zipfs is not supported.
            try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(filePath))) {
                ZipEntry entry;
                java.util.jar.Manifest manifest = null;
                while ((entry = zin.getNextEntry()) != null) {
                    if (entry.getName().endsWith("META-INF/MANIFEST.MF")) {
                        manifest = new java.util.jar.Manifest(zin);
                        break;
                    }
                }
                List<String> classPath = new ArrayList<>();
                if (manifest != null) {
                    String cp = manifest.getMainAttributes().getValue("Class-Path");
                    if (cp != null) {
                        Arrays.stream(cp.split(" "))
                                .filter(e -> e.startsWith("libraries/"))
                                .map(e -> e.substring(10))
                                .forEach(classPath::add);
                    }
                }
                for (String s : classPath) {
                    String[] splits = s.split("/");
                    int len = splits.length;
                    if (len < 4) continue; //Invalid

                    String file = splits[len - 1];  //Grab file, version, and module segments.
                    String version = splits[len - 2];
                    String module = splits[len - 3];
                    StringBuilder gBuilder = new StringBuilder();
                    for (int i = 0; i < len - 3; i++) { // Assemble remaining into group.
                        if (gBuilder.length() > 0) {
                            gBuilder.append(".");
                        }
                        gBuilder.append(splits[i]);
                    }
                    String fPart = file.replaceFirst(module + "-", ""); // Strip module name
                    fPart = fPart.replaceFirst(version, ""); // Strip version
                    int lastDot = fPart.lastIndexOf("."); // Assumes we only have a single dot in the extension.
                    String classifer = "";
                    if (fPart.startsWith("-")) { // We have a classifier.
                        classifer = fPart.substring(1, lastDot);
                    }
                    String extension = fPart.substring(lastDot + 1);
                    classpathLibraries.add(new MavenNotation(gBuilder.toString(), module, version, classifer, extension));
                }
            }

            //TODO, grab license files from src jar.
            Files.copy(filePath, makeParents(universalJar));
            Install install = generateInstallProfile(notation, universalJar);
            Files.write(newJarRoot.resolve("install_profile.json"), Utils.GSON.toJson(install).getBytes(StandardCharsets.UTF_8));

            Version version = generateVersionJson(install, v1Profile, classpathLibraries);
            Files.write(newJarRoot.resolve("version.json"), Utils.GSON.toJson(version).getBytes(StandardCharsets.UTF_8));
        }
    }

    public static Install generateInstallProfile(MavenNotation notation, Path newFilePath) throws IOException {
        Install install = new Install();
        install._comment_ = comment;
        install.spec = 0;
        install.profile = "forge";

        MavenNotation baseNotation = notation.withClassifier(null).withExtension("jar");

        String[] vSplit = notation.version.split("-", 2);
        install.version = vSplit[0] + "-" + notation.module + "-" + vSplit[1];
        install.icon = InstallerRewriter.ICON;
        install.json = "/version.json";
        install.path = baseNotation;
        install.logo = "/big_logo.png";
        install.minecraft = vSplit[0].replace("_", "-");//Replace _ with  - for prerelease versions
        install.welcome = "Welcome to the simple Forge installer.";
        install.mirrorList = MIRROR_LIST;

        List<Version.Library> libraries = install.getLibraries();
        Version.Library forgeLib = new Version.Library();
        Version.Downloads downloads = new Version.Downloads();
        Version.LibraryDownload artifact = new Version.LibraryDownload();

        artifact.path = baseNotation.toPath();
        artifact.url = "";
        artifact.sha1 = HashUtils.hash(SHA1, newFilePath).toString();
        artifact.size = Math.toIntExact(Files.size(newFilePath));

        downloads.artifact = artifact;
        forgeLib.name = baseNotation;
        forgeLib.downloads = downloads;
        libraries.add(forgeLib);

        return install;
    }

    public static Version generateVersionJson(Install newProfile, V1InstallProfile v1Profile, Set<MavenNotation> classpathLibraries) throws IOException {
        V1InstallProfile.VersionInfo v1VersionInfo = v1Profile.versionInfo;
        Version version = new Version();
        version._comment_ = comment;
        version.id = newProfile.version;
        version.time = v1VersionInfo.time;
        version.releaseTime = v1VersionInfo.releaseTime;
        version.type = v1VersionInfo.type;
        version.mainClass = v1VersionInfo.mainClass;
        version.minecraftArguments = v1VersionInfo.minecraftArguments;
        v1VersionInfo.libraries.forEach(v -> {
            MavenNotation replacement = REPLACEMENTS.get(v.name);
            if (replacement != null) {
                LOGGER.info("Replacing {} with {}", v.name, replacement);
                v.name = replacement;
            }
        });
        if (v1VersionInfo.inheritsFrom == null) {
            Path versionManifest = CACHE_DIR.resolve("version_manifest.json");
            downloadFile(VERSION_MANIFEST, versionManifest, true);

            Manifest manifest;
            try (BufferedReader reader = Files.newBufferedReader(versionManifest)) {
                manifest = Utils.GSON.fromJson(reader, Manifest.class);
            }

            String mcVersion = newProfile.minecraft;
            Path versionJson = CACHE_DIR.resolve(mcVersion + ".json");
            downloadFile(new URL(manifest.getUrl(mcVersion)), versionJson, true);

            Version mcVersionJson;
            try (BufferedReader reader = Files.newBufferedReader(versionJson)) {
                mcVersionJson = Utils.GSON.fromJson(reader, Version.class);
            }
            Set<MavenNotation> mcLibraries = mcVersionJson.getLibraries().stream()
                    .map(e -> e.name)
                    .collect(Collectors.toSet());
            v1VersionInfo.libraries.removeIf(e -> {
                //If our parent has the library, _and_ the v1 installer marked both client and server req as non existent, remove it.
                // This special cases joptsimple, kinda, as its provided by the parent, but doesn't exist in the server jar for all versions.
                if (mcLibraries.contains(e.name) && e.clientreq == null && e.serverreq == null) {
                    LOGGER.info("Removing {} from forge version json.", e.name);
                    return true;
                }
                //Forge has never shipped any versions with this on our own libraries.
                if (e.rules != null) throw new RuntimeException("Expected non-minecraft library to have no rules! " + e.name);
                if (e.natives != null) throw new RuntimeException("Expected non-minecraft library to have no natives! " + e.name);
                if (e.extract != null) throw new RuntimeException("Expected non-minecraft library to have no extract! " + e.name);
                return false;
            });
            version.inheritsFrom = mcVersion;
        } else {
            version.inheritsFrom = v1VersionInfo.inheritsFrom;
        }

        List<Version.Library> libraries = version.getLibraries();
        for (V1InstallProfile.Library library : v1VersionInfo.libraries) {
            LOGGER.info("Processing library: {}", library.name);
            libraries.add(rewriteLibrary(library, newProfile, classpathLibraries));
        }

        //Validate all libraries declared in the Class-Path manifest entry exist.
        for (MavenNotation l : classpathLibraries) {
            boolean found = false;
            for (Version.Library library : version.getLibraries()) {
                MavenNotation name = library.name;
                if (name.equals(l)) {
                    LOGGER.info("Classpath library {} validated.", l);
                    found = true;
                    break;
                }
            }
            if (found) continue;
            LOGGER.error("Classpath library {}, not found!", l);
        }

        return version;
    }

    public static Version.Library rewriteLibrary(V1InstallProfile.Library oldLibrary, Install newProfile, Set<MavenNotation> classpathLibraries) throws IOException {

        MavenNotation name = oldLibrary.name;
        //This is the universal jar, without classifier.
        if (name.module.equals("forge") || name.module.equals("minecraftforge")) {
            return newProfile.getLibraries().get(0);
        }

        //If we dont find this library in the Class-Path entry
        if (!classpathLibraries.contains(name)) {
            for (MavenNotation l : classpathLibraries) {
                //Attempt to find a compatible group, module, and classifier
                if (!name.group.equals(l.group) || !name.module.equals(l.module) || !Objects.equals(name.classifier, l.classifier)) continue;
                LOGGER.warn("Correcting incorrect library, Classpath assumes '{}', got '{}'", l, name);
                //Update the entry to match what the classpath expects.
                name = l;
            }
        }

        Version.Library library = new Version.Library();
        Version.Downloads downloads = new Version.Downloads();
        Version.LibraryDownload libraryDownload = new Version.LibraryDownload();
        libraryDownload.path = name.toPath();

        String repo = determineRepo(oldLibrary);
        LOGGER.info("Using {} repository for library {}", repo, name);
        Path libraryPath = name.toPath(CACHE_DIR);
        URL url = name.toURL(repo);
        downloadFile(url, libraryPath);
        libraryDownload.url = url.toString();

        HashCode hash = HashUtils.hash(SHA1, libraryPath);
        if (oldLibrary.checksums != null && !oldLibrary.checksums.isEmpty()) {
            boolean matches = false;
            for (String checksum : oldLibrary.checksums) {
                if (HashUtils.equals(hash, checksum)) {
                    matches = true;
                    break;
                }
            }
            if (!matches) {
                String expected = "[" + String.join(", ", oldLibrary.checksums) + "]";
                //Suppress warnings about scala hashes changing, these were intentionally shrunk
                if (!name.group.startsWith("org.scala-lang")) {
                    LOGGER.warn("Old installer profile checksums could not be validated for {}. Got {}, Expected one of {}.", name, hash, expected);
                }
            }
        }
        libraryDownload.sha1 = hash.toString();
        libraryDownload.size = Math.toIntExact(Files.size(libraryPath));

        downloads.artifact = libraryDownload;
        library.name = name;
        library.downloads = downloads;
        return library;
    }

    //Determine the repository to use for the library, if mojang has it, prefer that.
    public static String determineRepo(V1InstallProfile.Library library) throws IOException {
        String maven = MOJANG_MAVEN;
        if (headRequest(library.name.toURL(maven))) {
            return maven;
        }
        maven = FORGE_MAVEN;
        if (headRequest(library.name.toURL(maven))) {
            return maven;
        }
        throw new IllegalStateException("Mojang & Forge mavens do not contain: " + library.name);
    }
}
