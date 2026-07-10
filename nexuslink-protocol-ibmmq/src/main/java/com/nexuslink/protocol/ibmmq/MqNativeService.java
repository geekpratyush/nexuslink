package com.nexuslink.protocol.ibmmq;

import com.ibm.mq.MQException;
import com.ibm.mq.MQGetMessageOptions;
import com.ibm.mq.MQMessage;
import com.ibm.mq.MQPutMessageOptions;
import com.ibm.mq.MQQueue;
import com.ibm.mq.MQQueueManager;
import com.ibm.mq.constants.CMQC;
import com.ibm.mq.headers.MQDataException;
import com.ibm.mq.headers.MQRFH2;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * An IBM MQ client over the <em>base Java</em> (MQI) API rather than JMS: connect to a queue manager
 * in {@code CLIENT} binding mode, put and get messages, browse a queue without consuming it, read a
 * queue's depth, and inspect the queue manager's dead-letter queue.
 *
 * <p>Unlike {@code JmsService}, this speaks MQ's native message model, so an {@link MqMessage}
 * exposes the MQMD fields (message id, correlation id, priority, persistence, put time, backout
 * count) and, when present, the parsed {@link Rfh2Header} carrying message properties.</p>
 *
 * <p>Messages are put and got with {@code CCSID 1208} (UTF-8) and {@code MQGMO_CONVERT}, so the queue
 * manager converts on our behalf and bodies are plain Java strings regardless of the producer's
 * codepage.</p>
 *
 * <p>Blocking — callers run it off the UI thread. Not thread-safe: one instance per connection, and
 * one caller at a time.</p>
 */
public final class MqNativeService implements AutoCloseable {

    /** UTF-8. Everything this client writes, and everything it asks the queue manager to convert to. */
    private static final int CCSID_UTF8 = 1208;

    /** MQ message and correlation ids are 24-byte binary values. */
    private static final int ID_LENGTH = 24;

    /** Env var IBM MQ's AMS interceptor reads to locate {@code keystore.conf}. */
    public static final String AMS_KEYSTORE_CONF_ENV = "MQS_KEYSTORE_CONF";

    /**
     * One message read off a queue: its MQMD descriptor fields, the parsed RFH2 header if it carried
     * one, and the body decoded as UTF-8.
     *
     * @param messageId     24-byte MQMD message id, hex-encoded
     * @param correlationId 24-byte MQMD correlation id, hex-encoded
     * @param format        MQMD format of the body, trimmed (e.g. {@code MQSTR})
     * @param priority      MQMD priority, 0–9
     * @param persistent    whether the message survives a queue manager restart
     * @param putTime       when the putting application handed the message to MQ
     * @param backoutCount  how many times this message has been backed out of a unit of work
     * @param body          the message data after any RFH2 header, decoded as UTF-8
     * @param rfh2          the parsed RFH2 header, or {@code null} when the message had none
     */
    public record MqMessage(String messageId,
                            String correlationId,
                            String format,
                            int priority,
                            boolean persistent,
                            Instant putTime,
                            int backoutCount,
                            String body,
                            Rfh2Header rfh2) {

        /** True when the message carried an RFH2 header, i.e. {@link #rfh2()} is non-null. */
        public boolean hasRfh2() { return rfh2 != null; }
    }

    private MQQueueManager queueManager;
    private MqConnectionProfile profile;

    /**
     * Connects to the queue manager described by {@code profile}, replacing any existing connection.
     *
     * @throws IllegalStateException if the profile enables AMS but {@value #AMS_KEYSTORE_CONF_ENV} is
     *         not set to the profile's {@code keystore.conf}. The MQ AMS interceptor reads that
     *         location from the process environment at class-init time and a JVM cannot set its own
     *         environment, so a mismatch would silently ship unprotected messages.
     */
    public void connect(MqConnectionProfile profile) throws MQException {
        close();
        if (profile.ams()) requireAmsEnvironment(profile);

        Hashtable<String, Object> properties = new Hashtable<>();
        properties.put(CMQC.HOST_NAME_PROPERTY, profile.host());
        properties.put(CMQC.PORT_PROPERTY, profile.port());
        properties.put(CMQC.CHANNEL_PROPERTY, profile.channel());
        properties.put(CMQC.TRANSPORT_PROPERTY, CMQC.TRANSPORT_MQSERIES_CLIENT);

        if (profile.hasCredentials()) {
            properties.put(CMQC.USER_ID_PROPERTY, profile.user());
            properties.put(CMQC.PASSWORD_PROPERTY, profile.password() == null ? "" : profile.password());
            // MQCSP flows the password out-of-band instead of squeezing it into the 12-char MQMD field.
            properties.put(CMQC.USE_MQCSP_AUTHENTICATION_PROPERTY, true);
        }
        if (profile.tls()) {
            properties.put(CMQC.SSL_CIPHER_SUITE_PROPERTY, profile.cipherSuite());
            if (profile.trustStore() != null && !profile.trustStore().isBlank()) {
                // Hand MQ a socket factory built from this profile's trust store rather than mutating
                // the JVM-global javax.net.ssl.* system properties.
                properties.put(CMQC.SSL_SOCKET_FACTORY_PROPERTY, sslSocketFactory(profile));
            }
        }

        this.queueManager = new MQQueueManager(profile.queueManager(), properties);
        this.profile = profile;
    }

    private static void requireAmsEnvironment(MqConnectionProfile profile) {
        String configured = System.getenv(AMS_KEYSTORE_CONF_ENV);
        if (configured == null || !Path.of(configured).equals(Path.of(profile.amsKeystoreConf()))) {
            throw new IllegalStateException(
                    "AMS is enabled on " + profile.redacted() + " but the " + AMS_KEYSTORE_CONF_ENV
                            + " environment variable is " + (configured == null ? "unset" : "'" + configured + "'")
                            + ", not '" + profile.amsKeystoreConf() + "'. MQ's AMS interceptor reads that"
                            + " variable from the process environment; launch the JVM with it set.");
        }
    }

    private static javax.net.ssl.SSLSocketFactory sslSocketFactory(MqConnectionProfile profile) {
        Path path = Path.of(profile.trustStore());
        String password = profile.trustStorePassword() == null ? "" : profile.trustStorePassword();
        String type = path.toString().toLowerCase(Locale.ROOT).endsWith(".p12")
                || path.toString().toLowerCase(Locale.ROOT).endsWith(".pfx") ? "PKCS12" : "JKS";
        try (InputStream in = Files.newInputStream(path)) {
            KeyStore trust = KeyStore.getInstance(type);
            trust.load(in, password.toCharArray());
            TrustManagerFactory trustManagers =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagers.init(trust);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustManagers.getTrustManagers(), null);
            return context.getSocketFactory();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot build a TLS socket factory from " + path, e);
        }
    }

    public boolean isConnected() {
        return queueManager != null && queueManager.isConnected();
    }

    /** The profile this service is connected with, or {@code null} when disconnected. */
    public MqConnectionProfile profile() { return profile; }

    /** Puts a plain-text message on {@code queueName}; returns its hex-encoded message id. */
    public String put(String queueName, String body) throws MQException, IOException {
        return put(queueName, body, Map.of());
    }

    /**
     * Puts a message on {@code queueName}, returning its hex-encoded message id. When
     * {@code usrProperties} is non-empty the entries are written as an RFH2 {@code usr} folder, which
     * is how MQ carries application message properties on the wire — a JMS consumer reads them back as
     * string properties.
     */
    public String put(String queueName, String body, Map<String, String> usrProperties)
            throws MQException, IOException {
        MQMessage message = new MQMessage();
        message.characterSet = CCSID_UTF8;
        message.encoding = CMQC.MQENC_NATIVE;
        message.persistence = CMQC.MQPER_PERSISTENT;

        if (usrProperties == null || usrProperties.isEmpty()) {
            message.format = CMQC.MQFMT_STRING;
        } else {
            MQRFH2 rfh2 = new MQRFH2();
            rfh2.setEncoding(CMQC.MQENC_NATIVE);
            rfh2.setCodedCharSetId(CCSID_UTF8);
            rfh2.setFormat(CMQC.MQFMT_STRING);   // describes what follows the header, i.e. the body
            rfh2.setNameValueCCSID(CCSID_UTF8);
            for (Map.Entry<String, String> property : usrProperties.entrySet()) {
                rfh2.setFieldValue("usr", property.getKey(), property.getValue());
            }
            message.format = CMQC.MQFMT_RF_HEADER_2;
            rfh2.write(message);
        }
        message.writeString(body == null ? "" : body);

        MQPutMessageOptions options = new MQPutMessageOptions();
        options.options = CMQC.MQPMO_NO_SYNCPOINT | CMQC.MQPMO_NEW_MSG_ID | CMQC.MQPMO_FAIL_IF_QUIESCING;

        MQQueue queue = queueManager.accessQueue(queueName,
                CMQC.MQOO_OUTPUT | CMQC.MQOO_FAIL_IF_QUIESCING);
        try {
            queue.put(message, options);
        } finally {
            queue.close();
        }
        return hex(message.messageId);
    }

    /**
     * Gets — and removes — one message from {@code queueName}, waiting up to {@code timeoutMs} for one
     * to arrive. Returns {@code null} if the queue stayed empty.
     */
    public MqMessage get(String queueName, long timeoutMs) throws MQException, IOException {
        MQGetMessageOptions options = new MQGetMessageOptions();
        options.options = CMQC.MQGMO_NO_SYNCPOINT | CMQC.MQGMO_CONVERT | CMQC.MQGMO_FAIL_IF_QUIESCING
                | (timeoutMs > 0 ? CMQC.MQGMO_WAIT : CMQC.MQGMO_NO_WAIT);
        options.waitInterval = (int) Math.min(timeoutMs, Integer.MAX_VALUE);

        MQQueue queue = queueManager.accessQueue(queueName,
                CMQC.MQOO_INPUT_SHARED | CMQC.MQOO_FAIL_IF_QUIESCING);
        try {
            MQMessage message = newReadMessage();
            queue.get(message, options);
            return toMqMessage(message);
        } catch (MQException e) {
            if (e.reasonCode == CMQC.MQRC_NO_MSG_AVAILABLE) return null;
            throw e;
        } finally {
            queue.close();
        }
    }

    /** Peeks up to {@code max} messages on {@code queueName} without removing any of them. */
    public List<MqMessage> browse(String queueName, int max) throws MQException, IOException {
        List<MqMessage> messages = new ArrayList<>();
        MQQueue queue = queueManager.accessQueue(queueName,
                CMQC.MQOO_BROWSE | CMQC.MQOO_FAIL_IF_QUIESCING);
        try {
            MQGetMessageOptions options = new MQGetMessageOptions();
            int cursor = CMQC.MQGMO_BROWSE_FIRST;
            while (messages.size() < max) {
                options.options = CMQC.MQGMO_NO_SYNCPOINT | CMQC.MQGMO_CONVERT
                        | CMQC.MQGMO_FAIL_IF_QUIESCING | CMQC.MQGMO_NO_WAIT | cursor;
                MQMessage message = newReadMessage();
                try {
                    queue.get(message, options);
                } catch (MQException e) {
                    if (e.reasonCode == CMQC.MQRC_NO_MSG_AVAILABLE) break; // walked off the end
                    throw e;
                }
                messages.add(toMqMessage(message));
                cursor = CMQC.MQGMO_BROWSE_NEXT;
            }
        } finally {
            queue.close();
        }
        return messages;
    }

    /** The number of messages currently sitting on {@code queueName}. */
    public int depth(String queueName) throws MQException {
        MQQueue queue = queueManager.accessQueue(queueName,
                CMQC.MQOO_INQUIRE | CMQC.MQOO_FAIL_IF_QUIESCING);
        try {
            return queue.getCurrentDepth();
        } finally {
            queue.close();
        }
    }

    /** The queue manager's configured dead-letter queue, or {@code null} when it has none. */
    public String deadLetterQueueName() throws MQException {
        String name = queueManager.getAttributeString(CMQC.MQCA_DEAD_LETTER_Q_NAME,
                CMQC.MQ_Q_MGR_NAME_LENGTH).trim();
        return name.isEmpty() ? null : name;
    }

    /**
     * Browses up to {@code max} messages on the queue manager's dead-letter queue.
     *
     * @throws IllegalStateException if the queue manager has no dead-letter queue configured
     */
    public List<MqMessage> browseDeadLetterQueue(int max) throws MQException, IOException {
        String dlq = deadLetterQueueName();
        if (dlq == null) {
            throw new IllegalStateException(
                    "Queue manager " + profile.queueManager() + " has no dead-letter queue configured");
        }
        return browse(dlq, max);
    }

    /**
     * A message primed for a get: any id left over from a previous get would otherwise act as a
     * selector, and the CCSID/encoding tell {@code MQGMO_CONVERT} what to convert <em>to</em>.
     */
    private static MQMessage newReadMessage() {
        MQMessage message = new MQMessage();
        message.messageId = CMQC.MQMI_NONE;
        message.correlationId = CMQC.MQCI_NONE;
        message.characterSet = CCSID_UTF8;
        message.encoding = CMQC.MQENC_NATIVE;
        return message;
    }

    /** Splits a just-read message into its RFH2 header (if any) and its body. */
    private static MqMessage toMqMessage(MQMessage message) throws IOException {
        Rfh2Header rfh2 = null;
        String bodyFormat = message.format == null ? "" : message.format.trim();

        if (CMQC.MQFMT_RF_HEADER_2.trim().equals(bodyFormat)) {
            try {
                MQRFH2 header = new MQRFH2(message); // advances the read cursor past the header
                rfh2 = new Rfh2Header(
                        header.getFormat() == null ? "" : header.getFormat().trim(),
                        header.getEncoding(),
                        header.getCodedCharSetId(),
                        header.getNameValueCCSID(),
                        List.of(header.getFolderStrings()));
                bodyFormat = rfh2.format();
            } catch (MQDataException e) {
                throw new IOException("Message claims format " + CMQC.MQFMT_RF_HEADER_2
                        + " but its RFH2 header will not parse", e);
            }
        }

        byte[] body = new byte[message.getDataLength()];
        message.readFully(body);

        return new MqMessage(
                hex(message.messageId),
                hex(message.correlationId),
                bodyFormat,
                message.priority,
                message.persistence == CMQC.MQPER_PERSISTENT,
                message.putDateTime == null ? null : message.putDateTime.toInstant(),
                message.backoutCount,
                new String(body, StandardCharsets.UTF_8),
                rfh2);
    }

    /** Hex-encodes a 24-byte MQ id; MQ's own tooling shows these as hex, so users can match them up. */
    private static String hex(byte[] id) {
        if (id == null) return "";
        StringBuilder out = new StringBuilder(ID_LENGTH * 2);
        for (byte b : id) out.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return out.toString();
    }

    @Override
    public void close() {
        if (queueManager != null) {
            try {
                queueManager.disconnect();
            } catch (MQException ignored) {
                // Already gone, or the channel died — nothing useful to do on the way out.
            }
            queueManager = null;
        }
        profile = null;
    }
}
