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

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import joptsimple.util.PathConverter;
import net.covers1624.quack.io.IOUtils;
import net.covers1624.quack.maven.MavenNotation;
import net.covers1624.quack.util.JavaPathUtils;
import net.covers1624.quack.util.ProcessUtils;
import net.covers1624.quack.util.SneakyUtils;
import net.minecraftforge.ir.json.Manifest;
import net.minecraftforge.ir.json.Version;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.maven.artifact.versioning.ComparableVersion;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.URL;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;
import static net.minecraftforge.ir.InstallerRewriter.*;

/**
 * Created by covers1624 on 31/5/21.
 */
public class InstallerTester {

    private static final Logger LOGGER = LogManager.getLogger();

    public static void main(String[] args) throws Throwable {
        System.exit(mainI(args));
    }

    public static int mainI(String[] args) throws Throwable {

        OptionParser parser = new OptionParser();
        OptionSpec<String> nonOptions = parser.nonOptions();

        OptionSpec<Void> helpOpt = parser.acceptsAll(asList("h", "help"), "Prints this help.").forHelp();

        OptionSpec<Path> repoPathOpt = parser.acceptsAll(asList("r", "repo"), "The repository path on disk.")
                .withRequiredArg()
                .withValuesConvertedBy(new PathConverter());

        OptionSpec<Path> outputPathOpt = parser.acceptsAll(asList("o", "output"), "The output path to place installed servers.")
                .withRequiredArg()
                .withValuesConvertedBy(new PathConverter());

        OptionSpec<Path> reportsPathOpt = parser.acceptsAll(asList("r", "reports"), "The reports directory.")
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

        Path repoPath = optSet.valueOf(repoPathOpt);

        if (!optSet.has(outputPathOpt)) {
            LOGGER.error("Expected --output argument.");
            return -1;
        }

        Path outputPath = optSet.valueOf(outputPathOpt);

        if (!optSet.has(reportsPathOpt)) {
            LOGGER.error("Expected --reports argument.");
            return -1;
        }

        Path reportsPath = optSet.valueOf(reportsPathOpt);
        Files.createDirectories(reportsPath);

        MavenNotation forgeNotation = MavenNotation.parse("net.minecraftforge:forge");

        Path moduleFolder = repoPath.resolve(forgeNotation.toModulePath());

        LOGGER.info("Reading sub-folders of {}", forgeNotation);
        List<String> folderVersions = Files.list(moduleFolder)
                .filter(Files::isDirectory)
                .map(Path::getFileName)
                .map(Path::toString)
                .collect(Collectors.toList());

        LOGGER.info("Sorting version lists..");
        folderVersions.sort(Comparator.comparing(ComparableVersion::new));
        LOGGER.info("Testing versions..");

        Path ljfVersionsPath = reportsPath.resolve("ljf_versions.txt");
        OpenOption[] options = Files.exists(ljfVersionsPath) ? new OpenOption[] { StandardOpenOption.APPEND } : new OpenOption[] {};
        try (PrintWriter ljfVersionsWriter = new PrintWriter(Files.newBufferedWriter(ljfVersionsPath, options))) {
            for (String version : folderVersions) {
                InstallReport report = testInstaller(forgeNotation.withVersion(version), repoPath, outputPath);
                if (report == null) continue;

                if (report.requiresLegacyJavaFixer) {
                    ljfVersionsWriter.println(version);
                    ljfVersionsWriter.flush();
                }

                if (!report.installSuccess || !report.runSuccess) {
                    LOGGER.warn("Installer for '{}' reported as failed.", version);
                    Path reportPath = reportsPath.resolve(version + ".txt");
                    try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(reportPath), true)) {
                        w.println("Installer Success: " + report.installSuccess);
                        w.println("Run Success:       " + report.runSuccess);
                        w.println("Run exit reason:   " + report.runExitReason);
                        w.println();
                        w.println("Installer Log:");
                        report.installLog.forEach(w::println);
                        w.println();
                        w.println("Server Log:");
                        report.runLog.forEach(w::println);
                        w.flush();
                    }
                } else {
                    LOGGER.info("Installer success!");
                }
            }
        }

        return 0;
    }

    private static InstallReport testInstaller(MavenNotation notation, Path repo, Path outputPath) throws Throwable {
        if (notation.version.startsWith("1.5.2-")) return null; //TODO Temporary

        Path installDirectory = outputPath.resolve(notation.version);

        if (Files.exists(installDirectory)) return null;

        MavenNotation installer = notation.withClassifier("installer");
        Path installerPath = installer.toPath(repo);

        if (Files.notExists(installerPath)) {
            LOGGER.warn("Missing installer for: {}", notation);
            return null;
        }
        InstallReport report = new InstallReport();
        report.notation = notation;

        LOGGER.info("Testing installer: {}", notation);

        Files.createDirectories(installDirectory);

        Files.createSymbolicLink(installDirectory.resolve("libraries"), InstallerRewriter.CACHE_DIR);

        {
            Path versionManifest = CACHE_DIR.resolve("version_manifest.json");
            downloadFile(VERSION_MANIFEST, versionManifest, true);

            Manifest manifest;
            try (BufferedReader reader = Files.newBufferedReader(versionManifest)) {
                manifest = Utils.GSON.fromJson(reader, Manifest.class);
            }

            String[] vSplit = notation.version.split("-", 2);
            String mcVersion = vSplit[0].replace("_", "-");
            Path versionJson = CACHE_DIR.resolve(mcVersion + ".json");
            downloadFile(new URL(manifest.getUrl(mcVersion)), versionJson, true);

            Version mcVersionJson;
            try (BufferedReader reader = Files.newBufferedReader(versionJson)) {
                mcVersionJson = Utils.GSON.fromJson(reader, Version.class);
            }

            Version.Download server = mcVersionJson.downloads.get("server");
            String name = "minecraft_server." + mcVersion + ".jar";
            Path serverJar = CACHE_DIR.resolve(name);
            downloadFile(new URL(server.url), serverJar);
            Files.copy(serverJar, installDirectory.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        }

        int installerExit = Utils.runWaitFor(builder -> {
            builder.command(asList(
                    JavaPathUtils.getJavaExecutable().toAbsolutePath().toString(),
                    "-jar",
                    installerPath.toAbsolutePath().toString(),
                    "--installServer",
                    installDirectory.toAbsolutePath().toString(),
                    "--mirror",
                    "https://maven-proxy.covers1624.net/"
            ));
            builder.directory(installDirectory.toFile());
        }, line -> {
            report.installLog.add(line);
            LOGGER.debug("Installer: {}", line);
        });
        report.installSuccess = installerExit == 0;

        Path eula = installDirectory.resolve("eula.txt");
        Files.write(eula, Collections.singletonList("eula=true"));

        LOGGER.info("Testing instance..");

        ProcessBuilder procBuilder = new ProcessBuilder();
        procBuilder.command(asList(
                JavaPathUtils.getJavaExecutable().toAbsolutePath().toString(),
                "-jar",
                installDirectory.resolve(notation.toFileName()).toAbsolutePath().toString(),
                "nogui"
        ));
        procBuilder.redirectErrorStream(true);
        procBuilder.directory(installDirectory.toFile());
        Process process = procBuilder.start();
        AtomicBoolean finishedStarting = new AtomicBoolean(false);
        AtomicLong lastOutput = new AtomicLong(System.currentTimeMillis());
        CompletableFuture<Void> stdoutFuture = CompletableFuture.runAsync(SneakyUtils.sneak(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    report.runLog.add(line);
                    lastOutput.set(System.currentTimeMillis());
                    //We found the 'Done' line, server has started successfully.
                    if (line.contains("Done") && line.contains("\"help\"")) {
                        finishedStarting.set(true);
                    }
                    LOGGER.debug("Server: {}", line);
                }
            }
        }));
        ProcessUtils.onExit(process).thenRunAsync(() -> {
            if (!stdoutFuture.isDone()) stdoutFuture.cancel(true);
        });

        PrintWriter outWriter = new PrintWriter(process.getOutputStream(), true);

        boolean failed = true;
        boolean stopRequested = false;
        while (process.isAlive()) {
            long currTime = System.currentTimeMillis();
            if (currTime - lastOutput.get() >= TimeUnit.SECONDS.toMillis(10)) {
                LOGGER.warn("Considering server crashed.. No output for 10 seconds.");
                failed = true;
                report.runSuccess = false;
                report.runExitReason = "Considering crashed after 10 seconds of inactivity.";
                break;
            } else if (!stopRequested && finishedStarting.get()) {
                Thread.sleep(400);
                outWriter.println("stop");
                failed = false;
                stopRequested = true;
            }

            Thread.sleep(200);
        }

        if (!failed) {
            report.runSuccess = true;
            report.runExitReason = "Success";
            Utils.delete(installDirectory);
        } else {
            if (process.isAlive()) {
                try {
                    process.destroyForcibly().waitFor();
                } catch (InterruptedException e) {
                    LOGGER.error("Interrupted whilst waiting for exit.", e);
                }
            }

            // If we have the start of a LJF stack trace, this version requires it.
            List<String> runLog = report.runLog;
            for (int i = 0; i < runLog.size(); i++) {
                String s = runLog.get(i);
                String sNext = i + 1 == runLog.size() ? null : runLog.get(i + 1);
                if (s.contains("java.util.ConcurrentModificationException")) {
                    report.requiresLegacyJavaFixer = StringUtils.contains(sNext, "at java.util.ArrayList$Itr.checkForComodification(ArrayList");
                    report.runExitReason = "Requires Legacy Java Fixer";
                    break;
                }
            }
        }

        return report;
    }

    public static class InstallReport {

        public MavenNotation notation;
        public boolean installSuccess;
        public List<String> installLog = new ArrayList<>();
        public boolean runSuccess;
        public String runExitReason;
        public List<String> runLog = new ArrayList<>();
        public boolean requiresLegacyJavaFixer;

    }
}
