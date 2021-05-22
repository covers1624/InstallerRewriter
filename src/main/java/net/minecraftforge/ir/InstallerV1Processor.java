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

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Table;
import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import net.covers1624.quack.io.IOUtils;
import net.covers1624.quack.maven.MavenNotation;
import net.covers1624.quack.util.HashUtils;
import net.minecraftforge.ir.ClasspathEntry.LibraryClasspathEntry;
import net.minecraftforge.ir.json.Install;
import net.minecraftforge.ir.json.Manifest;
import net.minecraftforge.ir.json.V1InstallProfile;
import net.minecraftforge.ir.json.Version;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.Attributes;
import java.util.stream.Collectors;

import static net.covers1624.quack.util.SneakyUtils.sneak;
import static net.covers1624.quack.util.SneakyUtils.sneaky;
import static net.minecraftforge.ir.InstallerRewriter.*;
import static net.minecraftforge.ir.Utils.makeParents;

/**
 * Created by covers1624 on 1/5/21.
 */
@SuppressWarnings ("UnstableApiUsage")
public class InstallerV1Processor implements InstallerProcessor {

    private static final Logger LOGGER = LogManager.getLogger();

    private static final HashFunction SHA1 = Hashing.sha1();

    private static final URL VERSION_MANIFEST = sneaky(() -> new URL("https://launchermeta.mojang.com/mc/game/version_manifest.json"));

    private static final List<String> comment = Arrays.asList(
            "Please do not automate the download and installation of Forge.",
            "Our efforts are supported by ads from the download page.",
            "If you MUST automate this, please consider supporting the project through https://www.patreon.com/LexManos/"
    );

    //Overall replacements. These are checked first.
    public static final Map<MavenNotation, MavenNotation> REPLACEMENTS = ImmutableMap.<MavenNotation, MavenNotation>builder()
            .put(MavenNotation.parse("org.ow2.asm:asm:4.1-all"), MavenNotation.parse("org.ow2.asm:asm-all:4.1"))
            .build();

    //Per mc version replacements, these are run on the result of the above replacements.
    public static final Table<String, MavenNotation, MavenNotation> PER_VERSION_TABLE = HashBasedTable.create();

    static {
        //The highest LaunchWrapper version seen on 1.6.4 was 1.7, force update old installers referencing 1.3 to 1.7.
        PER_VERSION_TABLE.put("1.6.4", MavenNotation.parse("net.minecraft:launchwrapper:1.3"), MavenNotation.parse("net.minecraft:launchwrapper:1.7"));
    }

    @Override
    public void process(ProcessorContext ctx) throws IOException {
        MavenNotation baseNotation = ctx.notation.withClassifier(null).withExtension("jar");
        MavenNotation uniNotation = baseNotation.withClassifier("universal");

        Pair<Path, Path> pathPair = ctx.getFile(ctx.installer);
        try (FileSystem oldFs = IOUtils.getJarFileSystem(pathPair.getLeft(), true);
             FileSystem newFs = IOUtils.getJarFileSystem(pathPair.getRight(), true)
        ) {
            Path oldJarRoot = oldFs.getPath("/");
            Path newJarRoot = newFs.getPath("/");

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
            Path oldUniversalJar = oldJarRoot.resolve(filePathStr);
            if (!Files.exists(oldUniversalJar)) {
                LOGGER.error("'filePath' does not exist in old jar. {}", filePathStr);
                return;
            }

            Path newUniversalJar = newJarRoot.resolve("maven").resolve(baseNotation.toPath());
            Path repoUniversalJar = uniNotation.toPath(ctx.repoPath);
            if (!Utils.contentEquals(oldUniversalJar, repoUniversalJar)) {
                LOGGER.warn("Old installer universal jar differs from repo universal jar!");
            }

            //Load libraries referenced in the 'Class-Path' manifest attribute.
            List<ClasspathEntry> classpathLibraries = Utils.parseManifestClasspath(repoUniversalJar);

            Install install = generateInstallProfile(ctx, newUniversalJar);
            Version version = generateVersionJson(install, v1Profile, classpathLibraries);

            boolean classpathModified = classpathLibraries.stream().anyMatch(ClasspathEntry::isModified);

            if (classpathModified) {
                LOGGER.debug("Classpath modified, updating universal jar.");
                Pair<Path, Path> uniPair = ctx.getFile(uniNotation);
                Files.copy(uniPair.getLeft(), uniPair.getRight(), StandardCopyOption.REPLACE_EXISTING);
                try (FileSystem uniFs = IOUtils.getJarFileSystem(uniPair.getRight(), true)) {
                    Path uniRoot = uniFs.getPath("/");
                    Path manifestFile = uniRoot.resolve("META-INF/MANIFEST.MF");
                    java.util.jar.Manifest manifest;
                    try (InputStream is = Files.newInputStream(manifestFile)) {
                        manifest = new java.util.jar.Manifest(is);
                    }
                    Attributes mainAttribs = manifest.getMainAttributes();
                    mainAttribs.put(new Attributes.Name("Class-Path"), classpathLibraries.stream().map(ClasspathEntry::toPath).collect(Collectors.joining(" ")));

                    //Strip old signing info. Unsure if jar sign tool will explode or not, safe to strip regardless.
                    manifest.getEntries().clear(); // Nuke signing attributes in manifest.
                    Files.list(uniRoot.resolve("META-INF"))
                            .filter(Files::isRegularFile)
                            .filter(e -> {
                                // Find all signing metadata files.
                                String s = e.getFileName().toString();
                                return s.endsWith(".SF") || s.endsWith(".DSA") || s.endsWith(".RSA") || s.endsWith(".EC");
                            })
                            .forEach(sneak(Files::delete));

                    try (OutputStream os = Files.newOutputStream(manifestFile, StandardOpenOption.TRUNCATE_EXISTING)) {
                        manifest.write(os);
                        os.flush();
                    }
                }
                Files.copy(uniPair.getRight(), makeParents(newUniversalJar));
            } else {

                //TODO, grab license files from src jar.
                Files.copy(repoUniversalJar, makeParents(newUniversalJar));
            }

            Version.Library forgeLib = install.getLibraries().get(0);
            Version.Downloads downloads = new Version.Downloads();
            Version.LibraryDownload artifact = new Version.LibraryDownload();

            artifact.path = baseNotation.toPath();
            artifact.url = "";
            artifact.sha1 = HashUtils.hash(SHA1, newUniversalJar).toString();
            artifact.size = Math.toIntExact(Files.size(newUniversalJar));

            downloads.artifact = artifact;
            forgeLib.name = baseNotation;
            forgeLib.downloads = downloads;

            Files.write(newJarRoot.resolve("install_profile.json"), Utils.GSON.toJson(install).getBytes(StandardCharsets.UTF_8));
            Files.write(newJarRoot.resolve("version.json"), Utils.GSON.toJson(version).getBytes(StandardCharsets.UTF_8));
        }

    }

    public static Install generateInstallProfile(ProcessorContext ctx, Path newFilePath) throws IOException {
        Install install = new Install();
        install._comment_ = comment;
        install.spec = 0;
        install.profile = "forge";

        MavenNotation baseNotation = ctx.notation.withClassifier(null).withExtension("jar");

        String[] vSplit = baseNotation.version.split("-", 2);
        install.version = vSplit[0] + "-" + baseNotation.module + "-" + vSplit[1];
        install.icon = InstallerRewriter.ICON;
        install.json = "/version.json";
        install.path = baseNotation;
        install.logo = "/big_logo.png";
        install.minecraft = vSplit[0].replace("_", "-");//Replace _ with  - for prerelease versions
        install.welcome = "Welcome to the simple Forge installer.";
        install.mirrorList = MIRROR_LIST;

        List<Version.Library> libraries = install.getLibraries();
        Version.Library forgeLib = new Version.Library();
        libraries.add(forgeLib);//Add blank library, used by generateVersionInfo && rewriteLibrary. This is filled in after universal jar rewriting.

        return install;
    }

    public static Version generateVersionJson(Install newProfile, V1InstallProfile v1Profile, List<ClasspathEntry> classpathLibraries) throws IOException {
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
            replacement = PER_VERSION_TABLE.get(newProfile.minecraft, v.name);
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
                    LOGGER.debug("Removing {} from forge version json.", e.name);
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
            LOGGER.debug("Processing library: {}", library.name);
            libraries.add(rewriteLibrary(library, newProfile));
        }

        //Validate all libraries declared in the Class-Path manifest entry exist.
        for (ClasspathEntry lE : classpathLibraries) {
            if (!(lE instanceof LibraryClasspathEntry)) continue;
            LibraryClasspathEntry l = (LibraryClasspathEntry) lE;
            boolean found = false;
            for (Version.Library library : version.getLibraries()) {
                MavenNotation name = library.name;
                if (name == null) continue;

                if (name.equals(l.notation)) {
                    LOGGER.debug("Classpath library {} validated.", l.notation);
                    found = true;
                    break;
                } else if (name.group.equals(l.notation.group) && name.module.equals(l.notation.module) && Objects.equals(name.classifier, l.notation.classifier)) {
                    //Update the classpath if we have a newer version than a supplied library.
                    LOGGER.warn("Correcting incorrect library, Classpath assumes '{}', got '{}'", l.notation, name);
                    l.modified = true;
                    found = true;
                    l.notation = name;
                    break;
                }
            }
            if (found) continue;
            LOGGER.error("Classpath library {}, not found!", l.notation);
        }

        return version;
    }

    public static Version.Library rewriteLibrary(V1InstallProfile.Library oldLibrary, Install newProfile) throws IOException {

        MavenNotation name = oldLibrary.name;
        //This is the universal jar, without classifier.
        if (name.module.equals("forge") || name.module.equals("minecraftforge")) {
            return newProfile.getLibraries().get(0);
        }

        Version.Library library = new Version.Library();
        Version.Downloads downloads = new Version.Downloads();
        Version.LibraryDownload libraryDownload = new Version.LibraryDownload();
        libraryDownload.path = name.toPath();

        String repo = determineRepo(oldLibrary);
        LOGGER.debug("Using {} repository for library {}", repo, name);
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
