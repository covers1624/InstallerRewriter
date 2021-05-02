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
import com.google.common.collect.Sets;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import joptsimple.util.PathConverter;
import net.covers1624.quack.io.IOUtils;
import net.covers1624.quack.maven.MavenNotation;
import net.covers1624.quack.util.SneakyUtils;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSink;
import okio.Okio;
import okio.Source;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.maven.artifact.versioning.ComparableVersion;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;
import static net.minecraftforge.ir.Utils.makeParents;

/**
 * Created by covers1624 on 30/4/21.
 */
public class InstallerRewriter {

    public static final Logger LOGGER = LogManager.getLogger();

    public static final String USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/87.0.4280.88 Safari/537.36";

    public static final String FORGE_MAVEN = "https://maven.minecraftforge.net/";
    public static final String OLD_FORGE_MAVEN = "https://files.minecraftforge.net/maven/";
    public static final String MIRROR_LIST = "https://files.minecraftforge.net/mirrors-2.0.json";

    public static final String MOJANG_MAVEN = "https://libraries.minecraft.net/";

    public static final String ICON = SneakyUtils.sneaky(() -> {
        try (InputStream is = InstallerRewriter.class.getResourceAsStream("/icon.ico")) {
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(IOUtils.toBytes(is));
        }
    });

    public static final Path RUN_DIR = Paths.get(".").toAbsolutePath().normalize();
    public static final Path CACHE_DIR = RUN_DIR.resolve("cache");
    public static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .readTimeout(Duration.ofMinutes(5))
            .connectTimeout(Duration.ofMinutes(5))
            .build();

    private static final Map<InstallerFormat, InstallerProcessor> PROCESSORS = ImmutableMap.of(
            InstallerFormat.V1, new InstallerV1Processor(),
            InstallerFormat.V2, new InstallerV2Processor()
    );

    //Speeeeeeed.
    private static final Set<String> recentFiles = new HashSet<>();
    private static final Map<String, Boolean> recentHeadRequests = new HashMap<>();

    public static void main(String[] args) throws Throwable {
        System.exit(mainI(args));
    }

    public static int mainI(String[] args) throws Throwable {
        OptionParser parser = new OptionParser();
        OptionSpec<String> nonOptions = parser.nonOptions();

        OptionSpec<Void> helpOpt = parser.acceptsAll(asList("h", "help"), "Prints this help.").forHelp();

        OptionSpec<Void> validateMetadataOpt = parser.acceptsAll(asList("validate"), "Validates all versions exist in maven-metadata.xml");

        OptionSpec<String> installerCoordsOpt = parser.acceptsAll(asList("installer"), "The maven coords of the installer to use. group:module:version[:classifier][@ext]")
                .withRequiredArg()
                .defaultsTo("net.minecraftforge:installer:2.0.+:shrunk");

        OptionSpec<Path> repoPathOpt = parser.acceptsAll(asList("r", "repo"), "The repository path on disk.")
                .withRequiredArg()
                .withValuesConvertedBy(new PathConverter());

        OptionSpec<Path> outputRepoPathOpt = parser.acceptsAll(asList("o", "output"), "The directory to outputPath updated files.")
                .withRequiredArg()
                .withValuesConvertedBy(new PathConverter());

        OptionSet optSet = parser.parse(args);
        if (optSet.has(helpOpt)) {
            parser.printHelpOn(System.err);
            return -1;
        }

        if (!optSet.has(repoPathOpt)) {
            LOGGER.error("Expected --repo argument.");
            parser.printHelpOn(System.err);
            return -1;
        }

        if (!optSet.has(outputRepoPathOpt)) {
            LOGGER.error("Expected --output argument.");
            parser.printHelpOn(System.err);
            return -1;
        }

        Path repoPath = optSet.valueOf(repoPathOpt);
        if (Files.notExists(repoPath)) {
            LOGGER.error("Provided repo path does not exist");
            return -1;
        }

        Path outputPath = optSet.valueOf(outputRepoPathOpt);

        LOGGER.info("Resolving latest Forge installer..");
        MavenNotation installerNotation = MavenNotation.parse(optSet.valueOf(installerCoordsOpt));
        MavenNotation latestInstaller = resolveLatestInstaller(installerNotation);
        Path latestInstallerPath = CACHE_DIR.resolve(latestInstaller.toPath());
        downloadFile(latestInstaller.toURL(FORGE_MAVEN), latestInstallerPath);

        MavenNotation forgeNotation = MavenNotation.parse("net.minecraftforge:forge");

        Path moduleFolder = repoPath.resolve(forgeNotation.toModulePath());

        if (Files.notExists(moduleFolder)) {
            LOGGER.error("Provided repo does not contain forge.");
            return -1;
        }

        LOGGER.info("Reading sub-folders of {}", forgeNotation);
        List<String> folderVersions = Files.list(moduleFolder)
                .filter(Files::isDirectory)
                .map(Path::getFileName)
                .map(Path::toString)
                .collect(Collectors.toList());

        if (optSet.has(validateMetadataOpt)) {
            LOGGER.info("Reading maven-metadata.xml");
            List<String> versions = Utils.parseVersions(moduleFolder.resolve("maven-metadata.xml"));
            Set<String> versionSet = new HashSet<>(versions);
            Set<String> folderVersionSet = new HashSet<>(folderVersions);

            LOGGER.info("Checking for mismatches..");
            Set<String> missingMetadata = Sets.difference(folderVersionSet, versionSet);
            Set<String> missingFolder = Sets.difference(versionSet, folderVersionSet);

            //Validate all versions are accounted for.
            if (!missingMetadata.isEmpty() || !missingFolder.isEmpty()) {
                LOGGER.warn("Found mismatch between maven-metadata and version folder list.");
                if (!missingMetadata.isEmpty()) {
                    LOGGER.warn("Missing in maven-metadata, but exist as folders:");
                    for (String version : missingMetadata) {
                        LOGGER.warn(" {}", version);
                    }
                }
                if (!missingFolder.isEmpty()) {
                    LOGGER.warn("Missing in folder but exist in maven-metadata:");
                    for (String version : missingFolder) {
                        LOGGER.warn(" {}", version);
                    }
                }
            }
        }

        LOGGER.info("Sorting version lists..");
        folderVersions.sort(Comparator.comparing(ComparableVersion::new));
        LOGGER.info("Processing versions..");
        for (String version : folderVersions) {
            processVersion(forgeNotation.withVersion(version), repoPath, outputPath, latestInstallerPath);
        }

        return 0;
    }

    public static void processVersion(MavenNotation notation, Path repo, Path outputPath, Path latestInstaller) throws IOException {
        if (notation.version.startsWith("1.5.2-")) return; //TODO Temporary

        MavenNotation installer = notation.withClassifier("installer");
        MavenNotation installerWin = notation.withClassifier("installer-win").withExtension("exe");
        Path jarInstallerPath = repo.resolve(installer.toPath());
        Path winInstallerPath = repo.resolve(installerWin.toPath());
        if (Files.notExists(jarInstallerPath)) {
            LOGGER.warn("Missing installer for: {}", notation);
            return;
        }
        LOGGER.info("Found installer jar for: {}", notation);
        //        if (Files.exists(winInstallerPath)) {
//            LOGGER.info("Found win installer for: {}", notation);
//            if (Files.notExists(jarInstallerPath)) {
//                LOGGER.error(" Jar installer does not exist for: {}", notation);
//            }
//        }

        try (FileSystem fs = IOUtils.getJarFileSystem(jarInstallerPath, true)) {
            Path jarRoot = fs.getPath("/");
            InstallerFormat probableFormat = InstallerFormat.detectInstallerFormat(jarRoot);
            if (probableFormat == null) {
                LOGGER.error("Unable to detect probable installer format for {}", notation);
                return;
            }
            LOGGER.info("Found probable format: {}", probableFormat);
            LOGGER.info("Processing {}..", notation);

            InstallerProcessor processor = PROCESSORS.get(probableFormat);
            Path newInstaller = outputPath.resolve(installer.toPath());
            Files.copy(latestInstaller, makeParents(newInstaller), StandardCopyOption.REPLACE_EXISTING);

            processor.process(notation, repo, newInstaller, jarRoot);
            LOGGER.info("Processing finished!");
        }
    }

    public static MavenNotation resolveLatestInstaller(MavenNotation installerNotation) throws IOException {
        String metadataPath = installerNotation.toModulePath() + "maven-metadata.xml";
        Path metadataFile = CACHE_DIR.resolve(metadataPath);
        downloadFile(new URL(FORGE_MAVEN + metadataPath), metadataFile, true);

        List<String> versions = Utils.parseVersions(metadataFile);
        String targetVersion = installerNotation.version;
        MavenNotation ret;
        if (targetVersion.endsWith("+")) {
            String tv = targetVersion.replace("+", "");
            List<String> filtered = versions.stream()
                    .filter(e -> e.startsWith(tv))
                    .sorted(Comparator.comparing(ComparableVersion::new).reversed())
                    .collect(Collectors.toList());
            if (filtered.isEmpty()) {
                throw new RuntimeException("Could not find any installer versions matching: " + targetVersion);
            }
            ret = installerNotation.withVersion(filtered.get(0));
        } else {
            if (!versions.contains(targetVersion)) throw new RuntimeException("Exact installer version '" + targetVersion + "' does not exist.");

            ret = installerNotation;
        }
        LOGGER.info("Resolved Forge installer: {}", ret);
        return ret;
    }

    public static void downloadFile(URL url, Path file) throws IOException {
        downloadFile(url, file, false);
    }

    public static void downloadFile(URL url, Path file, boolean forceDownload) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + "__tmp");
        if (forceDownload && !recentFiles.contains(url.toString())) {
            Files.deleteIfExists(file);
        }
        if (Files.exists(file)) return; //Assume the file is already downloaded.
        recentFiles.add(url.toString());

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build();
        try (Response response = HTTP_CLIENT.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful()) {
                throw new RuntimeException("Got: " + response.code());
            }
            if (body == null) {
                throw new RuntimeException("Expected response body.");
            }

            LOGGER.info("Downloading file " + file.getFileName());
            try (Source source = body.source()) {
                try (BufferedSink sink = Okio.buffer(Okio.sink(makeParents(tmp)))) {
                    sink.writeAll(source);
                }
            }
            Files.move(tmp, file);

            Date lastModified = response.headers().getDate("Last-Modified");
            if (lastModified != null) {
                Files.setLastModifiedTime(file, FileTime.fromMillis(lastModified.getTime()));
            }
        }
    }

    public static boolean headRequest(URL url) throws IOException {
        Boolean recent = recentHeadRequests.get(url.toString());
        if (recent != null) {
            return recent;
        }
        Request request = new Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", USER_AGENT)
                .build();
        try (Response response = HTTP_CLIENT.newCall(request).execute()) {
            recent = response.isSuccessful();
            recentHeadRequests.put(url.toString(), recent);
            return recent;
        }
    }
}