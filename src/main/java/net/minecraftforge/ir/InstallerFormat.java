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

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Created by covers1624 on 30/4/21.
 */
public enum InstallerFormat {
    V2,
    V1,
    ;

    public static InstallerFormat detectInstallerFormat(Path jarRoot) {
        if (Files.exists(jarRoot.resolve("install_profile.json"))) {
            if (Files.exists(jarRoot.resolve("version.json"))) {
                return V2;
            }
            return V1;
        }
        return null;
    }
}
