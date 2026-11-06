/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Why is an extension class loader still alive? Reads a HotSpot heap dump (`jcmd <pid> GC.heap_dump <file>`) and prints,
 * for every live instance of a class loader class (default: `ChildFirstURLClassLoader`), the shortest chain of strong
 * references from a GC root to it. `Reference.referent` is not followed, so a loader that only a weak or soft reference
 * reaches is listed as such. Madoyomi Phase 2 Stage 6 (the uninstall leak); no dependencies, a JDK 21 single-file
 * program:
 *
 * <pre>java -Xmx3g tools/compat-test/lib/HprofLoaderRoots.java heap.hprof [loader class simple name] [max path length]</pre>
 */
public class HprofLoaderRoots {
    // kinds stored in the top bits of an object's file position
    private static final long KIND_INSTANCE = 1L << 60;
    private static final long KIND_OBJ_ARRAY = 2L << 60;
    private static final long KIND_PRIM_ARRAY = 3L << 60;
    private static final long KIND_MASK = 3L << 60;

    // edge labels that are not field names
    private static final long EDGE_CLASS = -1;
    private static final long EDGE_LOADER = -2;
    private static final long EDGE_DOMAIN = -3;
    private static final long EDGE_SIGNERS = -4;
    private static final long EDGE_ROOT = -5;
    private static final long EDGE_ARRAY = Long.MIN_VALUE; // + index

    private final Buf buf;
    private int idSize;
    private final Map<Long, String> strings = new HashMap<>();
    private final Map<Long, Long> classNameIds = new HashMap<>();
    private final Map<Long, ClassInfo> classes = new HashMap<>();
    private final LongLongMap objects = new LongLongMap(1 << 22);
    private final List<long[]> roots = new ArrayList<>(); // {kind, id, threadSerial}
    private final Map<Long, Long> threadBySerial = new HashMap<>();

    private record Field(long nameId, int type, long value) {}

    private static final class ClassInfo {
        long id;
        long superId;
        long loaderId;
        long signersId;
        long domainId;
        final List<Field> statics = new ArrayList<>();
        final List<Field> fields = new ArrayList<>();
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("usage: java HprofLoaderRoots.java <heap.hprof> [loader class simple name] [max path length]");
            System.exit(2);
        }
        String loaderClass = args.length > 1 ? args[1] : "ChildFirstURLClassLoader";
        int maxPath = args.length > 2 ? Integer.parseInt(args[2]) : 40;
        new HprofLoaderRoots(args[0]).run(loaderClass, maxPath);
    }

    private HprofLoaderRoots(String path) throws IOException {
        buf = new Buf(path);
    }

    // ---- reading

    private void index() {
        long p = 0;
        while (buf.get(p) != 0) p++;
        p++;
        idSize = buf.u4(p);
        p += 4 + 8;
        while (p < buf.size) {
            int tag = buf.u1(p);
            long length = buf.u4(p + 5) & 0xffffffffL;
            long body = p + 9;
            switch (tag) {
                case 0x01 -> strings.put(id(body), buf.utf8(body + idSize, (int) length - idSize));
                case 0x02 -> classNameIds.put(id(body + 4), id(body + 4 + idSize + 4));
                case 0x0C, 0x1C -> heapDump(body, body + length);
                default -> {}
            }
            p = body + length;
        }
    }

    private void heapDump(long p, long end) {
        while (p < end) {
            int tag = buf.u1(p++);
            switch (tag) {
                case 0xFF -> { root(tag, id(p), -1); p += idSize; }
                case 0x01 -> { root(tag, id(p), -1); p += 2L * idSize; }
                case 0x02 -> { root(tag, id(p), buf.u4(p + idSize)); p += idSize + 8; }
                case 0x03 -> { root(tag, id(p), buf.u4(p + idSize)); p += idSize + 8; }
                case 0x04 -> { root(tag, id(p), buf.u4(p + idSize)); p += idSize + 4; }
                case 0x05 -> { root(tag, id(p), -1); p += idSize; }
                case 0x06 -> { root(tag, id(p), buf.u4(p + idSize)); p += idSize + 4; }
                case 0x07 -> { root(tag, id(p), -1); p += idSize; }
                case 0x08 -> {
                    long thread = id(p);
                    threadBySerial.put((long) buf.u4(p + idSize), thread);
                    root(tag, thread, buf.u4(p + idSize));
                    p += idSize + 8;
                }
                case 0x20 -> p = classDump(p);
                case 0x21 -> {
                    objects.put(id(p), (p - 1) | KIND_INSTANCE);
                    p += idSize + 4 + idSize;
                    p += 4 + (buf.u4(p) & 0xffffffffL);
                }
                case 0x22 -> {
                    objects.put(id(p), (p - 1) | KIND_OBJ_ARRAY);
                    long n = buf.u4(p + idSize + 4) & 0xffffffffL;
                    p += idSize + 8 + idSize + n * idSize;
                }
                case 0x23 -> {
                    objects.put(id(p), (p - 1) | KIND_PRIM_ARRAY);
                    long n = buf.u4(p + idSize + 4) & 0xffffffffL;
                    int type = buf.u1(p + idSize + 8);
                    p += idSize + 9 + n * size(type);
                }
                default -> throw new IllegalStateException("unknown heap dump tag 0x" + Integer.toHexString(tag) + " at " + (p - 1));
            }
        }
    }

    private long classDump(long p) {
        ClassInfo c = new ClassInfo();
        c.id = id(p);
        p += idSize + 4;
        c.superId = id(p);
        c.loaderId = id(p + idSize);
        c.signersId = id(p + 2L * idSize);
        c.domainId = id(p + 3L * idSize);
        p += 6L * idSize + 4;
        int cp = buf.u2(p);
        p += 2;
        for (int i = 0; i < cp; i++) {
            int type = buf.u1(p + 2);
            p += 3 + size(type);
        }
        int statics = buf.u2(p);
        p += 2;
        for (int i = 0; i < statics; i++) {
            long name = id(p);
            int type = buf.u1(p + idSize);
            long value = type == 2 ? id(p + idSize + 1) : 0;
            c.statics.add(new Field(name, type, value));
            p += idSize + 1 + size(type);
        }
        int fields = buf.u2(p);
        p += 2;
        for (int i = 0; i < fields; i++) {
            c.fields.add(new Field(id(p), buf.u1(p + idSize), 0));
            p += idSize + 1;
        }
        classes.put(c.id, c);
        return p;
    }

    private void root(int kind, long id, long serial) {
        if (id != 0) roots.add(new long[] {kind, id, serial});
    }

    private long id(long p) {
        return idSize == 8 ? buf.u8(p) : buf.u4(p) & 0xffffffffL;
    }

    private int size(int type) {
        return switch (type) {
            case 2 -> idSize;
            case 4, 8 -> 1;
            case 5, 9 -> 2;
            case 6, 10 -> 4;
            case 7, 11 -> 8;
            default -> throw new IllegalStateException("type " + type);
        };
    }

    // ---- the graph

    private interface Edges {
        void edge(long target, long label);
    }

    private void edges(long id, Edges out) {
        ClassInfo cls = classes.get(id);
        if (cls != null) {
            out.edge(cls.loaderId, EDGE_LOADER);
            out.edge(cls.domainId, EDGE_DOMAIN);
            out.edge(cls.signersId, EDGE_SIGNERS);
            for (Field f : cls.statics) if (f.type == 2) out.edge(f.value, f.nameId);
            return;
        }
        long pos = objects.get(id);
        if (pos == LongLongMap.MISSING) return;
        long kind = pos & KIND_MASK;
        long p = (pos & ~KIND_MASK) + 1;
        if (kind == KIND_INSTANCE) {
            long classId = id(p + idSize + 4);
            out.edge(classId, EDGE_CLASS);
            long q = p + idSize + 4 + idSize + 4;
            boolean reference = isReference(classId);
            for (ClassInfo c = classes.get(classId); c != null; c = classes.get(c.superId)) {
                for (Field f : c.fields) {
                    if (f.type == 2) {
                        long target = id(q);
                        if (!(reference && "referent".equals(strings.get(f.nameId)))) out.edge(target, f.nameId);
                    }
                    q += size(f.type);
                }
            }
        } else if (kind == KIND_OBJ_ARRAY) {
            long n = buf.u4(p + idSize + 4) & 0xffffffffL;
            long q = p + idSize + 8 + idSize;
            for (long i = 0; i < n; i++) out.edge(id(q + i * idSize), EDGE_ARRAY + i);
        }
    }

    private final Map<Long, Boolean> referenceClasses = new HashMap<>();

    private boolean isReference(long classId) {
        return referenceClasses.computeIfAbsent(classId, k -> {
            for (ClassInfo c = classes.get(k); c != null; c = classes.get(c.superId)) {
                if ("java.lang.ref.Reference".equals(className(c.id))) return true;
            }
            return false;
        });
    }

    private String className(long classId) {
        Long nameId = classNameIds.get(classId);
        String name = nameId == null ? null : strings.get(nameId);
        return name == null ? "class@" + Long.toHexString(classId) : name.replace('/', '.');
    }

    private long classOf(long id) {
        long pos = objects.get(id);
        if (pos == LongLongMap.MISSING) return 0;
        long p = (pos & ~KIND_MASK) + 1;
        long kind = pos & KIND_MASK;
        if (kind == KIND_INSTANCE) return id(p + idSize + 4);
        if (kind == KIND_OBJ_ARRAY) return id(p + idSize + 8);
        return 0;
    }

    private String describe(long id) {
        if (classes.containsKey(id)) return "class " + className(id);
        long pos = objects.get(id);
        if (pos == LongLongMap.MISSING) return "?@" + Long.toHexString(id);
        long kind = pos & KIND_MASK;
        if (kind == KIND_PRIM_ARRAY) return "primitive[]";
        String name = className(classOf(id));
        if (kind == KIND_INSTANCE && isThread(classOf(id))) {
            String threadName = threadName(id);
            if (threadName != null) return name + " \"" + threadName + "\"";
        }
        return name;
    }

    private boolean isThread(long classId) {
        for (ClassInfo c = classes.get(classId); c != null; c = classes.get(c.superId)) {
            if ("java.lang.Thread".equals(className(c.id))) return true;
        }
        return false;
    }

    /** An instance field's value by name (first match up the hierarchy), or 0. */
    private long field(long id, String name) {
        long pos = objects.get(id);
        if (pos == LongLongMap.MISSING || (pos & KIND_MASK) != KIND_INSTANCE) return 0;
        long p = (pos & ~KIND_MASK) + 1;
        long q = p + idSize + 4 + idSize + 4;
        for (ClassInfo c = classes.get(id(p + idSize + 4)); c != null; c = classes.get(c.superId)) {
            for (Field f : c.fields) {
                if (f.type == 2 && name.equals(strings.get(f.nameId))) return id(q);
                q += size(f.type);
            }
        }
        return 0;
    }

    private String threadName(long thread) {
        long s = field(thread, "name");
        long value = s == 0 ? 0 : field(s, "value");
        if (value == 0) return null;
        long pos = objects.get(value);
        if (pos == LongLongMap.MISSING || (pos & KIND_MASK) != KIND_PRIM_ARRAY) return null;
        long p = (pos & ~KIND_MASK) + 1;
        int n = buf.u4(p + idSize + 4);
        int type = buf.u1(p + idSize + 8);
        if (type != 8) return null;
        byte[] bytes = new byte[Math.min(n, 200)];
        for (int i = 0; i < bytes.length; i++) bytes[i] = buf.get(p + idSize + 9 + i);
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    private String label(long label) {
        if (label == EDGE_CLASS) return "<class>";
        if (label == EDGE_LOADER) return "<loader>";
        if (label == EDGE_DOMAIN) return "<protection domain>";
        if (label == EDGE_SIGNERS) return "<signers>";
        if (label == EDGE_ROOT) return "<root>";
        if (label < EDGE_ROOT && label - EDGE_ARRAY >= 0) return "[" + (label - EDGE_ARRAY) + "]";
        String s = strings.get(label);
        return s == null ? "?" : s;
    }

    /** A JDK object or class (an array of JDK objects too): not where a leak is fixed, so not named as the holder. */
    private static boolean isJdk(String described) {
        String d = described.startsWith("class ") ? described.substring(6) : described;
        if (d.startsWith("[L")) d = d.substring(2);
        while (d.startsWith("[")) d = d.substring(1);
        return d.startsWith("java.") || d.startsWith("jdk.") || d.startsWith("sun.") || d.startsWith("com.sun.")
            || d.equals("primitive[]");
    }

    private static String rootKind(int kind) {
        return switch (kind) {
            case 0xFF -> "unknown";
            case 0x01 -> "JNI global";
            case 0x02 -> "JNI local";
            case 0x03 -> "Java frame";
            case 0x04 -> "native stack";
            case 0x05 -> "system class";
            case 0x06 -> "thread block";
            case 0x07 -> "monitor";
            case 0x08 -> "thread";
            default -> "root " + kind;
        };
    }

    // ---- the search

    private void run(String loaderClass, int maxPath) {
        long started = System.currentTimeMillis();
        index();
        List<Long> loaders = new ArrayList<>();
        for (long[] e : objects.entries()) {
            if ((e[1] & KIND_MASK) != KIND_INSTANCE) continue;
            String name = className(classOf(e[0]));
            if (name.equals(loaderClass) || name.endsWith("." + loaderClass) || name.endsWith("$" + loaderClass)) loaders.add(e[0]);
        }
        System.out.printf("%s: %d objects, %d classes, %d roots; %d live %s (%d ms)%n", buf.path, objects.size(), classes.size(),
            roots.size(), loaders.size(), loaderClass, System.currentTimeMillis() - started);
        if (loaders.isEmpty()) return;

        // which classes each loader defined (to name the extension)
        Map<Long, List<String>> defined = new HashMap<>();
        for (ClassInfo c : classes.values()) {
            if (loaders.contains(c.loaderId)) defined.computeIfAbsent(c.loaderId, k -> new ArrayList<>()).add(className(c.id));
        }

        LongLongMap parent = new LongLongMap(1 << 22);
        LongLongMap via = new LongLongMap(1 << 22);
        Map<Long, long[]> rootOf = new HashMap<>();
        LongQueue queue = new LongQueue();
        for (long[] r : roots) {
            if (parent.get(r[1]) != LongLongMap.MISSING) continue;
            parent.put(r[1], 0);
            via.put(r[1], EDGE_ROOT);
            rootOf.put(r[1], r);
            queue.add(r[1]);
        }
        while (!queue.isEmpty()) {
            long id = queue.poll();
            edges(id, (target, label) -> {
                if (target == 0 || parent.get(target) != LongLongMap.MISSING) return;
                if (!classes.containsKey(target) && objects.get(target) == LongLongMap.MISSING) return;
                parent.put(target, id);
                via.put(target, label);
                queue.add(target);
            });
        }

        Map<String, Integer> holders = new LinkedHashMap<>();
        for (long loader : loaders) {
            List<String> names = defined.getOrDefault(loader, List.of());
            String sample = names.stream().filter(n -> n.startsWith("eu.kanade.tachiyomi.")).findFirst()
                .orElse(names.isEmpty() ? "no classes" : names.get(0));
            System.out.printf("%nloader @%x (%d classes, e.g. %s)%n", loader, names.size(), sample);
            if (parent.get(loader) == LongLongMap.MISSING) {
                System.out.println("  not strongly reachable (only through a weak/soft/phantom reference or unreachable garbage)");
                holders.merge("(not strongly reachable)", 1, Integer::sum);
                continue;
            }
            List<String> path = new ArrayList<>();
            long cur = loader;
            String holder = null;
            while (true) {
                long p = parent.get(cur);
                long label = via.get(cur);
                if (label == EDGE_ROOT) {
                    long[] r = rootOf.get(cur);
                    String thread = "";
                    if (r[2] >= 0 && threadBySerial.containsKey(r[2])) thread = " of " + describe(threadBySerial.get(r[2]));
                    path.add(0, "root (" + rootKind((int) r[0]) + thread + "): " + describe(cur));
                    break;
                }
                path.add(0, "  ." + label(label) + " -> " + describe(cur));
                String d = describe(p);
                if (holder == null && !isJdk(d)) {
                    holder = d + "." + label(label);
                }
                cur = p;
            }
            int from = Math.max(0, path.size() - maxPath);
            if (from > 0) System.out.println("  (" + from + " steps from the root left out)");
            for (int i = from; i < path.size(); i++) System.out.println("  " + path.get(i));
            String rootLine = path.get(0);
            holders.merge(holder != null ? holder : rootLine, 1, Integer::sum);
        }
        System.out.printf("%nnearest non-JDK holder per loader (or the root, when the path has none):%n");
        holders.forEach((k, v) -> System.out.printf("  %3d  %s%n", v, k));
    }

    // ---- small helpers

    private static final class Buf {
        final String path;
        final long size;
        private final MappedByteBuffer[] parts;
        private static final int PART = 1 << 30;

        Buf(String path) throws IOException {
            this.path = path;
            try (RandomAccessFile f = new RandomAccessFile(path, "r"); FileChannel ch = f.getChannel()) {
                size = ch.size();
                int n = (int) ((size + PART - 1) / PART);
                parts = new MappedByteBuffer[n];
                for (int i = 0; i < n; i++) {
                    long start = (long) i * PART;
                    parts[i] = ch.map(FileChannel.MapMode.READ_ONLY, start, Math.min(PART, size - start));
                }
            }
        }

        byte get(long p) {
            return parts[(int) (p / PART)].get((int) (p % PART));
        }

        int u1(long p) {
            return get(p) & 0xff;
        }

        int u2(long p) {
            return (u1(p) << 8) | u1(p + 1);
        }

        int u4(long p) {
            return (u1(p) << 24) | (u1(p + 1) << 16) | (u1(p + 2) << 8) | u1(p + 3);
        }

        long u8(long p) {
            return ((long) u4(p) << 32) | (u4(p + 4) & 0xffffffffL);
        }

        String utf8(long p, int n) {
            byte[] b = new byte[n];
            for (int i = 0; i < n; i++) b[i] = get(p + i);
            return new String(b, StandardCharsets.UTF_8);
        }
    }

    /** Open addressing, long keys (never 0) to long values. */
    private static final class LongLongMap {
        static final long MISSING = Long.MIN_VALUE + 1;
        private long[] keys;
        private long[] values;
        private int count;

        LongLongMap(int capacity) {
            keys = new long[capacity];
            values = new long[capacity];
        }

        int size() {
            return count;
        }

        private static int hash(long k, int mask) {
            k ^= k >>> 33;
            k *= 0xff51afd7ed558ccdL;
            k ^= k >>> 33;
            return (int) k & mask;
        }

        void put(long k, long v) {
            if (count * 2 >= keys.length) grow();
            int mask = keys.length - 1;
            int i = hash(k, mask);
            while (keys[i] != 0 && keys[i] != k) i = (i + 1) & mask;
            if (keys[i] == 0) count++;
            keys[i] = k;
            values[i] = v;
        }

        long get(long k) {
            int mask = keys.length - 1;
            int i = hash(k, mask);
            while (keys[i] != 0) {
                if (keys[i] == k) return values[i];
                i = (i + 1) & mask;
            }
            return MISSING;
        }

        private void grow() {
            long[] ok = keys;
            long[] ov = values;
            keys = new long[ok.length * 2];
            values = new long[ok.length * 2];
            count = 0;
            for (int i = 0; i < ok.length; i++) if (ok[i] != 0) put(ok[i], ov[i]);
        }

        List<long[]> entries() {
            List<long[]> out = new ArrayList<>(count);
            for (int i = 0; i < keys.length; i++) if (keys[i] != 0) out.add(new long[] {keys[i], values[i]});
            return out;
        }
    }

    private static final class LongQueue {
        private long[] items = new long[1 << 16];
        private int head;
        private int tail;

        void add(long v) {
            if (tail == items.length) {
                if (head > items.length / 2) {
                    System.arraycopy(items, head, items, 0, tail - head);
                    tail -= head;
                    head = 0;
                } else {
                    long[] n = new long[items.length * 2];
                    System.arraycopy(items, head, n, 0, tail - head);
                    tail -= head;
                    head = 0;
                    items = n;
                }
            }
            items[tail++] = v;
        }

        long poll() {
            return items[head++];
        }

        boolean isEmpty() {
            return head == tail;
        }
    }
}
