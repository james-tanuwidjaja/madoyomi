package madoyomi.api;

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * A stand-in for mpv in PlaybackApiTest: records its arguments and its include file (which the backend deletes when
 * mpv exits) to {@code <tmp>/madoyomi-fake-mpv/<pipe name>.txt}, then waits to be killed (at most two minutes, so a
 * leaked one goes away). Java with no dependencies, so it starts from its own class directory, and the launch stays
 * far below Windows' command-line limit.
 */
public final class FakeMpv {
    public static void main(String[] args) throws Exception {
        record(args);
        Thread.sleep(120_000);
    }

    static void record(String[] args) throws Exception {
        String pipe = null;
        String include = null;
        StringBuilder out = new StringBuilder();
        for (String arg : args) {
            if (arg.startsWith("--input-ipc-server=")) pipe = arg.substring("--input-ipc-server=".length());
            if (arg.startsWith("--include=")) include = arg.substring("--include=".length());
            out.append("ARG ").append(arg).append('\n');
        }
        out.append("TOKEN ").append(System.getenv("MADOYOMI_TOKEN") != null).append('\n');
        out.append("INCLUDE\n").append(include == null ? "" : Files.readString(Path.of(include)));
        String name = pipe.substring(Math.max(pipe.lastIndexOf('\\'), pipe.lastIndexOf('/')) + 1);
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "madoyomi-fake-mpv");
        Files.createDirectories(dir);
        Path tmp = dir.resolve(name + ".tmp");
        Files.writeString(tmp, out);
        Files.move(tmp, dir.resolve(name + ".txt"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** mpv that exits by itself (a closed window, a failed URL), with code 3. */
    public static final class Exits {
        public static void main(String[] args) throws Exception {
            record(args);
            System.exit(3);
        }
    }
}
