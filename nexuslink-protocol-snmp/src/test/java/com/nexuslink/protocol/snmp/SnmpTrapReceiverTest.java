package com.nexuslink.protocol.snmp;

import org.junit.jupiter.api.Test;
import org.snmp4j.CommunityTarget;
import org.snmp4j.PDU;
import org.snmp4j.PDUv1;
import org.snmp4j.Snmp;
import org.snmp4j.event.ResponseEvent;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.GenericAddress;
import org.snmp4j.smi.IpAddress;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.TimeTicks;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.transport.DefaultUdpTransportMapping;

import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the {@link SnmpTrapReceiver} decode helpers offline (PDUs built in-process) and the full
 * receive path against a loopback ephemeral port — no external network or live agent needed.
 */
class SnmpTrapReceiverTest {

    private static final String SNMP_TRAP_OID_0 = "1.3.6.1.6.3.1.1.4.1.0";
    private static final String SYS_UPTIME_0 = "1.3.6.1.2.1.1.3.0";

    @Test
    void isTrapRecognisesTrapAndNotificationTypes() {
        assertTrue(SnmpTrapReceiver.isTrap(PDU.V1TRAP));
        assertTrue(SnmpTrapReceiver.isTrap(PDU.NOTIFICATION));
        assertTrue(SnmpTrapReceiver.isTrap(PDU.INFORM));
        assertFalse(SnmpTrapReceiver.isTrap(PDU.GET));
        assertFalse(SnmpTrapReceiver.isTrap(PDU.RESPONSE));
    }

    @Test
    void trapOidFromV2cNotificationReadsSnmpTrapOidVarbind() {
        PDU pdu = new PDU();
        pdu.setType(PDU.NOTIFICATION);
        pdu.add(new VariableBinding(new OID(SYS_UPTIME_0), new TimeTicks(12345)));
        pdu.add(new VariableBinding(new OID(SNMP_TRAP_OID_0), new OID("1.3.6.1.6.3.1.1.5.3")));
        assertEquals("1.3.6.1.6.3.1.1.5.3", SnmpTrapReceiver.trapOidOf(pdu));
    }

    @Test
    void trapOidFromV1GenericTrapMapsToSnmpTrapsBranch() {
        PDUv1 pdu = new PDUv1();
        pdu.setType(PDU.V1TRAP);
        pdu.setEnterprise(new OID("1.3.6.1.4.1.9"));
        pdu.setGenericTrap(PDUv1.COLDSTART);       // generic 0 ⇒ snmpTraps.1
        pdu.setSpecificTrap(0);
        assertEquals("1.3.6.1.6.3.1.1.5.1", SnmpTrapReceiver.trapOidOf(pdu));
    }

    @Test
    void trapOidFromV1EnterpriseSpecificTrapUsesEnterpriseAndSpecific() {
        PDUv1 pdu = new PDUv1();
        pdu.setType(PDU.V1TRAP);
        pdu.setEnterprise(new OID("1.3.6.1.4.1.9"));
        pdu.setGenericTrap(PDUv1.ENTERPRISE_SPECIFIC);  // generic 6 ⇒ <enterprise>.0.<specific>
        pdu.setSpecificTrap(42);
        assertEquals("1.3.6.1.4.1.9.0.42", SnmpTrapReceiver.trapOidOf(pdu));
    }

    @Test
    void decodeBuildsImmutableRecordWithResolvedNamesAndVarbinds() {
        PDU pdu = new PDU();
        pdu.setType(PDU.NOTIFICATION);
        pdu.add(new VariableBinding(new OID(SYS_UPTIME_0), new TimeTicks(999)));
        pdu.add(new VariableBinding(new OID(SNMP_TRAP_OID_0), new OID("1.3.6.1.6.3.1.1.5.3")));
        pdu.add(new VariableBinding(new OID("1.3.6.1.2.1.1.5.0"), new OctetString("router-1")));

        Instant ts = Instant.now();
        SnmpTrapReceiver.Trap trap = SnmpTrapReceiver.decode(pdu, "udp:127.0.0.1/40000", "public", ts);

        assertEquals(ts, trap.timestamp());
        assertEquals("v2c", trap.version());
        assertEquals("public", trap.community());
        assertEquals("udp:127.0.0.1/40000", trap.source());
        assertEquals("1.3.6.1.6.3.1.1.5.3", trap.trapOid());
        assertEquals(OidRegistry.nameFor("1.3.6.1.6.3.1.1.5.3"), trap.trapName());
        assertFalse(trap.inform(), "an unconfirmed notification is not an inform");
        assertEquals(3, trap.varBinds().size());
        // sysName.0 must be present and decodable to its symbolic name for display.
        SnmpService.VarBind sysName = trap.varBinds().get(2);
        assertEquals("1.3.6.1.2.1.1.5.0", sysName.oid());
        assertEquals("router-1", sysName.value());
        assertEquals("sysName.0", OidRegistry.nameFor(sysName.oid()));
    }

    @Test
    void decodeReturnsV1VersionForPduV1() {
        PDUv1 pdu = new PDUv1();
        pdu.setType(PDU.V1TRAP);
        pdu.setEnterprise(new OID("1.3.6.1.4.1.9"));
        pdu.setGenericTrap(PDUv1.LINKDOWN);
        pdu.setAgentAddress(new IpAddress("127.0.0.1"));
        SnmpTrapReceiver.Trap trap = SnmpTrapReceiver.decode(pdu, "udp:127.0.0.1/1", "private", Instant.now());
        assertEquals("v1", trap.version());
        assertEquals("1.3.6.1.6.3.1.1.5.3", trap.trapOid());   // linkDown ⇒ snmpTraps.3
    }

    @Test
    void decodeFlagsInformPduAsInform() {
        PDU pdu = new PDU();
        pdu.setType(PDU.INFORM);
        pdu.add(new VariableBinding(new OID(SYS_UPTIME_0), new TimeTicks(7)));
        pdu.add(new VariableBinding(new OID(SNMP_TRAP_OID_0), new OID("1.3.6.1.6.3.1.1.5.4")));
        SnmpTrapReceiver.Trap trap = SnmpTrapReceiver.decode(pdu, "udp:127.0.0.1/1", "public", Instant.now());
        assertTrue(trap.inform(), "a PDU.INFORM must be flagged as an inform");
        assertEquals("v2c", trap.version());
        assertEquals("1.3.6.1.6.3.1.1.5.4", trap.trapOid());
    }

    @Test
    void receivesV2cTrapOverLoopbackEphemeralPort() throws Exception {
        BlockingQueue<SnmpTrapReceiver.Trap> received = new ArrayBlockingQueue<>(4);
        try (SnmpTrapReceiver receiver = new SnmpTrapReceiver(received::offer)) {
            receiver.start(0);   // OS-assigned ephemeral port
            int port = receiver.boundPort();
            assertTrue(port > 0, "ephemeral port should be assigned");

            sendV2cTrap(port, "public", "1.3.6.1.6.3.1.1.5.4");   // authenticationFailure

            SnmpTrapReceiver.Trap trap = received.poll(5, TimeUnit.SECONDS);
            assertNotNull(trap, "expected a trap to be received");
            assertEquals("v2c", trap.version());
            assertEquals("public", trap.community());
            assertEquals("1.3.6.1.6.3.1.1.5.4", trap.trapOid());
            assertTrue(trap.varBinds().size() >= 2, "trap should carry its uptime + trapOID varbinds");
        }
    }

    @Test
    void receivesInformAndAcknowledgesItWithAResponse() throws Exception {
        BlockingQueue<SnmpTrapReceiver.Trap> received = new ArrayBlockingQueue<>(4);
        try (SnmpTrapReceiver receiver = new SnmpTrapReceiver(received::offer)) {
            receiver.start(0);   // OS-assigned ephemeral port
            int port = receiver.boundPort();
            assertTrue(port > 0, "ephemeral port should be assigned");

            ResponseEvent<UdpAddress> response = sendInform(port, "public", "1.3.6.1.6.3.1.1.5.4");

            // (a) the inform was delivered, flagged as an inform.
            SnmpTrapReceiver.Trap trap = received.poll(5, TimeUnit.SECONDS);
            assertNotNull(trap, "expected an inform to be received");
            assertTrue(trap.inform(), "received notification should be flagged as an inform");
            assertEquals("v2c", trap.version());
            assertEquals("1.3.6.1.6.3.1.1.5.4", trap.trapOid());

            // (b) the sender's confirmed inform got a RESPONSE back (no retry/time-out).
            assertNotNull(response, "synchronous inform send returned no event");
            PDU ack = response.getResponse();
            assertNotNull(ack, "inform should have been acknowledged with a RESPONSE PDU");
            assertEquals(PDU.RESPONSE, ack.getType(), "acknowledgement must be a RESPONSE PDU");
            assertEquals(0, ack.getErrorStatus(), "acknowledgement must carry no error");
        }
    }

    private static ResponseEvent<UdpAddress> sendInform(int port, String community, String trapOid)
            throws Exception {
        try (Snmp sender = new Snmp(new DefaultUdpTransportMapping())) {
            sender.listen();
            CommunityTarget<UdpAddress> target = new CommunityTarget<>();
            target.setCommunity(new OctetString(community));
            target.setAddress((UdpAddress) GenericAddress.parse("udp:127.0.0.1/" + port));
            target.setVersion(SnmpConstants.version2c);
            target.setTimeout(2000);
            target.setRetries(2);

            PDU pdu = new PDU();
            pdu.setType(PDU.INFORM);
            pdu.add(new VariableBinding(new OID(SYS_UPTIME_0), new TimeTicks(4242)));
            pdu.add(new VariableBinding(new OID(SNMP_TRAP_OID_0), new OID(trapOid)));
            return sender.send(pdu, target);   // synchronous: blocks until the RESPONSE or time-out
        }
    }

    private static void sendV2cTrap(int port, String community, String trapOid) throws Exception {
        try (Snmp sender = new Snmp(new DefaultUdpTransportMapping())) {
            sender.listen();
            CommunityTarget<UdpAddress> target = new CommunityTarget<>();
            target.setCommunity(new OctetString(community));
            target.setAddress((UdpAddress) GenericAddress.parse("udp:127.0.0.1/" + port));
            target.setVersion(SnmpConstants.version2c);

            PDU pdu = new PDU();
            pdu.setType(PDU.NOTIFICATION);
            pdu.add(new VariableBinding(new OID(SYS_UPTIME_0), new TimeTicks(4242)));
            pdu.add(new VariableBinding(new OID(SNMP_TRAP_OID_0), new OID(trapOid)));
            sender.send(pdu, target);
        }
    }
}
