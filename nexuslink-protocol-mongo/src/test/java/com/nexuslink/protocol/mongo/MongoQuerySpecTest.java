package com.nexuslink.protocol.mongo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MongoQuerySpecTest {

    @Test
    void nullsBecomeBlanksSoNothingIsApplied() {
        MongoQuerySpec spec = new MongoQuerySpec(null, null, null, 0, 20);
        assertEquals("", spec.filter());
        assertEquals("", spec.projection());
        assertEquals("", spec.sort());
        assertTrue(spec.isPlain());
    }

    @Test
    void theLimitIsAlwaysSaneAndTheSkipNeverNegative() {
        assertEquals(50, new MongoQuerySpec("", "", "", 0, 0).limit(), "0 means the default, not nothing");
        assertEquals(50, new MongoQuerySpec("", "", "", 0, -5).limit());
        assertEquals(10_000, new MongoQuerySpec("", "", "", 0, 99_999).limit());
        assertEquals(0, new MongoQuerySpec("", "", "", -10, 20).skip());
    }

    @Test
    void pagingMovesBySkipAndStopsAtTheStart() {
        MongoQuerySpec page1 = new MongoQuerySpec("{}", "", "", 0, 20);
        MongoQuerySpec page2 = page1.nextPage();
        assertEquals(20, page2.skip());
        assertEquals(40, page2.nextPage().skip());
        assertEquals(0, page2.previousPage().skip());
        assertEquals(0, page1.previousPage().skip(), "the first page has nothing before it");
    }

    @Test
    void changingTheFilterKeepsTheRestOfTheBar() {
        MongoQuerySpec spec = new MongoQuerySpec("{}", "{\"name\":1}", "{\"name\":1}", 10, 20)
                .withFilter("{\"role\":\"admin\"}");
        assertEquals("{\"role\":\"admin\"}", spec.filter());
        assertEquals("{\"name\":1}", spec.projection());
        assertEquals(10, spec.skip());
    }

    @Test
    void aQueryWithOnlyALimitIsPlain() {
        assertTrue(MongoQuerySpec.all(50).isPlain());
        assertFalse(new MongoQuerySpec("{\"a\":1}", "", "", 0, 50).isPlain());
        assertFalse(new MongoQuerySpec("", "", "", 5, 50).isPlain());
    }

    @Test
    void theShellRenderingMatchesWhatSomeoneWouldType() {
        MongoQuerySpec spec = new MongoQuerySpec("{\"role\":\"admin\"}", "{\"name\":1}",
                "{\"name\":-1}", 20, 10);
        assertEquals("db.people.find({\"role\":\"admin\"}, {\"name\":1})"
                + ".sort({\"name\":-1}).skip(20).limit(10)", spec.toShell("people"));
    }

    @Test
    void aPlainQueryStillRendersValidShell() {
        assertEquals("db.people.find({}).limit(50)", MongoQuerySpec.all(50).toShell("people"));
        assertEquals("db.collection.find({}).limit(50)", MongoQuerySpec.all(50).toShell(""));
    }

    @Test
    void theSavedQueryLabelNamesWhatIsSet() {
        String label = new MongoQuerySpec("{\"role\":\"admin\"}", "{\"n\":1}", "{\"n\":1}", 5, 25).label();
        assertTrue(label.startsWith("{\"role\":\"admin\"}"), label);
        assertTrue(label.contains("projection"));
        assertTrue(label.contains("sort"));
        assertTrue(label.contains("skip 5"));
        assertTrue(label.contains("limit 25"));
    }

    @Test
    void aLongFilterIsTruncatedInTheLabel() {
        String longFilter = "{\"a\":\"" + "x".repeat(200) + "\"}";
        assertTrue(new MongoQuerySpec(longFilter, "", "", 0, 10).label().length() < 80);
    }
}
