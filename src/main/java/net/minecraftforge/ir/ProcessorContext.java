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
import org.apache.commons.lang3.tuple.Pair;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Created by covers1624 on 22/5/21.
 */
public abstract class ProcessorContext {

    public final MavenNotation notation;
    public final MavenNotation installer;
    public final Path repoPath;

    public ProcessorContext(MavenNotation notation, MavenNotation installer, Path repoPath) {
        this.notation = notation;
        this.installer = installer;
        this.repoPath = repoPath;
    }

    /**
     * Gets a pair of files to process.
     *
     * @param notation The {@link MavenNotation} describing the file.
     * @return A Pair of paths. Old -> New
     */
    public abstract Pair<Path, Path> getFile(MavenNotation notation) throws IOException;

}
