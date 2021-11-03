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

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Created by covers1624 on 30/4/21.
 */
public enum InstallerFormat {
    V2,
    V1,
    ;
    private static final Logger LOGGER = LogManager.getLogger();

    public static InstallerFormat detectInstallerFormat(JarContents jar) {
        try (InputStream is = jar.getInput("install_profile.json")) {
            if (is == null)
                return null;

            @SuppressWarnings("unchecked")
            Map<String, ?> map = Utils.GSON.fromJson(new InputStreamReader(is), Map.class);

            return map.containsKey("install") && map.containsKey("versionInfo") ? V1 : V2;
        } catch (IOException e) {
            LOGGER.error("Failed to parse install_profile.json", e);
            return null;
        }
    }
}
