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

import net.covers1624.quack.maven.MavenNotation;

/**
 * Represents a mutable expanded 'Class-Path' manifest entry.
 * <p>
 * Created by covers1624 on 22/5/21.
 *
 * @see Utils#parseManifestClasspath
 */
public interface ClasspathEntry {

    /**
     * @return If the entry has been modified.
     */
    boolean isModified();

    /**
     * Builds the entries path ready to be added to a manifest.
     *
     * @return The path.
     */
    String toPath();

    class LibraryClasspathEntry implements ClasspathEntry {

        public boolean modified;
        public MavenNotation notation;

        public LibraryClasspathEntry(MavenNotation notation) {
            this.notation = notation;
        }

        @Override
        public boolean isModified() {
            return modified;
        }

        @Override
        public String toPath() {
            return "libraries/" + notation.toPath();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            LibraryClasspathEntry that = (LibraryClasspathEntry) o;

            return notation.equals(that.notation);
        }

        @Override
        public int hashCode() {
            return notation.hashCode();
        }
    }

    class StringClasspathEntry implements ClasspathEntry {

        public boolean modified;
        public String path;

        public StringClasspathEntry(String path) {
            this.path = path;
        }

        @Override
        public boolean isModified() {
            return modified;
        }

        @Override
        public String toPath() {
            return path;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            StringClasspathEntry that = (StringClasspathEntry) o;

            return path.equals(that.path);
        }

        @Override
        public int hashCode() {
            return path.hashCode();
        }
    }
}
