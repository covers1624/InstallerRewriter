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

import com.google.gson.JsonObject;
import net.covers1624.quack.maven.MavenNotation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

//Mostly copied from the Installer, except easily mutable.
public class Version {

    public List<String> _comment_;

    public String id;
    public String time;
    public String releaseTime;
    public String type;
    public String mainClass;
    public String inheritsFrom;
    public JsonObject logging;
    public String minecraftArguments;
    public Map<String, Download> downloads;
    public List<Library> libraries;

    public Map<String, Download> getDownloads() {
        if (downloads == null) {
            downloads = new HashMap<>();
        }
        return downloads;
    }

    public List<Library> getLibraries() {
        if (libraries == null) {
            libraries = new ArrayList<>();
        }
        return libraries;
    }

    public static class Download {

        public String url;
        public String sha1;
        public Integer size;
    }

    public static class LibraryDownload extends Download {

        public String path;
    }

    public static class Library {

        public MavenNotation name;
        public Downloads downloads;
    }

    public static class Downloads {

        public LibraryDownload artifact;
        public Map<String, LibraryDownload> classifiers;

        public Map<String, LibraryDownload> getClassifiers() {
            if (classifiers == null) {
                classifiers = new HashMap<>();
            }
            return classifiers;
        }
    }
}
