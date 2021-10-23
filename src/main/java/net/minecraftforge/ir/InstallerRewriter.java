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
import com.google.common.hash.HashCode;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import joptsimple.util.PathConverter;
import net.covers1624.quack.io.IOUtils;
import net.covers1624.quack.maven.MavenNotation;
import net.covers1624.quack.util.JavaPathUtils;
import net.covers1624.quack.util.MultiHasher;
import net.covers1624.quack.util.SneakyUtils;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSink;
import okio.Okio;
import okio.Source;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;
import static net.covers1624.quack.util.SneakyUtils.sneaky;
import static net.minecraftforge.ir.Utils.makeParents;
import static net.minecraftforge.ir.Utils.runWaitFor;

/**
 * Created by covers1624 on 30/4/21.
 */
@SuppressWarnings ("UnstableApiUsage")
public class InstallerRewriter {

    public static final Logger LOGGER = LogManager.getLogger();

    public static final String USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/87.0.4280.88 Safari/537.36";

    public static final URL VERSION_MANIFEST = sneaky(() -> new URL("https://launchermeta.mojang.com/mc/game/version_manifest.json"));

    public static final String FORGE_MAVEN = "https://maven.minecraftforge.net/";
    public static final String OLD_FORGE_MAVEN = "https://files.minecraftforge.net/maven/";
    public static final String MIRROR_LIST = "https://files.minecraftforge.net/mirrors-2.0.json";

    public static final String MOJANG_MAVEN = "https://libraries.minecraft.net/";
    public static final String MAVEN_LOCAL = Paths.get(System.getProperty("user.home")).resolve(".m2/repository").toUri().toString();

    // If this system property is provided, InstallerRewriter will favor using this repository over
    // the Mojang and Forge mavens. This is intended purely for running installers with the InstallerTester sub-program.
    public static final String FORCED_MAVEN = System.getProperty("ir.forced_maven");
    public static final String[] MAVENS = SneakyUtils.sneaky(() -> {
        List<String> mavens = new ArrayList<>();
        if (FORCED_MAVEN != null) {
            mavens.add(FORCED_MAVEN);
        }
        mavens.add(MOJANG_MAVEN);
        mavens.add(FORGE_MAVEN);
        return mavens.toArray(new String[0]);
    });

    // Forces the use of Local files in generated installers.
    // Generated installers will have uri's to the CACHE_DIR instead of a real maven repository.
    // This is intended for use with the InstallerTester sub-program.
    public static final boolean USE_LOCAL_CACHE = Boolean.getBoolean("ir.use_local_cache");

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

    private static final List<MultiHasher.HashFunc> HASH_FUNCS = Arrays.asList(
            MultiHasher.HashFunc.MD5,
            MultiHasher.HashFunc.SHA1,
            MultiHasher.HashFunc.SHA256,
            MultiHasher.HashFunc.SHA512
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

        OptionSpec<Void> inPlaceOpt = parser.acceptsAll(asList("i", "in-place"), "Specifies if we should update the repository in-place, moving old installers to the specified backups dir, or if we should use an output folder.");

        OptionSpec<Path> repoPathOpt = parser.acceptsAll(asList("r", "repo"), "The repository path on disk.")
                .withRequiredArg()
                .withValuesConvertedBy(new PathConverter());

        OptionSpec<Path> backupPathOpt = parser.acceptsAll(asList("b", "backup"), "The directory to place the old installers into.")
                .requiredIf(inPlaceOpt)
                .withRequiredArg()
                .withValuesConvertedBy(new PathConverter());

        OptionSpec<Path> outputPathOpt = parser.acceptsAll(asList("o", "output"), "The directory to place installers when in-place mode is turned off.")
                .requiredUnless(inPlaceOpt)
                .withRequiredArg()
                .withValuesConvertedBy(new PathConverter());


        OptionSpec<Void> urlFixesOpt =  parser.acceptsAll(asList("u", "url-only"), "Will only run the maven URL updating processor.");

        //Signing
        OptionSpec<Void> signOpt = parser.acceptsAll(asList("sign"), "If jars should be signed or not.");
        OptionSpec<Path> keyStoreOpt = parser.acceptsAll(asList("keyStore"), "The keystore to use for signing.")
                .availableIf(signOpt)
                .requiredIf(signOpt)
                .withRequiredArg()
                .withValuesConvertedBy(new PathConverter());

        OptionSpec<String> keyAliasOpt = parser.acceptsAll(asList("keyAlias"), "The key alias to use for signing.")
                .availableIf(signOpt)
                .requiredIf(signOpt)
                .withRequiredArg();

        OptionSpec<String> keyStorePassOpt = parser.acceptsAll(asList("keyStorePass"), "The password for the provided keystore.")
                .availableIf(signOpt)
                .requiredIf(signOpt)
                .withRequiredArg();

        OptionSpec<String> keyPassOpt = parser.acceptsAll(asList("keyPass"), "The password for the key within the provided keystore.")
                .availableIf(signOpt)
                .requiredIf(signOpt)
                .withRequiredArg();

        OptionSet optSet = parser.parse(args);
        if (optSet.has(helpOpt)) {
            parser.printHelpOn(System.err);
            return -1;
        }

        boolean inPlace = optSet.has(inPlaceOpt);

        if (!optSet.has(repoPathOpt)) {
            LOGGER.error("Expected --repo argument.");
            parser.printHelpOn(System.err);
            return -1;
        }

        if (inPlace && !optSet.has(backupPathOpt)) {
            LOGGER.error("Expected --backup argument.");
            parser.printHelpOn(System.err);
            return -1;
        }

        if (!inPlace && !optSet.has(outputPathOpt)) {
            LOGGER.error("Expected --output argument.");
            return -1;
        }

        Path repoPath = optSet.valueOf(repoPathOpt);
        if (Files.notExists(repoPath)) {
            LOGGER.error("Provided repo path does not exist");
            return -1;
        }

        Path backupPath;
        Path outputPath;
        if (inPlace) {
            backupPath = optSet.valueOf(backupPathOpt);
            outputPath = null;
        } else {
            backupPath = null;
            outputPath = optSet.valueOf(outputPathOpt);
        }

        SignProps signProps = null;
        if (optSet.has(signOpt)) {
            signProps = new SignProps();
            signProps.keyStorePath = optSet.valueOf(keyStoreOpt);
            signProps.keyAlias = optSet.valueOf(keyAliasOpt);
            signProps.keyStorePass = optSet.valueOf(keyStorePassOpt);
            signProps.keyPass = optSet.valueOf(keyPassOpt);
        }

        boolean urlFixesOnly = optSet.has(urlFixesOpt);

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
            LOGGER.info("");
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

        LOGGER.info("");

        LOGGER.info("Sorting version lists..");
        folderVersions.sort(Comparator.comparing(ComparableVersion::new));
        LOGGER.info("Processing versions..");
        for (int x = 0; x < folderVersions.size(); x++) {
            processVersion(signProps, forgeNotation.withVersion(folderVersions.get(x)), repoPath, backupPath, outputPath, latestInstallerPath, urlFixesOnly, x, folderVersions.size());
        }

        return 0;
    }

    private static void processVersion(SignProps signProps, MavenNotation notation, Path repo, @Nullable Path backupPath, @Nullable Path outputPath, Path latestInstaller, boolean urlFixesOnly, int idx, int total) throws IOException {
        if (notation.version.startsWith("1.5.2-")) return; //TODO Temporary

        boolean inPlace = backupPath != null;

        MavenNotation installer = notation.withClassifier("installer");
        Path repoInstallerPath = installer.toPath(repo);

        if (Files.notExists(repoInstallerPath)) {
            LOGGER.warn("[{}/{}] Missing installer for: {}", idx, total, notation);
            return;
        }
        LOGGER.info("");
        LOGGER.info("[{}/{}] Found installer jar for: {}", idx, total, notation);

        //Attempt to detect the installer format.
        InstallerFormat probableFormat;
        try (FileSystem fs = IOUtils.getJarFileSystem(repoInstallerPath, true)) {
            Path jarRoot = fs.getPath("/");
            probableFormat = InstallerFormat.detectInstallerFormat(jarRoot);
            if (probableFormat == null) {
                LOGGER.error("Unable to detect probable installer format for {}", notation);
                return;
            }
        }
        LOGGER.info("[{}/{}] Found probable format: {}", idx, total, probableFormat);

        //List if files that need to be re hashed/signed
        List<Path> modifiedFiles = new ArrayList<>();
        ProcessorContext ctx = new ProcessorContext(notation, installer, repo) {
            @Override
            public Pair<Path, Path> getFile(MavenNotation notation) throws IOException {
                Path repoFile = notation.toPath(repo);
                Pair<Path, Path> pair;
                if (inPlace) {
                    Path backupFile = notation.toPath(backupPath);
                    moveWithAssociated(repoFile, backupFile);
                    modifiedFiles.add(repoFile);
                    pair = Pair.of(backupFile, repoFile);
                } else {
                    Path outFile = notation.toPath(outputPath);
                    modifiedFiles.add(outFile);
                    pair = Pair.of(repoFile, outFile);
                }
                if (notation.equals(installer)) {
                    Files.copy(latestInstaller, makeParents(pair.getRight()), StandardCopyOption.REPLACE_EXISTING);
                }
                return pair;
            }
        };

        if (inPlace) {
            //Move windows installers if found
            MavenNotation winNotation = installer.withClassifier("installer-win").withClassifier("exe");
            Path winFile = winNotation.toPath(repo);
            if (Files.exists(winFile)) {
                moveWithAssociated(winFile, winNotation.toPath(backupPath));
            }

            //Move javadoc zips.. Its 10 GB of useless space.
            MavenNotation docNotation = installer.withClassifier("javadoc").withClassifier("zip");
            Path docFile = docNotation.toPath(repo);
            if (Files.exists(docFile)) {
                moveWithAssociated(docFile, docNotation.toPath(backupPath));
            }
        }

        LOGGER.info("[{}/{}] Processing {}..", idx, total, notation);

        InstallerProcessor processor = null;
        if (urlFixesOnly)
            processor = new MavenUrlProcessor();
        else
            processor = PROCESSORS.get(probableFormat);

        processor.process(ctx);
        LOGGER.info("[{}/{}] Processing finished!", idx, total);

        for (Path file : modifiedFiles) {
            //Re-sign all modified files.
            if (signProps != null) {
                signJar(signProps, file);
            }

            MultiHasher hasher = new MultiHasher(HASH_FUNCS);
            hasher.load(file);
            MultiHasher.HashResult result = hasher.finish();
            for (Map.Entry<MultiHasher.HashFunc, HashCode> entry : result.entrySet()) {
                Path hashFile = file.resolveSibling(file.getFileName() + "." + entry.getKey().name.toLowerCase());
                try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(hashFile))) {
                    out.print(entry.getValue().toString());
                    out.flush();
                }
            }
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

    public static void moveWithAssociated(Path from, Path to) throws IOException {
        Files.move(from, to);
        String theFileName = from.getFileName().toString();
        List<Path> associated = Files.list(from.getParent())
                .filter(e -> e.getFileName().toString().startsWith(theFileName))
                .collect(Collectors.toList());
        for (Path assoc : associated) {
            Files.move(assoc, to.resolveSibling(assoc.getFileName()));
        }
    }

    public static void signJar(SignProps props, Path jarToSign) throws IOException {
        runWaitFor("Sign", builder -> {
            builder.command(asList(
                    JavaPathUtils.getJarSignerExecutable().toAbsolutePath().toString(),
                    "-keystore",
                    props.keyStorePath.toAbsolutePath().toString(),
                    "-storepass",
                    props.keyStorePass,
                    "-keypass",
                    props.keyPass,
                    jarToSign.toAbsolutePath().toString(),
                    props.keyAlias
            ));
        });
    }

    public static void downloadFile(URL url, Path file) throws IOException {
        downloadFile(url, file, false);
    }

    public static void downloadFile(URL url, Path file, boolean forceDownload) throws IOException {
        // OkHttp does not handle the file protocol.
        if (url.getProtocol().equals("file")) {
            try {
                Files.createDirectories(file.getParent());
                Files.copy(Paths.get(url.toURI()), file, StandardCopyOption.REPLACE_EXISTING);
            } catch (URISyntaxException e) {
                throw new RuntimeException("What.", e);
            }
        }
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
        // OkHttp does not handle the file protocol.
        if (url.getProtocol().equals("file")) {
            try {
                return Files.exists(Paths.get(url.toURI()));
            } catch (URISyntaxException e) {
                throw new RuntimeException("What.", e);
            }
        }

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

    public static class SignProps {

        public Path keyStorePath = null;
        public String keyAlias = null;
        public String keyStorePass = null;
        public String keyPass = null;
    }
}
