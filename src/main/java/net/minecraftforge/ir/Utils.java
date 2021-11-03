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
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.covers1624.quack.gson.MavenNotationAdapter;
import net.covers1624.quack.maven.MavenNotation;
import net.covers1624.quack.util.HashUtils;
import net.covers1624.quack.util.ProcessUtils;
import net.minecraftforge.ir.ClasspathEntry.LibraryClasspathEntry;
import net.minecraftforge.ir.ClasspathEntry.StringClasspathEntry;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSink;
import okio.Okio;
import okio.Source;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.artifact.repository.metadata.Versioning;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static java.util.Objects.requireNonNull;
import static net.covers1624.quack.util.SneakyUtils.sneak;

/**
 * Created by covers1624 on 30/4/21.
 */
@SuppressWarnings ("UnstableApiUsage")
public class Utils {

    public static final Logger LOGGER = LogManager.getLogger();

    private static final MetadataXpp3Reader METADATA_XPP3_READER = new MetadataXpp3Reader();
    private static final HashFunction SHA256 = Hashing.sha256();

    public static final String USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/87.0.4280.88 Safari/537.36";

    public static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .readTimeout(Duration.ofMinutes(5))
            .connectTimeout(Duration.ofMinutes(5))
            .build();

    public static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(MavenNotation.class, new MavenNotationAdapter())
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    public static int getAsInt(JsonObject obj, String key) {
        JsonElement element = requireNonNull(obj.get(key));
        if (!element.isJsonPrimitive()) throw new IllegalArgumentException("Expected JsonPrimitive.");
        return element.getAsJsonPrimitive().getAsInt();
    }

    public static int getAsInt(JsonObject obj, String key, int _default) {
        JsonElement element = obj.get(key);
        if (element == null) return _default;
        if (!element.isJsonPrimitive()) return _default;
        return element.getAsJsonPrimitive().getAsInt();
    }

    public static String getAsString(JsonObject obj, String key) {
        JsonElement element = requireNonNull(obj.get(key));
        if (!element.isJsonPrimitive()) throw new IllegalArgumentException("Expected JsonPrimitive.");
        return element.getAsJsonPrimitive().getAsString();
    }

    public static String getAsString(JsonObject obj, String key, String _default) {
        JsonElement element = obj.get(key);
        if (element == null) return _default;
        if (!element.isJsonPrimitive()) return _default;
        return element.getAsJsonPrimitive().getAsString();
    }

    public static List<String> parseVersions(Path file) throws IOException {
        if (Files.exists(file)) {
            try (InputStream is = Files.newInputStream(file)) {
                Metadata metadata = METADATA_XPP3_READER.read(is);
                Versioning versioning = metadata.getVersioning();
                if (versioning != null) {
                    return versioning.getVersions();
                }
            } catch (XmlPullParserException e) {
                throw new IOException("Failed to parse file.", e);
            }
        }
        return Collections.emptyList();
    }

    public static Path makeParents(Path file) throws IOException {
        if (Files.notExists(file.getParent())) {
            Files.createDirectories(file.getParent());
        }
        return file;
    }

    public static void delete(Path path) throws IOException {
        try (Stream<Path> stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(sneak(Files::delete));
        }
    }

    public static boolean contentEquals(Path a, Path b) throws IOException {
        if (Files.notExists(a) || Files.notExists(b)) return false;
        if (Files.size(a) != Files.size(b)) return false;
        HashCode aHash = HashUtils.hash(SHA256, a);
        HashCode bHash = HashUtils.hash(SHA256, b);
        return aHash.equals(bHash);
    }

    public static List<ClasspathEntry> parseManifestClasspath(Path zipFile) throws IOException {
        List<ClasspathEntry> classpathEntries = new ArrayList<>();
        //We load things with a ZipInputStream, as jar-in-jar zipfs is not supported.
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zipFile))) {
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
                    Collections.addAll(classPath, cp.split(" "));
                }
            }
            for (String s : classPath) {
                if (!s.startsWith("libraries/")) {
                    classpathEntries.add(new StringClasspathEntry(s));
                    continue;
                }
                s = s.substring(10);
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
                MavenNotation notation = new MavenNotation(gBuilder.toString(), module, version, classifer, extension);
                classpathEntries.add(new LibraryClasspathEntry(notation));
            }
        }
        return classpathEntries;
    }

    public static int runWaitFor(String logPrefix, Consumer<ProcessBuilder> configure) throws IOException {
        return runWaitFor(configure, e -> LOGGER.info("{}: {}", logPrefix, e));
    }

    public static int runWaitFor(Consumer<ProcessBuilder> configure, Consumer<String> consumer) throws IOException {
        ProcessBuilder procBuilder = new ProcessBuilder();
        configure.accept(procBuilder);
        procBuilder.redirectErrorStream(true);
        Process process = procBuilder.start();

        CompletableFuture<Void> stdoutFuture = processLines(process.getInputStream(), consumer);
        ProcessUtils.onExit(process).thenRunAsync(() -> {
            if (!stdoutFuture.isDone()) stdoutFuture.cancel(true);
        });
        try {
            process.waitFor();
        } catch (InterruptedException e) {
            LOGGER.warn("Interrupted.", e);
        }
        return process.exitValue();
    }

    public static CompletableFuture<Void> processLines(InputStream stream, Consumer<String> consumer) {
        return CompletableFuture.runAsync(sneak(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    consumer.accept(line);
                }
            }
        }));
    }

    public static void downloadFile(URL url, Path file) throws IOException {
        downloadFile(url, file, false);
    }

    private static final Set<String> recentFiles = new HashSet<>();
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

    public static byte[] toBytes(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[0x100];
        int len;
        while ((len = is.read(buf)) != -1)
            bos.write(buf, 0, len);
        return bos.toByteArray();
    }
}
