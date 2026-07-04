package com.nexuslink.protocol.grpc;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProtoFileLoaderTest {

    private static final String PROTO = """
            syntax = "proto3";
            package routeguide;

            // A point on the map.
            message Point {
              int32 latitude = 1;
              int32 longitude = 2;
            }
            message Feature { string name = 1; Point location = 2; }
            message RouteSummary { int32 point_count = 1; }

            service RouteGuide {
              // Unary.
              rpc GetFeature(Point) returns (Feature) {}
              // Server streaming.
              rpc ListFeatures(Rectangle) returns (stream Feature);
              // Client streaming.
              rpc RecordRoute(stream Point) returns (RouteSummary);
              // Bidirectional.
              rpc RouteChat(stream RouteNote) returns (stream RouteNote);
            }
            """;

    @Test
    void parsesSyntaxAndPackage() {
        ProtoFileLoader.ProtoFile p = ProtoFileLoader.parse(PROTO);
        assertEquals("proto3", p.syntax());
        assertEquals("routeguide", p.packageName());
    }

    @Test
    void collectsMessageNames() {
        ProtoFileLoader.ProtoFile p = ProtoFileLoader.parse(PROTO);
        assertEquals(List.of("Point", "Feature", "RouteSummary"), p.messages());
    }

    @Test
    void parsesServiceAndItsMethods() {
        ProtoFileLoader.ProtoFile p = ProtoFileLoader.parse(PROTO);
        assertEquals(1, p.services().size());
        ProtoFileLoader.Service svc = p.services().get(0);
        assertEquals("RouteGuide", svc.name());
        assertEquals(List.of("GetFeature", "ListFeatures", "RecordRoute", "RouteChat"),
                svc.methods().stream().map(ProtoFileLoader.Method::name).toList());
    }

    @Test
    void capturesStreamingFlagsAndTypes() {
        ProtoFileLoader.Service svc = ProtoFileLoader.parse(PROTO).services().get(0);
        ProtoFileLoader.Method unary = svc.methods().get(0);
        assertTrue(unary.isUnary());
        assertEquals("Point", unary.inputType());
        assertEquals("Feature", unary.outputType());

        ProtoFileLoader.Method serverStream = svc.methods().get(1);
        assertFalse(serverStream.clientStreaming());
        assertTrue(serverStream.serverStreaming());

        ProtoFileLoader.Method clientStream = svc.methods().get(2);
        assertTrue(clientStream.clientStreaming());
        assertFalse(clientStream.serverStreaming());

        ProtoFileLoader.Method bidi = svc.methods().get(3);
        assertTrue(bidi.clientStreaming());
        assertTrue(bidi.serverStreaming());
    }

    @Test
    void handlesFullyQualifiedTypesAndMultipleServices() {
        String proto = """
                syntax = "proto3";
                service A { rpc Do(.pkg.In) returns (.pkg.Out); }
                service B { rpc Ping(google.protobuf.Empty) returns (google.protobuf.Empty); }
                """;
        ProtoFileLoader.ProtoFile p = ProtoFileLoader.parse(proto);
        assertEquals(List.of("A", "B"), p.services().stream().map(ProtoFileLoader.Service::name).toList());
        assertEquals("pkg.In", p.services().get(0).methods().get(0).inputType());
        assertEquals("google.protobuf.Empty", p.services().get(1).methods().get(0).outputType());
    }

    @Test
    void commentsWithKeywordsAreIgnored() {
        String proto = """
                syntax = "proto3";
                // service Ghost { rpc Nope(X) returns (Y); }
                /* message Hidden {} */
                service Real { rpc Go(In) returns (Out); }
                """;
        ProtoFileLoader.ProtoFile p = ProtoFileLoader.parse(proto);
        assertEquals(List.of("Real"), p.services().stream().map(ProtoFileLoader.Service::name).toList());
        assertTrue(p.messages().isEmpty(), "commented-out message must not be collected");
    }

    @Test
    void emptyOrNullInputYieldsEmptyProto() {
        ProtoFileLoader.ProtoFile p = ProtoFileLoader.parse(null);
        assertEquals("proto2", p.syntax());
        assertEquals("", p.packageName());
        assertTrue(p.services().isEmpty());
        assertTrue(p.messages().isEmpty());
        assertTrue(ProtoFileLoader.parse("   ").services().isEmpty());
    }
}
