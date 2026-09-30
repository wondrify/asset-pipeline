/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package example;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.zip.GZIPInputStream;

public class Application {
    public static void main(String[] args) throws Exception {
        Properties manifest = new Properties();
        try (InputStream input = Application.class.getResourceAsStream("/assets/manifest.properties")) {
            if (input == null) {
                throw new IllegalStateException("Missing asset manifest");
            }
            manifest.load(input);
        }
        String asset = manifest.getProperty("nested/site.css");
        if (asset == null || asset.equals("nested/site.css")) {
            throw new IllegalStateException("Missing digested asset name");
        }
        String css;
        try (InputStream input = Application.class.getResourceAsStream("/assets/" + asset)) {
            css = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (!css.contains("#123456")) {
            throw new IllegalStateException("Incorrect asset contents");
        }
        try (InputStream input = new GZIPInputStream(Application.class.getResourceAsStream("/assets/" + asset + ".gz"))) {
            if (!css.equals(new String(input.readAllBytes(), StandardCharsets.UTF_8))) {
                throw new IllegalStateException("Incorrect compressed asset contents");
            }
        }
        System.out.println("ASSETS_OK");
    }
}
