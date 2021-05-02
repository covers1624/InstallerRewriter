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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.covers1624.quack.maven.MavenNotation;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by covers1624 on 1/5/21.
 */
public class V1InstallProfile {

    public Install install;
    public VersionInfo versionInfo;

    public static class Install {
        public String filePath;
    }

    public static class VersionInfo {
        public String id;
        public String time;
        public String releaseTime;
        public String type;
        public String minecraftArguments;
        public String mainClass;
        public String inheritsFrom;

        public List<Library> libraries = new ArrayList<>();
        public JsonArray optionals;
    }

    public static class Library {
        public MavenNotation name;
        public String url;
        public List<String> checksums = new ArrayList<>();
        public Boolean serverreq;
        public Boolean clientreq;
        public JsonArray rules;
        public JsonObject natives;
        public JsonObject extract;
    }



}


