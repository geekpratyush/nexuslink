package com.nexuslink.protocol.grpc;

import com.google.protobuf.ByteString;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.util.JsonFormat;
import com.nexuslink.security.tls.TlsConfig;
import com.nexuslink.security.tls.TlsContextFactory;
import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.reflection.v1alpha.ServerReflectionGrpc;
import io.grpc.reflection.v1alpha.ServerReflectionRequest;
import io.grpc.reflection.v1alpha.ServerReflectionResponse;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.StreamObserver;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Dynamic gRPC client driven entirely by <b>server reflection</b> — no {@code .proto} upload needed.
 * Lists services/methods, builds request messages from JSON, invokes unary methods, and prints the
 * response as JSON. Streaming methods are detected but not yet invocable.
 */
public final class GrpcService implements AutoCloseable {

    /** A method on a service, with streaming flags and a default-value JSON request template. */
    public record MethodInfo(String name, boolean clientStreaming, boolean serverStreaming, String requestTemplate) {
        public boolean isUnary() { return !clientStreaming && !serverStreaming; }
    }

    private ManagedChannel channel;
    private final Map<String, FileDescriptorProto> protoByName = new ConcurrentHashMap<>();
    private final Map<String, Descriptors.FileDescriptor> fdByName = new ConcurrentHashMap<>();

    public void connect(String host, int port, boolean tls) {
        connect(host, port, tls, null);
    }

    /**
     * Connects with optional custom TLS material. When {@code tls} is false the channel is plaintext.
     * When {@code tls} is true and {@code tlsConfig} is {@link TlsConfig#isCustom() custom}, a netty
     * client SSL context is built from the configured trust store (the CAs to verify the server) and/or
     * client key store (a client certificate for mutual TLS), or trust-all; otherwise the system default
     * TLS is used.
     */
    public void connect(String host, int port, boolean tls, TlsConfig tlsConfig) {
        close();
        if (!tls) {
            channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
            return;
        }
        if (tlsConfig != null && tlsConfig.isCustom()) {
            try {
                channel = NettyChannelBuilder.forAddress(host, port).sslContext(buildSslContext(tlsConfig)).build();
            } catch (Exception e) {
                throw new RuntimeException("TLS setup failed: " + e.getMessage(), e);
            }
        } else {
            channel = ManagedChannelBuilder.forAddress(host, port).useTransportSecurity().build();
        }
    }

    /** Builds a netty client {@link SslContext} from a {@link TlsConfig}'s key/trust stores. */
    private static SslContext buildSslContext(TlsConfig cfg) throws Exception {
        SslContextBuilder builder = GrpcSslContexts.forClient();
        if (cfg.trustAll()) {
            builder.trustManager(InsecureTrustManagerFactory.INSTANCE);
        } else if (cfg.hasTrustStore()) {
            KeyStore ts = loadStore(cfg.trustStorePath(),
                    TlsContextFactory.typeFor(cfg.trustStoreType(), cfg.trustStorePath()), cfg.trustStorePassword());
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ts);
            builder.trustManager(tmf);
        }
        if (cfg.hasKeyStore()) {
            KeyStore ks = loadStore(cfg.keyStorePath(),
                    TlsContextFactory.typeFor(cfg.keyStoreType(), cfg.keyStorePath()), cfg.keyStorePassword());
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, cfg.keyStorePassword());
            builder.keyManager(kmf);
        }
        return builder.build();
    }

    private static KeyStore loadStore(String path, String type, char[] password) throws Exception {
        KeyStore ks = KeyStore.getInstance(type);
        try (InputStream in = Files.newInputStream(Path.of(path))) {
            ks.load(in, password);
        }
        return ks;
    }

    public boolean isConnected() { return channel != null; }

    /** Lists the server's services (excluding the reflection service itself). */
    public List<String> listServices() throws Exception {
        ServerReflectionResponse resp = reflect(ServerReflectionRequest.newBuilder().setListServices("*").build());
        List<String> out = new ArrayList<>();
        resp.getListServicesResponse().getServiceList().forEach(s -> {
            if (!s.getName().startsWith("grpc.reflection") && !s.getName().startsWith("grpc.channelz")) out.add(s.getName());
        });
        Collections.sort(out);
        return out;
    }

    public List<MethodInfo> listMethods(String service) throws Exception {
        Descriptors.ServiceDescriptor sd = resolveService(service);
        List<MethodInfo> out = new ArrayList<>();
        for (Descriptors.MethodDescriptor m : sd.getMethods()) {
            out.add(new MethodInfo(m.getName(), m.isClientStreaming(), m.isServerStreaming(),
                    defaultJson(m.getInputType())));
        }
        return out;
    }

    /** Invokes a unary method with a JSON request and returns the JSON response. */
    public String invokeUnary(String service, String method, String jsonRequest) throws Exception {
        Descriptors.ServiceDescriptor sd = resolveService(service);
        Descriptors.MethodDescriptor m = sd.findMethodByName(method);
        if (m == null) throw new IllegalArgumentException("No method '" + method + "' on " + service);
        if (m.isClientStreaming() || m.isServerStreaming()) {
            throw new UnsupportedOperationException("Only unary methods are supported in this build");
        }
        Descriptors.Descriptor inType = m.getInputType();
        Descriptors.Descriptor outType = m.getOutputType();

        DynamicMessage.Builder reqBuilder = DynamicMessage.newBuilder(inType);
        JsonFormat.parser().ignoringUnknownFields()
                .merge(jsonRequest == null || jsonRequest.isBlank() ? "{}" : jsonRequest, reqBuilder);
        DynamicMessage request = reqBuilder.build();

        io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> grpcMethod =
                io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                        .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
                        .setFullMethodName(io.grpc.MethodDescriptor.generateFullMethodName(service, method))
                        .setRequestMarshaller(ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(inType)))
                        .setResponseMarshaller(ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(outType)))
                        .build();

        DynamicMessage response = ClientCalls.blockingUnaryCall(channel, grpcMethod, CallOptions.DEFAULT, request);
        return JsonFormat.printer().print(response);
    }

    // ---- reflection / descriptor resolution ----

    private Descriptors.ServiceDescriptor resolveService(String service) throws Exception {
        ServerReflectionResponse resp = reflect(ServerReflectionRequest.newBuilder()
                .setFileContainingSymbol(service).build());
        cacheProtos(resp);
        for (ByteString bs : resp.getFileDescriptorResponse().getFileDescriptorProtoList()) {
            FileDescriptorProto p = FileDescriptorProto.parseFrom(bs);
            Descriptors.FileDescriptor fd = buildFile(p.getName());
            for (Descriptors.ServiceDescriptor sd : fd.getServices()) {
                if (sd.getFullName().equals(service)) return sd;
            }
        }
        throw new IllegalStateException("Service descriptor not found: " + service);
    }

    private Descriptors.FileDescriptor buildFile(String name) throws Exception {
        Descriptors.FileDescriptor cached = fdByName.get(name);
        if (cached != null) return cached;
        FileDescriptorProto proto = protoByName.get(name);
        if (proto == null) proto = fetchFile(name);
        List<Descriptors.FileDescriptor> deps = new ArrayList<>();
        for (String dep : proto.getDependencyList()) deps.add(buildFile(dep));
        Descriptors.FileDescriptor fd = Descriptors.FileDescriptor.buildFrom(
                proto, deps.toArray(new Descriptors.FileDescriptor[0]));
        fdByName.put(name, fd);
        return fd;
    }

    private FileDescriptorProto fetchFile(String filename) throws Exception {
        ServerReflectionResponse resp = reflect(ServerReflectionRequest.newBuilder()
                .setFileByFilename(filename).build());
        cacheProtos(resp);
        FileDescriptorProto p = protoByName.get(filename);
        if (p == null) throw new IllegalStateException("Could not fetch descriptor for " + filename);
        return p;
    }

    private void cacheProtos(ServerReflectionResponse resp) throws Exception {
        for (ByteString bs : resp.getFileDescriptorResponse().getFileDescriptorProtoList()) {
            FileDescriptorProto p = FileDescriptorProto.parseFrom(bs);
            protoByName.putIfAbsent(p.getName(), p);
        }
    }

    private ServerReflectionResponse reflect(ServerReflectionRequest req) throws Exception {
        ServerReflectionGrpc.ServerReflectionStub stub = ServerReflectionGrpc.newStub(channel);
        CompletableFuture<ServerReflectionResponse> future = new CompletableFuture<>();
        StreamObserver<ServerReflectionRequest> requestObserver =
                stub.serverReflectionInfo(new StreamObserver<>() {
                    @Override public void onNext(ServerReflectionResponse value) {
                        if (!future.isDone()) future.complete(value);
                    }
                    @Override public void onError(Throwable t) { future.completeExceptionally(t); }
                    @Override public void onCompleted() {
                        if (!future.isDone()) future.completeExceptionally(new IllegalStateException("no reflection response"));
                    }
                });
        requestObserver.onNext(req);
        requestObserver.onCompleted();
        return future.get(15, TimeUnit.SECONDS);
    }

    private static String defaultJson(Descriptors.Descriptor d) {
        try {
            return JsonFormat.printer().includingDefaultValueFields()
                    .print(DynamicMessage.getDefaultInstance(d));
        } catch (Exception e) {
            return "{}";
        }
    }

    @Override
    public void close() {
        if (channel != null) { channel.shutdownNow(); channel = null; }
        protoByName.clear();
        fdByName.clear();
    }
}
