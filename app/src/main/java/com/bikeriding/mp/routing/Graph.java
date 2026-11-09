package com.bikeriding.mp.routing;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;

/**
 * Read-only routing graph, memory-mapped from mp.graph (written by tools/graphlib.py).
 * Pure Java so it can be unit-tested on a PC.
 */
public final class Graph {
    public static final int MAGIC = 0x4752504D;

    // road classes
    public static final int C_MOTORWAY = 0, C_TRUNK = 1, C_PRIMARY = 2, C_SECONDARY = 3, C_TERTIARY = 4,
            C_UNCLASSIFIED = 5, C_RESIDENTIAL = 6, C_SERVICE = 7, C_TRACK = 8;

    // flags
    public static final int F_NH = 1, F_SH = 2, F_TOLL = 4, F_UNPAVED = 8, F_EXPRESSWAY = 16, F_LINK = 32;
    // second flag byte (v2), returned shifted by 8 from edgeFlags()
    public static final int F_FORD = 256, F_URBAN = 512;

    public final int nodeCount, edgeCount, shapeCount;
    final IntBuffer nodeLat, nodeLon, adjStart, adj, edgeA, edgeB, edgeName, edgeShapeStart;
    final FloatBuffer edgeLen;
    final ByteBuffer edgeClass, edgeFlags, edgeSpeed, edgeCurv, edgeFlags2;
    final IntBuffer shapeLat, shapeLon, gridStart, gridEdges, nameOffsets;
    final ByteBuffer nameBytes;
    final int gridRows, gridCols, gridMinLat, gridMinLon, cellSize;
    final int maxSpeedKmh;
    private final String[] nameCache;

    public static Graph open(File file) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            FileChannel ch = raf.getChannel();
            return new Graph(ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()));
        }
    }

    /** Maps a region of a file, e.g. an uncompressed asset inside the APK. */
    public static Graph open(FileInputStream in, long offset, long length) throws IOException {
        FileChannel ch = in.getChannel();
        return new Graph(ch.map(FileChannel.MapMode.READ_ONLY, offset, length));
    }

    public Graph(ByteBuffer buffer) {
        ByteBuffer b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int[] h = new int[16];
        for (int i = 0; i < 16; i++) h[i] = b.getInt(i * 4);
        if (h[0] != MAGIC) throw new IllegalArgumentException("not a routing graph file");
        if (h[1] != 1 && h[1] != 2) throw new IllegalArgumentException("unsupported graph version " + h[1]);
        boolean v2 = h[1] >= 2;
        nodeCount = h[2];
        edgeCount = h[3];
        shapeCount = h[4];
        int adjCount = h[5];
        gridRows = h[6];
        gridCols = h[7];
        gridMinLat = h[8];
        gridMinLon = h[9];
        cellSize = h[10];
        int gridCount = h[11];
        int nameCount = h[12];
        int nameLen = h[13];

        Cursor c = new Cursor(b, 64);
        nodeLat = c.ints(nodeCount);
        nodeLon = c.ints(nodeCount);
        adjStart = c.ints(nodeCount + 1);
        adj = c.ints(adjCount);
        edgeA = c.ints(edgeCount);
        edgeB = c.ints(edgeCount);
        edgeLen = c.floats(edgeCount);
        edgeName = c.ints(edgeCount);
        edgeShapeStart = c.ints(edgeCount + 1);
        edgeClass = c.bytes(edgeCount);
        edgeFlags = c.bytes(edgeCount);
        edgeSpeed = c.bytes(edgeCount);
        edgeCurv = v2 ? c.bytes(edgeCount) : ByteBuffer.allocate(edgeCount);
        edgeFlags2 = v2 ? c.bytes(edgeCount) : ByteBuffer.allocate(edgeCount);
        shapeLat = c.ints(shapeCount);
        shapeLon = c.ints(shapeCount);
        gridStart = c.ints(gridRows * gridCols + 1);
        gridEdges = c.ints(gridCount);
        nameOffsets = c.ints(nameCount + 1);
        nameBytes = c.bytes(nameLen);
        nameCache = new String[nameCount];

        int max = 10;
        for (int e = 0; e < edgeCount; e++) max = Math.max(max, edgeSpeed.get(e) & 0xff);
        maxSpeedKmh = max;
    }

    private static final class Cursor {
        final ByteBuffer b;
        int pos;

        Cursor(ByteBuffer b, int pos) {
            this.b = b;
            this.pos = pos;
        }

        private ByteBuffer slice(int bytes) {
            ByteBuffer d = b.duplicate();
            d.limit(pos + bytes);
            d.position(pos);
            ByteBuffer s = d.slice().order(ByteOrder.LITTLE_ENDIAN);
            pos += (bytes + 3) & ~3;
            return s;
        }

        IntBuffer ints(int n) {
            return slice(n * 4).asIntBuffer();
        }

        FloatBuffer floats(int n) {
            return slice(n * 4).asFloatBuffer();
        }

        ByteBuffer bytes(int n) {
            return slice(n);
        }
    }

    public double nodeLat(int n) { return nodeLat.get(n) / 1e6; }
    public double nodeLon(int n) { return nodeLon.get(n) / 1e6; }
    public int edgeA(int e) { return edgeA.get(e); }
    public int edgeB(int e) { return edgeB.get(e); }
    public double edgeLength(int e) { return edgeLen.get(e); }
    public int edgeClass(int e) { return edgeClass.get(e); }
    public int edgeFlags(int e) { return (edgeFlags.get(e) & 0xff) | ((edgeFlags2.get(e) & 0xff) << 8); }
    /** curviness in degrees turned per km */
    public int edgeCurvDegPerKm(int e) { return (edgeCurv.get(e) & 0xff) * 2; }
    public int edgeSpeedKmh(int e) { return edgeSpeed.get(e) & 0xff; }
    public int shapeStart(int e) { return edgeShapeStart.get(e); }
    public int shapeEnd(int e) { return edgeShapeStart.get(e + 1); }
    public double shapeLat(int i) { return shapeLat.get(i) / 1e6; }
    public double shapeLon(int i) { return shapeLon.get(i) / 1e6; }

    public String edgeName(int e) {
        int idx = edgeName.get(e);
        if (idx < 0) return null;
        String s = nameCache[idx];
        if (s == null) {
            int a = nameOffsets.get(idx), z = nameOffsets.get(idx + 1);
            byte[] tmp = new byte[z - a];
            for (int i = 0; i < tmp.length; i++) tmp[i] = nameBytes.get(a + i);
            s = new String(tmp, StandardCharsets.UTF_8);
            nameCache[idx] = s;
        }
        return s;
    }

    /** true if the edge may be driven in that direction (dir 0 = A to B, 1 = B to A). */
    public boolean allowed(int e, int dir) {
        int from = dir == 0 ? edgeA.get(e) : edgeB.get(e);
        int want = e * 2 + dir;
        for (int i = adjStart.get(from), z = adjStart.get(from + 1); i < z; i++) {
            if (adj.get(i) == want) return true;
        }
        return false;
    }

    public boolean contains(double lat, double lon) {
        int la = (int) Math.round(lat * 1e6), lo = (int) Math.round(lon * 1e6);
        return la >= gridMinLat && lo >= gridMinLon
                && la < gridMinLat + (long) gridRows * cellSize && lo < gridMinLon + (long) gridCols * cellSize;
    }
}
