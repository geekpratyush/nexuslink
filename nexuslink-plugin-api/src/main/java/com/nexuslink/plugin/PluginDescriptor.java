package com.nexuslink.plugin;

/**
 * Metadata for a protocol plugin — displayed in the connection tree and tab headers.
 */
public record PluginDescriptor(
        String protocolId,
        String displayName,
        String category,        // "HTTP", "Messaging", "File", "Database", "Enterprise"
        String iconClass,       // FontAwesome class name e.g. "fa-link"
        String accentColor,     // CSS hex e.g. "#06B6D4"
        String helpTopic,       // Help anchor e.g. "rest-client"
        String version
) {}
