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
