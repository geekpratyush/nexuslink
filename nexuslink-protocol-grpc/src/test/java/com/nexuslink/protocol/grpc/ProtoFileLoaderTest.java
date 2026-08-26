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

    @Test
    void parsesMessageFieldsWithLabelsAndTypes() {
        ProtoFileLoader.ProtoFile p = ProtoFileLoader.parse(PROTO);
        ProtoFileLoader.MessageType point = p.message("Point");
        assertNotNull(point);
        assertEquals(List.of("latitude", "longitude"),
                point.fields().stream().map(ProtoFileLoader.Field::name).toList());
        assertEquals("int32", point.fields().get(0).type());

        ProtoFileLoader.MessageType feature = p.message("Feature");
        assertEquals("Point", feature.fields().get(1).type());
        assertFalse(feature.fields().get(1).repeated());
    }

    @Test
    void repeatedMapsNestedTypesAndEnumsAreRecognised() {
        String proto = """
                syntax = "proto3";
                enum Level { LEVEL_LOW = 0; LEVEL_HIGH = 1; }
                message Outer {
                  repeated string tags = 1;
                  map<string, int32> counts = 2;
                  Level level = 3;
                  message Inner { bool ok = 1; }
                  Inner inner = 4;
                  oneof choice { string a = 5; int64 b = 6; }
                  reserved 7;
                }
                """;
        ProtoFileLoader.ProtoFile p = ProtoFileLoader.parse(proto);
        ProtoFileLoader.MessageType outer = p.message("Outer");
        assertEquals(List.of("tags", "counts", "level", "inner", "a", "b"),
                outer.fields().stream().map(ProtoFileLoader.Field::name).toList());
        assertTrue(outer.fields().get(0).repeated());
        assertTrue(outer.fields().get(1).map());
        assertNotNull(p.message("Inner"), "a nested message is hoisted under its simple name");
        assertEquals(List.of("LEVEL_LOW", "LEVEL_HIGH"), p.enumType("Level").values());
    }

    @Test
    void requestTemplateUsesProto3JsonDefaults() {
        String template = ProtoFileLoader.parse(PROTO).requestTemplate("Feature");
        assertEquals("""
                {
                  "name": "",
                  "location": {
                    "latitude": 0,
                    "longitude": 0
                  }
                }""", template);
    }

    @Test
    void requestTemplateHandlesRepeatedMapsEnumsAndUnknownTypes() {
        String proto = """
                syntax = "proto3";
                enum Level { LEVEL_LOW = 0; }
                message Req {
                  repeated string tags = 1;
                  map<string, int32> counts = 2;
                  Level level = 3;
                  int64 total = 4;
                  bool ok = 5;
                  google.protobuf.Timestamp at = 6;
                }
                """;
        assertEquals("""
                {
                  "tags": [],
                  "counts": {},
                  "level": "LEVEL_LOW",
                  "total": "0",
                  "ok": false,
                  "at": {}
                }""", ProtoFileLoader.parse(proto).requestTemplate("Req"));
    }

    @Test
    void requestTemplateStopsAtRecursionAndUnknownMessages() {
        String proto = """
                syntax = "proto3";
                message Node { string id = 1; Node next = 2; }
                """;
        assertEquals("""
                {
                  "id": "",
                  "next": {}
                }""", ProtoFileLoader.parse(proto).requestTemplate("Node"));
        assertEquals("{}", ProtoFileLoader.parse(proto).requestTemplate("Nope"));
    }
}
