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
package net.minecraftforge.ir.json;

import net.covers1624.quack.maven.MavenNotation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

//Mostly copied from the Installer, except easily mutable.
public class Install {

    public List<String> _comment_;

    // Specification for this json format. Current known value is 0, or missing, This is for future use if we ever change the format/functionality of the installer..
    public int spec = 0;
    // Profile name to install and direct at this new version
    public String profile;
    // Version name to install to.
    public String version;
    // Icon to display in the list
    public String icon;
    // Vanilla version this is based off of.
    public String minecraft;
    // Version json to install into the client
    public String json;
    // Logo to be displayed on the installer GUI.
    public String logo;
    // Maven artifact path for the 'main' jar to install.
    public MavenNotation path;
    // Icon to use for the url button
    public String urlIcon;
    // Welcome message displayed on main install panel.
    public String welcome;
    // URL for mirror list, which needs to be a json file in the format of an array of Mirror
    public String mirrorList;
    //Hides an entry from the install UI
    public boolean hideClient = false;
    public boolean hideServer = false;
    public boolean hideExtract = false;
    // Extra libraries needed by processors, that may differ from the installer version's library list. Uses the same format as Mojang for simplicities sake.
    private List<Version.Library> libraries;
    // Executable jars to be run after all libraries have been downloaded.
    private List<Processor> processors;
    //Data files to be extracted during install, used for processor.
    private Map<String, DataFile> data;

    public List<Version.Library> getLibraries() {
        if (libraries == null) {
            libraries = new ArrayList<>();
        }
        return libraries;
    }

    public List<Processor> getProcessors() {
        if (processors == null) {
            processors = new ArrayList<>();
        }
        return processors;
    }

    public Map<String, DataFile> getData() {
        if (data == null) {
            data = new HashMap<>();
        }
        return data;
    }

    public static class Processor {

        // Which side this task is to be run on, Currently know sides are "client", "server" and "extract", if this omitted, assume all sides.
        private List<String> sides;
        // The executable jar to run, The installer will run it in-process, but external tools can run it using java -jar {file}, so MANFEST Main-Class entry must be valid.
        public MavenNotation jar;
        // Dependency list of files needed for this jar to run. Aything listed here SHOULD be listed in {@see Install#libraries} so the installer knows to download it.
        private List<MavenNotation> classpath;
        /*
         * Arguments to pass to the jar, can be in the following formats:
         * [Artifact] : A artifact path in the target maven style repo, where all libraries are downloaded to.
         * {DATA_ENTRY} : A entry in the Install#data map, extract as a file, there are a few extra specified values to allow the same processor to run on both sides:
         *   {MINECRAFT_JAR} - The vanilla minecraft jar we are dealing with, /versions/VERSION/VERSION.jar on the client and /minecraft_server.VERSION.jar for the server
         *   {SIDE} - Either the exact string "client", "server", and "extract" depending on what side we are installing.
         */
        private List<String> args;
        /*
         *  Files output from this task, used for verifying the process was successful, or if the task needs to be rerun.
         *  Keys are either a [Artifact] or {DATA_ENTRRY}, if it is a {DATA_ENTRY} then that MUST be a [Artifact]
         *  Values are either a {DATA_ENTRY} or 'value', if it is a {DATA_ENTRY} then that entry MUST be a quoted string literal
         *    The end string literal is the sha1 hash of the specified artifact.
         */
        private Map<String, String> outputs;

        public List<String> getSides() {
            if (sides == null) {
                sides = new ArrayList<>();
            }
            return sides;
        }

        public List<MavenNotation> getClasspath() {
            if (classpath == null) {
                classpath = new ArrayList<>();
            }
            return classpath;
        }

        public List<String> getArgs() {
            if (args == null) {
                args = new ArrayList<>();
            }
            return args;
        }

        public Map<String, String> getOutputs() {
            if (outputs == null) {
                outputs = new HashMap<>();
            }
            return outputs;
        }
    }

    public static class DataFile {

        /**
         * Can be in the following formats:
         * [value] - An absolute path to an artifact located in the target maven style repo.
         * 'value' - A string literal, remove the 's and use this value
         * value - A file in the installer package, to be extracted to a temp folder, and then have the absolute path in replacements.
         */
        // Value to use for the client install
        public String client;
        // Value to use for the server install
        public String server;
    }
}
