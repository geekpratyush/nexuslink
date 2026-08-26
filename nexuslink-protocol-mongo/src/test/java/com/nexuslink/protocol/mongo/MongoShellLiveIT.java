package com.nexuslink.protocol.mongo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.*;

/** The shell tab against a real server: parsed lines actually reach the driver and come back. */
@EnabledIfSystemProperty(named = "nexuslink.it", matches = "true")
class MongoShellLiveIT {

    private MongoService service;

    @BeforeEach
    void setUp() {
        service = new MongoService();
        service.connect("mongodb://localhost:27017");
        service.useDatabase("nexuslink_shell_it");
        service.runShell("db.people.deleteMany({})");
        service.runShell("db.people.insertMany([{\"name\":\"ada\",\"age\":36},"
                + "{\"name\":\"bob\",\"age\":41},{\"name\":\"cleo\",\"age\":29}])");
    }

    @AfterEach
    void tearDown() {
        service.runShell("db.people.drop()");
        service.close();
    }

    @Test
    void findReturnsDocuments() {
        MongoQueryResult r = service.runShell("db.people.find({})");
        assertTrue(r.success(), r.error());
        assertEquals(3, r.count());
    }

    @Test
    void filterSortSkipAndLimitAllReachTheDriver() {
        MongoQueryResult r = service.runShell("db.people.find({\"age\":{\"$gt\":30}}).sort({\"age\":-1}).limit(1)");
        assertTrue(r.success(), r.error());
        assertEquals(1, r.count());
        assertTrue(r.documents().get(0).contains("bob"), r.documents().get(0));

        MongoQueryResult skipped = service.runShell("db.people.find({}).sort({\"name\":1}).skip(2)");
        assertEquals(1, skipped.count());
        assertTrue(skipped.documents().get(0).contains("cleo"));
    }

    @Test
    void countAggregateAndDistinctWork() {
        assertEquals("3", service.runShell("db.people.find({}).count()").documents().get(0));
        assertEquals("2", service.runShell("db.people.countDocuments({\"age\":{\"$gt\":30}})")
                .documents().get(0));
        MongoQueryResult agg = service.runShell(
                "db.people.aggregate([{\"$group\":{\"_id\":null,\"total\":{\"$sum\":\"$age\"}}}])");
        assertTrue(agg.documents().get(0).contains("106"), agg.documents().get(0));
        assertEquals(3, service.runShell("db.people.distinct(\"name\")").count());
    }

    @Test
    void writesReportTheirCounts() {
        assertTrue(service.runShell("db.people.updateMany({\"age\":{\"$lt\":40}}, {\"$set\":{\"junior\":true}})")
                .documents().get(0).startsWith("2"));
        assertTrue(service.runShell("db.people.deleteOne({\"name\":\"cleo\"})")
                .documents().get(0).startsWith("1"));
        assertEquals(2, service.runShell("db.people.find({})").count());
    }

    @Test
    void indexHelpersWork() {
        assertTrue(service.runShell("db.people.createIndex({\"name\":1})").success());
        MongoQueryResult indexes = service.runShell("db.people.getIndexes()");
        assertEquals(2, indexes.count(), "the _id index plus the new one");
        assertTrue(service.runShell("db.people.dropIndex(\"name_1\")").success());
    }

    @Test
    void databaseHelpersWork() {
        assertTrue(service.runShell("db.getCollectionNames()").documents().contains("people"));
        assertTrue(service.runShell("db.version()").documents().get(0).startsWith("7."));
    }

    @Test
    void aLineOutsideTheGrammarComesBackAsAnExplanationNotACrash() {
        MongoQueryResult r = service.runShell("for (i=0;i<3;i++) print(i)");
        assertFalse(r.success());
        assertTrue(r.error().contains("JavaScript"), r.error());
    }

    @Test
    void aServerSideErrorIsReportedAsItself() {
        MongoQueryResult r = service.runShell("db.people.find({\"$badOperator\":1})");
        assertFalse(r.success());
        assertNotNull(r.error());
    }
}
