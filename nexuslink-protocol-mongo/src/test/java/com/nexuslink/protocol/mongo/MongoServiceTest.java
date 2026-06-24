package com.nexuslink.protocol.mongo;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end MongoDB tests against a real mongod started by Testcontainers.
 * <p>
 * Requires Docker. If Docker is not available the container will fail to start; run these
 * with {@code -DrunMongoIT=true} (or in CI with Docker) to exercise them. They are gated on
 * that system property so the default build stays green on machines without Docker.
 */
@EnabledIfSystemProperty(named = "runMongoIT", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MongoServiceTest {

    private MongoDBContainer mongo;
    private MongoService svc;

    @BeforeAll
    void startMongo() {
        mongo = new MongoDBContainer(DockerImageName.parse("mongo:7.0"));
        mongo.start();
        svc = new MongoService();
        svc.connect(mongo.getConnectionString());
        svc.useDatabase("nexustest");
    }

    @AfterAll
    void stop() {
        if (svc != null) svc.close();
        if (mongo != null) mongo.stop();
    }

    @Test
    void insertFindAndCount() {
        svc.insertOne("users", "{ \"name\": \"Alice\", \"role\": \"admin\" }");
        svc.insertOne("users", "{ \"name\": \"Bob\", \"role\": \"developer\" }");
        svc.insertOne("users", "{ \"name\": \"Carol\", \"role\": \"developer\" }");

        MongoQueryResult all = svc.find("users", "{}", 100);
        assertTrue(all.success());
        assertEquals(3, all.count());
        assertTrue(all.documents().get(0).contains("Alice"));

        MongoQueryResult devs = svc.find("users", "{ \"role\": \"developer\" }", 100);
        assertEquals(2, devs.count());

        assertEquals(3, svc.countDocuments("users", "{}"));
    }

    @Test
    void updateAndDelete() {
        svc.insertOne("widgets", "{ \"sku\": \"W-1\", \"stock\": 5 }");
        long modified = svc.updateMany("widgets",
                "{ \"sku\": \"W-1\" }", "{ \"$set\": { \"stock\": 10 } }");
        assertEquals(1, modified);

        MongoQueryResult r = svc.find("widgets", "{ \"sku\": \"W-1\" }", 1);
        assertTrue(r.documents().get(0).contains("10"));

        assertEquals(1, svc.deleteMany("widgets", "{ \"sku\": \"W-1\" }"));
    }

    @Test
    void aggregationPipeline() {
        svc.insertOne("sales", "{ \"region\": \"NA\", \"amount\": 100 }");
        svc.insertOne("sales", "{ \"region\": \"NA\", \"amount\": 150 }");
        svc.insertOne("sales", "{ \"region\": \"EU\", \"amount\": 200 }");

        MongoQueryResult r = svc.aggregate("sales",
                "[ { \"$group\": { \"_id\": \"$region\", \"total\": { \"$sum\": \"$amount\" } } },"
                + " { \"$sort\": { \"_id\": 1 } } ]");
        assertTrue(r.success(), r.error());
        assertEquals(2, r.count());
        assertTrue(r.documents().get(0).contains("EU"));
        assertTrue(r.documents().get(0).contains("200"));
    }

    @Test
    void listsDatabasesAndCollections() {
        svc.insertOne("c1", "{ \"x\": 1 }");
        List<String> dbs = svc.listDatabaseNames();
        assertTrue(dbs.contains("nexustest"));
        assertTrue(svc.listCollectionNames().contains("c1"));
    }
}
