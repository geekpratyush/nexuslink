package com.nexuslink.ui.help;

import java.util.List;

/**
 * Represents one help document with its parsed sections and search index.
 */
public record HelpTopic(
        String id,              // e.g. "rest-client"
        String title,           // e.g. "REST Client"
        String category,        // e.g. "HTTP Protocols"
        String resourcePath,    // classpath path to the .md file
        List<Section> sections, // parsed sections for TOC and search
        List<String> keywords   // extra keywords for fuzzy search
) {
    public record Section(
            String anchor,      // e.g. "sending-requests"
            String heading,     // e.g. "Sending Requests"
            int level,          // 1-6
            String excerpt      // first 200 chars of section text
    ) {}
}
