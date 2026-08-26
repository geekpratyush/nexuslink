package com.nexuslink.protocol.mongo;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MongoShellCommandTest {

    @Test
    void parsesTheBasicCallShape() {
        MongoShellCommand cmd = MongoShellCommand.parse("db.people.find({\"role\":\"admin\"})");
        assertTrue(cmd.isRunnable());
        assertEquals("people", cmd.collection());
        assertEquals("find", cmd.operation());
        assertEquals("{\"role\":\"admin\"}", cmd.firstArgument());
        assertTrue(cmd.isRead());
    }

    @Test
    void aTrailingSemicolonAndSurroundingSpaceAreIgnored() {
        assertTrue(MongoShellCommand.parse("  db.people.find({});  ").isRunnable());
    }

    @Test
    void anEmptyCallMeansEverything() {
        assertEquals("{}", MongoShellCommand.parse("db.people.find()").firstArgument());
    }

    @Test
    void chainedModifiersAreCollected() {
        MongoShellCommand cmd = MongoShellCommand.parse(
                "db.people.find({}).sort({\"name\":-1}).skip(20).limit(5)");
        assertTrue(cmd.isRunnable());
        assertEquals("{\"name\":-1}", cmd.sort());
        assertEquals(20, cmd.skip());
        assertEquals(5, cmd.limit());
    }

    @Test
    void countAndPrettyAreUnderstood() {
        assertTrue(MongoShellCommand.parse("db.people.find({}).count()").count());
        assertTrue(MongoShellCommand.parse("db.people.find({}).pretty()").isRunnable());
    }

    @Test
    void nestedBracesAndDotsInsideValuesDoNotConfuseTheParser() {
        MongoShellCommand cmd = MongoShellCommand.parse(
                "db.people.find({\"address.city\":\"London\",\"tags\":{\"$in\":[\"a.b\",\"c\"]}})");
        assertTrue(cmd.isRunnable());
        assertEquals("{\"address.city\":\"London\",\"tags\":{\"$in\":[\"a.b\",\"c\"]}}", cmd.firstArgument());
    }

    @Test
    void aCommaInsideAStringDoesNotSplitTheArguments() {
        MongoShellCommand cmd = MongoShellCommand.parse("db.c.insertOne({\"name\":\"a,b\"})");
        assertEquals(1, cmd.arguments().size());
        assertEquals("{\"name\":\"a,b\"}", cmd.firstArgument());
    }

    @Test
    void twoArgumentCallsKeepBothArguments() {
        MongoShellCommand cmd = MongoShellCommand.parse(
                "db.people.updateMany({\"a\":1}, {\"$set\":{\"b\":2}})");
        assertEquals("updatemany", cmd.operation());
        assertEquals("{\"a\":1}", cmd.firstArgument());
        assertEquals("{\"$set\":{\"b\":2}}", cmd.secondArgument());
    }

    @Test
    void aFindsSecondArgumentIsItsProjection() {
        MongoShellCommand cmd = MongoShellCommand.parse("db.people.find({}, {\"name\":1})");
        assertEquals("{\"name\":1}", cmd.projection());
    }

    @Test
    void aParsedFindConvertsToTheQueryBarsSpec() {
        MongoQuerySpec spec = MongoShellCommand
                .parse("db.people.find({\"a\":1}, {\"n\":1}).sort({\"n\":1}).skip(5).limit(7)")
                .toQuerySpec(50);
        assertEquals("{\"a\":1}", spec.filter());
        assertEquals("{\"n\":1}", spec.projection());
        assertEquals("{\"n\":1}", spec.sort());
        assertEquals(5, spec.skip());
        assertEquals(7, spec.limit());
    }

    @Test
    void aFindWithoutALimitTakesTheDefault() {
        assertEquals(50, MongoShellCommand.parse("db.people.find({})").toQuerySpec(50).limit());
    }

    @Test
    void writesAndAdminOperationsAreRunnableButNotReads() {
        for (String line : List.of("db.c.insertOne({})", "db.c.deleteMany({})", "db.c.drop()",
                "db.c.createIndex({\"a\":1})", "db.c.getIndexes()")) {
            MongoShellCommand cmd = MongoShellCommand.parse(line);
            assertTrue(cmd.isRunnable(), line);
            assertFalse(cmd.isRead(), line);
        }
    }

    @Test
    void javascriptBeyondTheGrammarIsRefusedWithAReason() {
        MongoShellCommand cmd = MongoShellCommand.parse("for (i=0;i<10;i++) print(i)");
        assertFalse(cmd.isRunnable());
        assertTrue(cmd.unsupportedReason().contains("JavaScript"), cmd.unsupportedReason());
    }

    @Test
    void anUnknownOperationNamesWhatIsSupported() {
        MongoShellCommand cmd = MongoShellCommand.parse("db.people.mapReduce({})");
        assertFalse(cmd.isRunnable());
        assertTrue(cmd.unsupportedReason().contains("supported:"), cmd.unsupportedReason());
    }

    @Test
    void anUnknownModifierIsNamedRatherThanIgnored() {
        MongoShellCommand cmd = MongoShellCommand.parse("db.people.find({}).hint({\"a\":1})");
        assertFalse(cmd.isRunnable());
        assertTrue(cmd.unsupportedReason().contains("hint"), cmd.unsupportedReason());
    }

    @Test
    void theUsefulDatabaseLevelHelpersRun() {
        MongoShellCommand cmd = MongoShellCommand.parse("db.getCollectionNames()");
        assertTrue(cmd.isRunnable());
        assertTrue(cmd.isDatabaseLevel());
        assertNull(cmd.collection());
        assertEquals("getcollectionnames", cmd.operation());
        assertTrue(MongoShellCommand.parse("db.stats()").isDatabaseLevel());
    }

    @Test
    void anUnsupportedDatabaseHelperIsRefusedClearly() {
        MongoShellCommand cmd = MongoShellCommand.parse("db.shutdownServer()");
        assertFalse(cmd.isRunnable());
        assertTrue(cmd.unsupportedReason().contains("Database-level"), cmd.unsupportedReason());
    }

    @Test
    void aCollectionOperationIsNotDatabaseLevel() {
        assertFalse(MongoShellCommand.parse("db.people.find({})").isDatabaseLevel());
    }

    @Test
    void unbalancedBracketsAreReported() {
        assertTrue(MongoShellCommand.parse("db.people.find({\"a\":1}").unsupportedReason()
                .contains("Unbalanced"));
    }

    @Test
    void aNonNumericLimitIsReported() {
        assertTrue(MongoShellCommand.parse("db.people.find({}).limit(abc)").unsupportedReason()
                .contains("number"));
    }

    @Test
    void emptyInputAsksForACommand() {
        assertFalse(MongoShellCommand.parse("").isRunnable());
        assertFalse(MongoShellCommand.parse(null).isRunnable());
    }
}
