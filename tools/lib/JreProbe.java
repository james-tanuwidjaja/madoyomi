// Checks a jlink runtime against what the server and extensions use at runtime, beyond what jdeps
// finds in the server jar. tools/jlink.ps1 compiles it with the JDK's javac (classpath: the server jar) and runs it on
// the new runtime: `java -cp <jar>;<dir> JreProbe`. Prints one line per check and exits 1 if any fails.
// No network: TLS is checked by its providers and cipher suites; the compat harness and the live spec do real fetches.
// Keep this file ASCII.

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import javax.imageio.ImageIO;
import javax.net.ssl.SSLContext;

public class JreProbe {
    private static int failures = 0;

    private static void check(String name, Callable<Object> body) {
        try {
            Object result = body.call();
            System.out.println("OK   " + name + (result == null ? "" : ": " + result));
        } catch (Throwable t) {
            failures++;
            System.out.println("FAIL " + name + ": " + t);
        }
    }

    private static void expect(boolean ok, String what) {
        if (!ok) throw new IllegalStateException(what);
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        check("JavaScript through the QuickJs stub (GraalJS)", () -> {
            try (app.cash.quickjs.QuickJs js = app.cash.quickjs.QuickJs.create()) {
                Object r = js.evaluate("[1, 2, 3].map(x => x * 2).join(',')");
                expect("2,4,6".equals(String.valueOf(r)), "got " + r);
                return r;
            }
        });
        // The Duktape stub asks javax.script for "rhino", which the jar doesn't register (on the JDK too: upstream's
        // state), so this checks javax.script itself, as the stub and H2 reach it.
        check("javax.script (java.scripting)", () -> {
            javax.script.ScriptEngineManager m = new javax.script.ScriptEngineManager();
            return m.getEngineFactories().size() + " engine factories";
        });
        check("dates in other locales (jdk.localedata)", () -> {
            String[][] cases = {
                {"es", "d 'de' MMMM 'de' yyyy", "5 de enero de 2024"},
                {"pt-BR", "d 'de' MMMM 'de' yyyy", "5 de janeiro de 2024"},
                {"fr", "d MMMM yyyy", "5 janvier 2024"},
                {"ru", "d MMMM yyyy", "5 \u044f\u043d\u0432\u0430\u0440\u044f 2024"},
                {"ja", "yyyy\u5e74M\u6708d\u65e5", "2024\u5e741\u67085\u65e5"},
                {"id", "d MMMM yyyy", "5 Januari 2024"},
                {"tr", "d MMMM yyyy", "5 Ocak 2024"},
            };
            for (String[] c : cases) {
                SimpleDateFormat f = new SimpleDateFormat(c[1], Locale.forLanguageTag(c[0]));
                SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
                String got = iso.format(f.parse(c[2]));
                expect("2024-01-05".equals(got), c[0] + " parsed as " + got);
            }
            return cases.length + " locales";
        });
        check("legacy charsets (jdk.charsets)", () -> {
            for (String cs : new String[] {"Shift_JIS", "EUC-JP", "EUC-KR", "GBK", "GB18030", "Big5", "windows-1251", "windows-1256", "KOI8-R"}) {
                expect(Charset.isSupported(cs), cs + " missing");
            }
            return "9 charsets";
        });
        check("zip file system (jdk.zipfs)", () -> {
            Path zip = Files.createTempFile("jreprobe", ".zip");
            Files.delete(zip);
            try (FileSystem fs = FileSystems.newFileSystem(URI.create("jar:" + zip.toUri()), Map.of("create", "true"))) {
                Files.writeString(fs.getPath("ComicInfo.xml"), "<ComicInfo/>");
            }
            try (FileSystem fs = FileSystems.newFileSystem(zip)) {
                expect(Files.readString(fs.getPath("ComicInfo.xml")).equals("<ComicInfo/>"), "read back");
            } finally {
                Files.deleteIfExists(zip);
            }
            return null;
        });
        check("TLS providers (jdk.crypto.ec)", () -> {
            SSLContext ctx = SSLContext.getInstance("TLSv1.3");
            ctx.init(null, null, null);
            String[] suites = ctx.getDefaultSSLParameters().getCipherSuites();
            expect(Arrays.stream(suites).anyMatch(s -> s.contains("ECDHE_ECDSA")), "no ECDHE_ECDSA suite");
            expect(java.security.Security.getProvider("SunEC") != null, "SunEC missing");
            java.security.KeyPairGenerator.getInstance("EC").generateKeyPair();
            return suites.length + " suites";
        });
        check("headless imaging (java.desktop)", () -> {
            BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
            img.setRGB(1, 1, 0xff8800);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            expect(ImageIO.write(img, "png", out), "no png writer");
            BufferedImage back = ImageIO.read(new ByteArrayInputStream(out.toByteArray()));
            expect((back.getRGB(1, 1) & 0xffffff) == 0xff8800, "pixel changed");
            return String.join(",", ImageIO.getReaderFormatNames()).length() > 0 ? "png round trip" : null;
        });
        check("preferences (java.prefs)", () -> {
            java.util.prefs.Preferences p = java.util.prefs.Preferences.userRoot().node("madoyomi-jreprobe");
            p.put("k", "v");
            String v = p.get("k", null);
            p.removeNode();
            expect("v".equals(v), "got " + v);
            return null;
        });
        check("java.net.http and the management beans", () -> {
            java.net.http.HttpClient.newHttpClient();
            com.sun.management.OperatingSystemMXBean os =
                (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            return os.getTotalMemorySize() / (1024 * 1024) + " MB RAM";
        });
        check("JDBC and JNDI (java.sql, java.naming)", () -> {
            Class.forName("org.h2.Driver");
            new javax.naming.InitialContext().getEnvironment();
            return null;
        });
        System.out.println(failures == 0 ? "PROBE OK" : "PROBE FAILED: " + failures);
        System.exit(failures == 0 ? 0 : 1);
    }
}
