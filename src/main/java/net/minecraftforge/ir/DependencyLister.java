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

import static net.minecraftforge.ir.InstallerRewriter.FORGE_MAVEN;
import static net.minecraftforge.ir.InstallerRewriter.OLD_FORGE_MAVEN;
import static net.minecraftforge.ir.Utils.getAsString;

import java.util.Set;

import com.google.gson.JsonObject;

import net.covers1624.quack.maven.MavenNotation;

class DependencyLister extends MavenUrlProcessor {
    private final Set<String> deps;
    DependencyLister(Set<String> deps) {
        this.deps = deps;
    }

    @Override
    protected boolean rewriteUrl(MavenNotation notation, JsonObject json) {
        if (!json.has("url"))
            return false;

        String url = getAsString(json, "url");
        if (url == null || url.isEmpty())
            return false;

        if (url.startsWith("http://"))
            url = "https://" + url.substring(7);

        if (url.startsWith(OLD_FORGE_MAVEN))
            url = FORGE_MAVEN + url.substring(OLD_FORGE_MAVEN.length());

        if (url.startsWith(FORGE_MAVEN)) {
            if ("https://maven.minecraftforge.net/".equals(url)) {
                String name = getAsString(json, "name");

                if (!name.startsWith("net.minecraftforge:forge:") &&
                    !name.startsWith("net.minecraftforge:minecraftforge:"))
                    deps.add(name);
            } else {
                String path = url.substring(FORGE_MAVEN.length());
                int idx = path.lastIndexOf('/');
                path = path.substring(0, idx);

                idx = path.lastIndexOf('/');
                String ver = path.substring(idx + 1);
                path = path.substring(0, idx);

                idx = path.lastIndexOf('/');
                String name = path.substring(idx + 1);
                String group = path.substring(0, idx).replace('/', '.');
                deps.add(group + ':' + name + ':' + ver);
            }
        }

        return false;
    }

    @Override
    protected void writeProfile(JarContents content, JsonObject install, MavenNotation notation) {}
    @Override
    protected void writeVersion(JarContents content, String name, JsonObject version) {}
}
