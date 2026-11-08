package com.bikeriding.mp.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;

import org.junit.Before;
import org.junit.Test;

/** Uses the synthetic graph from tools/make_test_graph.py. */
public class RouterTest {
    private Router router;

    @Before
    public void setUp() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("test.graph")) {
            assertNotNull("test.graph missing", in);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            router = new Router(new Graph(ByteBuffer.wrap(bo.toByteArray())));
        }
    }

    @Test
    public void graphLoaded() {
        Graph g = router.graph();
        // island road is removed → X, M, Y, V1?, ... only junctions/ends remain
        assertEquals(4, g.nodeCount);   // X, M, Y, S (V1/V2 are only shape points)
        assertTrue(g.edgeCount >= 4);
    }

    @Test
    public void defaultRouteUsesNH() throws Exception {
        Route r = router.route(22.999, 77.001, 23.001, 77.499, new RouteOptions());
        assertTrue("should mostly be NH, was " + r.nhM, r.nhM > 45000);
        assertEquals(0, r.expresswayM, 1);
        assertTrue(r.distanceM > 50000 && r.distanceM < 53000);
        assertTrue(r.summary().contains("NH46"));
    }

    @Test
    public void avoidNHUsesVillageRoad() throws Exception {
        RouteOptions o = new RouteOptions();
        o.avoidNH = true;
        Route r = router.route(22.999, 77.001, 23.001, 77.499, o);
        assertTrue("NH metres should be ~0, was " + r.nhM, r.nhM < 1000);
        assertTrue(r.mainRoads(3).contains("Gaon Road"));
        assertTrue(r.distanceM > 53000);
    }

    @Test
    public void expresswayOnlyWhenAllowed() throws Exception {
        RouteOptions o = new RouteOptions();
        o.avoidExpressway = false;
        o.avoidNH = true;
        Route r = router.route(22.999, 77.001, 23.001, 77.499, o);
        assertTrue("expressway expected, was " + r.expresswayM, r.expresswayM > 40000);
    }

    @Test
    public void onewaySpur() throws Exception {
        // spur M -> S is one-way: going S -> M must not drive the spur backwards...
        // ...but since it is the only road, the router starts on it in the allowed direction only.
        Route toS = router.route(23.0, 77.0, 22.951, 77.25, new RouteOptions());
        assertTrue(toS.mainRoads(3).contains("Spur Lane"));
        try {
            router.route(22.951, 77.25, 23.0, 77.0, new RouteOptions());
            // allowed only if it doesn't go backwards on the spur, which is impossible here
            throw new AssertionError("expected no route against one-way");
        } catch (Router.NoRouteException expected) {
            // ok
        }
    }

    @Test
    public void sameEdgeAndVias() throws Exception {
        Route r = router.route(23.0, 77.30, 23.0, 77.40, new RouteOptions());
        assertEquals(GeoUtil.distance(23, 77.3, 23, 77.4), r.distanceM, 50);
        Route v = router.route(new double[]{23.0, 23.085, 23.0}, new double[]{77.0, 77.25, 77.5}, new RouteOptions());
        assertTrue(v.mainRoads(3).contains("Gaon Road"));
        assertEquals(v.cumDist[v.size() - 1], v.distanceM, 200);
    }

    @Test
    public void snapFar() {
        assertNull(router.snap(26.0, 80.0, 5000));
        assertNotNull(router.snap(23.01, 77.2, 5000));
    }
}
