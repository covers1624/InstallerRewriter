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
import okhttp3.Request;
import okhttp3.Response;
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
import java.util.*;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;
import static net.covers1624.quack.util.SneakyUtils.sneaky;
import static net.minecraftforge.ir.Utils.runWaitFor;

/**
 * Created by covers1624 on 30/4/21.
 */
public class InstallerRewriter {

    public static final Logger LOGGER = LogManager.getLogger();

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
    private static final Map<String, Boolean> recentHeadRequests = new HashMap<>();

    public static void main(String[] args) throws Throwable {
        System.exit(mainI(args));
    }

    public static int mainI(String[] args) throws Throwable {
        OptionParser parser = new OptionParser();

        OptionSpec<Void> helpOpt = parser.acceptsAll(asList("h", "help"), "Prints this help.").forHelp();

        OptionSpec<Void> validateMetadataOpt = parser.acceptsAll(asList("validate"), "Validates all versions exist in maven-metadata.xml");

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

        // Processors to run:
        OptionSpec<Void> mavenUrlChangeOpt  = parser.acceptsAll(asList("maven-url"), "Updates " + OLD_FORGE_MAVEN + " to " + FORGE_MAVEN);
        OptionSpec<Void> updateInstallerOpt = parser.acceptsAll(asList("update-installer"), "Updates the installer's executible code to the latest version for the major version used."); // Stupid name...
        OptionSpec<Void> convert1To2Opt     = parser.acceptsAll(asList("convert-legacy"), "Attempts to convert the legacy 1.x installer data to 2.x compatible version");

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

        boolean mavenUrlChange = optSet.has(mavenUrlChangeOpt);
        boolean convert1To2 = optSet.has(convert1To2Opt);
        if (convert1To2) {
            throw new IllegalStateException("Converting from 1.x to 2.x not implemented currently");
        }
        InstallerUpdater instUpdater = null;
        if (optSet.has(updateInstallerOpt)) {
            instUpdater = new InstallerUpdater();
            if (!instUpdater.loadInstallerData(CACHE_DIR)) {
                return -1;
            }
        }

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
            processVersion(signProps, forgeNotation.withVersion(folderVersions.get(x)),
                repoPath, backupPath, outputPath,
                instUpdater, mavenUrlChange, convert1To2,
                x, folderVersions.size());
        }

        return 0;
    }

    private static void processVersion(SignProps signProps, MavenNotation notation, Path repo,
        @Nullable Path backupPath, @Nullable Path outputPath,
        InstallerUpdater instUpdater, boolean mavenUrlFix, boolean convert1To2,
        int idx, int total
    ) throws IOException {
        boolean inPlace = backupPath != null;

        MavenNotation installer = notation.withClassifier("installer");
        Path repoInstallerPath = installer.toPath(repo);

        if (Files.notExists(repoInstallerPath)) {
            LOGGER.warn("[{}/{}] Missing installer for: {}", idx, total, notation);
            return;
        }
        LOGGER.info("");
        LOGGER.info("[{}/{}] Found installer jar for: {}", idx, total, notation);

        JarContents contents = JarContents.loadJar(repoInstallerPath.toFile());

        //Attempt to detect the installer format.
        InstallerFormat format = InstallerFormat.detectInstallerFormat(contents);
        if (format == null) {
            LOGGER.error("Unable to detect installer format for {}", notation);
            return;
        }
        LOGGER.info("[{}/{}] Found probable format: {}", idx, total, format);

        if (inPlace) {
            //Move windows installers if found
            MavenNotation winNotation = installer.withClassifier("installer-win").withExtension("exe");
            Path winFile = winNotation.toPath(repo);
            if (Files.exists(winFile)) {
                moveWithAssociated(winFile, winNotation.toPath(backupPath));
            }

            //Move javadoc zips.. Its 10 GB of useless space.
            MavenNotation docNotation = installer.withClassifier("javadoc").withExtension("zip");
            Path docFile = docNotation.toPath(repo);
            if (Files.exists(docFile)) {
                moveWithAssociated(docFile, docNotation.toPath(backupPath));
            }
        }

        LOGGER.info("[{}/{}] Processing {}..", idx, total, notation);

        if (instUpdater != null)
            format = instUpdater.pre(installer, contents, format);
        if (mavenUrlFix)
            format = new MavenUrlProcessor().process(installer, contents, format);
        if (convert1To2)
            format = PROCESSORS.get(format).process(installer, contents, format);
        if (instUpdater != null)
            format = instUpdater.post(installer, contents, format);

        if (contents.changed()) {
            LOGGER.info("[{}/{}] Contents Changed, saving file", idx, total);
            FileTime timestamp = Files.getLastModifiedTime(repoInstallerPath);
            Path output = null;
            if (inPlace) {
                output = installer.toPath(repo);
                Path backupFile = installer.toPath(backupPath);
                moveWithAssociated(repoInstallerPath, backupFile);
            } else {
                output = installer.toPath(outputPath);
            }
            contents.save(output.toFile());
            Files.setLastModifiedTime(output, timestamp);

            if (signProps != null) {
                signJar(signProps, output);
            }

            MultiHasher hasher = new MultiHasher(HASH_FUNCS);
            hasher.load(output);
            MultiHasher.HashResult result = hasher.finish();
            for (Map.Entry<MultiHasher.HashFunc, HashCode> entry : result.entrySet()) {
                Path hashFile = output.resolveSibling(output.getFileName() + "." + entry.getKey().name.toLowerCase());
                try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(hashFile))) {
                    out.print(entry.getValue().toString());
                    out.flush();
                }
                Files.setLastModifiedTime(hashFile, timestamp);
            }
        }
        LOGGER.info("[{}/{}] Processing finished!", idx, total);
    }

    public static void moveWithAssociated(Path from, Path to) throws IOException {
        Utils.makeParents(to);
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
                .header("User-Agent", Utils.USER_AGENT)
                .build();
        try (Response response = Utils.HTTP_CLIENT.newCall(request).execute()) {
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
