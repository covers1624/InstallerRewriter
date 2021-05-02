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

import java.io.IOException;
import java.nio.file.Path;

/**
 * Created by covers1624 on 1/5/21.
 */
public interface InstallerProcessor {

    /**
     * Processes a given installer into the latest format.
     *
     * @param notation The {@link MavenNotation} for the installer being converted.
     * @param repoPath          The forge maven repository.
     * @param newInstaller      The new installer location.
     * @param oldJarRoot        The root Path inside the old Launcher.
     */
    void process(MavenNotation notation, Path repoPath, Path newInstaller, Path oldJarRoot) throws IOException;
}
