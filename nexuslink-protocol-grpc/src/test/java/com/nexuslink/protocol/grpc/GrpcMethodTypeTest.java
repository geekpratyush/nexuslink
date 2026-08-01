package com.nexuslink.protocol.grpc;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.Descriptors;
import io.grpc.MethodDescriptor.MethodType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The streaming flags on a descriptor must map to the gRPC method type used for the call. */
class GrpcMethodTypeTest {

    private static Descriptors.ServiceDescriptor service() throws Exception {
        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName("echo.proto")
                .setPackage("demo")
                .addMessageType(DescriptorProto.newBuilder().setName("Msg"))
                .addService(ServiceDescriptorProto.newBuilder()
                        .setName("Echo")
                        .addMethod(method("Unary", false, false))
                        .addMethod(method("Download", false, true))
                        .addMethod(method("Upload", true, false))
                        .addMethod(method("Chat", true, true)))
                .build();
        return Descriptors.FileDescriptor.buildFrom(file, new Descriptors.FileDescriptor[0]).getServices().get(0);
    }

    private static MethodDescriptorProto method(String name, boolean clientStreaming, boolean serverStreaming) {
        return MethodDescriptorProto.newBuilder()
                .setName(name)
                .setInputType(".demo.Msg")
                .setOutputType(".demo.Msg")
                .setClientStreaming(clientStreaming)
                .setServerStreaming(serverStreaming)
                .build();
    }

    @Test
    void mapsEachStreamingShapeToItsMethodType() throws Exception {
        Descriptors.ServiceDescriptor sd = service();
        assertEquals(MethodType.UNARY, GrpcService.methodType(sd.findMethodByName("Unary")));
        assertEquals(MethodType.SERVER_STREAMING, GrpcService.methodType(sd.findMethodByName("Download")));
        assertEquals(MethodType.CLIENT_STREAMING, GrpcService.methodType(sd.findMethodByName("Upload")));
        assertEquals(MethodType.BIDI_STREAMING, GrpcService.methodType(sd.findMethodByName("Chat")));
    }

    @Test
    void unaryIsTheOnlyNonStreamingShape() throws Exception {
        Descriptors.ServiceDescriptor sd = service();
        assertEquals(true, new GrpcService.MethodInfo("Unary", false, false, "{}").isUnary());
        assertEquals(false, new GrpcService.MethodInfo("Chat", true, true, "{}").isUnary());
        assertEquals(MethodType.UNARY, GrpcService.methodType(sd.findMethodByName("Unary")));
    }
}
