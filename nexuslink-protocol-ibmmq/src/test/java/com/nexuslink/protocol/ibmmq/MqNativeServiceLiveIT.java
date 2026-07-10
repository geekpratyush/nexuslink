package com.nexuslink.protocol.ibmmq;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live MQI round-trip against the local {@code test-env} IBM MQ queue manager.
 * <pre>docker compose -f test-env/docker-compose.yml up -d ibmmq</pre>
 * Run with {@code -Dnexuslink.it=true}. QM1 on localhost(1414), channel DEV.APP.SVRCONN, app / nexus123.
 *
 * <p>The developer image ships fixed queues (DEV.QUEUE.1–3) and the {@code app} user may not create
 * more, so each test drains the queue it uses first rather than minting a fresh name.</p>
 */
@EnabledIfSystemProperty(named = "nexuslink.it", matches = "true")
class MqNativeServiceLiveIT {

    private static final String QUEUE = "DEV.QUEUE.1";
    private static final String OTHER_QUEUE = "DEV.QUEUE.2";

    private static MqConnectionProfile profile() {
        return MqConnectionProfile.plain("QM1", "DEV.APP.SVRCONN", "localhost", 1414, "app", "nexus123");
    }

    /** Leaves {@code queueName} empty so a test starts from a known depth. */
    private static void drain(MqNativeService mq, String queueName) throws Exception {
        while (mq.get(queueName, 0) != null) {
            // consume
        }
    }

    @Test
    void putThenGet() throws Exception {
        try (MqNativeService mq = new MqNativeService()) {
            mq.connect(profile());
            assertTrue(mq.isConnected());
            drain(mq, QUEUE);

            String id = mq.put(QUEUE, "hello-mq");
            assertEquals(48, id.length(), "an MQ message id is 24 bytes, hex-encoded");
            assertNotEquals("0".repeat(48), id, "MQPMO_NEW_MSG_ID must have assigned an id");

            MqNativeService.MqMessage got = mq.get(QUEUE, 5000);
            assertNotNull(got);
            assertEquals("hello-mq", got.body());
            assertEquals(id, got.messageId());
            assertEquals("MQSTR", got.format());
            assertTrue(got.persistent());
            assertNotNull(got.putTime());
            assertFalse(got.hasRfh2(), "a plain put carries no RFH2 header");
        }
    }

    @Test
    void getReturnsNullWhenTheQueueIsEmpty() throws Exception {
        try (MqNativeService mq = new MqNativeService()) {
            mq.connect(profile());
            drain(mq, OTHER_QUEUE);

            assertNull(mq.get(OTHER_QUEUE, 500), "MQRC_NO_MSG_AVAILABLE must surface as null, not an exception");
        }
    }

    @Test
    void browseDoesNotConsume() throws Exception {
        try (MqNativeService mq = new MqNativeService()) {
            mq.connect(profile());
            drain(mq, QUEUE);

            mq.put(QUEUE, "peek-one");
            mq.put(QUEUE, "peek-two");

            List<MqNativeService.MqMessage> peeked = mq.browse(QUEUE, 10);
            assertEquals(2, peeked.size());
            assertEquals("peek-one", peeked.get(0).body());
            assertEquals("peek-two", peeked.get(1).body());

            // Still there, in order, after the browse.
            assertEquals(2, mq.depth(QUEUE));
            assertEquals("peek-one", mq.get(QUEUE, 5000).body());
            assertEquals("peek-two", mq.get(QUEUE, 5000).body());
            assertEquals(0, mq.depth(QUEUE));
        }
    }

    @Test
    void browseHonoursTheMaxLimit() throws Exception {
        try (MqNativeService mq = new MqNativeService()) {
            mq.connect(profile());
            drain(mq, QUEUE);
            for (int i = 0; i < 5; i++) mq.put(QUEUE, "m" + i);

            assertEquals(2, mq.browse(QUEUE, 2).size());
            assertEquals(5, mq.depth(QUEUE), "browsing a subset must not consume the rest");

            drain(mq, QUEUE);
        }
    }

    @Test
    void depthTracksPutsAndGets() throws Exception {
        try (MqNativeService mq = new MqNativeService()) {
            mq.connect(profile());
            drain(mq, QUEUE);
            assertEquals(0, mq.depth(QUEUE));

            mq.put(QUEUE, "one");
            assertEquals(1, mq.depth(QUEUE));

            mq.get(QUEUE, 5000);
            assertEquals(0, mq.depth(QUEUE));
        }
    }

    @Test
    void rfh2PropertiesRoundTrip() throws Exception {
        try (MqNativeService mq = new MqNativeService()) {
            mq.connect(profile());
            drain(mq, QUEUE);

            mq.put(QUEUE, "typed-body", Map.of("color", "red"));

            MqNativeService.MqMessage got = mq.get(QUEUE, 5000);
            assertNotNull(got);
            assertTrue(got.hasRfh2(), "usr properties must travel as an RFH2 header");
            assertEquals("typed-body", got.body(), "the body must exclude the header bytes");
            assertEquals("MQSTR", got.format(), "format must come from the RFH2, not the MQMD");

            Rfh2Header rfh2 = got.rfh2();
            assertEquals(1208, rfh2.nameValueCcsid());
            assertEquals("red", rfh2.fields().get("usr.color"));
        }
    }

    @Test
    void rfh2SurvivesABrowseToo() throws Exception {
        try (MqNativeService mq = new MqNativeService()) {
            mq.connect(profile());
            drain(mq, QUEUE);
            mq.put(QUEUE, "browsed", Map.of("region", "eu-west-1", "tier", "gold"));

            MqNativeService.MqMessage peeked = mq.browse(QUEUE, 1).get(0);
            assertEquals("browsed", peeked.body());
            Map<String, String> fields = peeked.rfh2().fields();
            assertEquals("eu-west-1", fields.get("usr.region"));
            assertEquals("gold", fields.get("usr.tier"));

            drain(mq, QUEUE);
        }
    }

    @Test
    void deadLetterQueueIsDiscoverableAndBrowsable() throws Exception {
        try (MqNativeService mq = new MqNativeService()) {
            mq.connect(profile());

            String dlq = mq.deadLetterQueueName();
            assertNotNull(dlq, "the developer queue manager configures a DLQ");
            assertEquals("DEV.DEAD.LETTER.QUEUE", dlq);

            // Empty on a fresh queue manager, but the browse itself must work.
            assertNotNull(mq.browseDeadLetterQueue(10));
        }
    }

    @Test
    void unknownQueueRaisesMqRc2085() throws Exception {
        try (MqNativeService mq = new MqNativeService()) {
            mq.connect(profile());

            com.ibm.mq.MQException e =
                    assertThrows(com.ibm.mq.MQException.class, () -> mq.put("NO.SUCH.QUEUE", "x"));
            assertEquals(2085, e.reasonCode); // MQRC_UNKNOWN_OBJECT_NAME
        }
    }

    @Test
    void amsWithoutTheEnvironmentVariableFailsLoudlyRatherThanSilently() {
        MqConnectionProfile ams = profile().withAms("/nonexistent/keystore.conf");

        try (MqNativeService mq = new MqNativeService()) {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> mq.connect(ams));
            assertTrue(e.getMessage().contains(MqNativeService.AMS_KEYSTORE_CONF_ENV));
            assertFalse(mq.isConnected(), "a rejected AMS profile must not leave a live connection");
        }
    }

    @Test
    void closeIsIdempotentAndDisconnects() throws Exception {
        MqNativeService mq = new MqNativeService();
        mq.connect(profile());
        assertTrue(mq.isConnected());

        mq.close();
        assertFalse(mq.isConnected());
        assertNull(mq.profile());
        mq.close(); // must not throw
    }
}
