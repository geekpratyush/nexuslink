package com.nexuslink.protocol.http.rest;

import com.nexuslink.plugin.codegen.CodeGenTarget;
import com.nexuslink.plugin.codegen.CodeGenerator;

import java.util.List;
import java.util.Locale;

/**
 * Exposes {@link RestCodeGenerator}'s eleven languages through the cross-protocol code-generation
 * SPI, so the REST client's snippets come from the same registry as every other protocol's.
 */
public final class RestCodeGeneratorProvider implements CodeGenerator {

    private static final List<CodeGenTarget> TARGETS = List.of(
            target(RestCodeGenerator.Language.CURL, "shell"),
            target(RestCodeGenerator.Language.PYTHON, "python"),
            target(RestCodeGenerator.Language.JAVASCRIPT, "javascript"),
            target(RestCodeGenerator.Language.NODE_AXIOS, "javascript"),
            target(RestCodeGenerator.Language.JAVA, "java"),
            target(RestCodeGenerator.Language.CSHARP, "csharp"),
            target(RestCodeGenerator.Language.GO, "go"),
            target(RestCodeGenerator.Language.RUST, "rust"),
            target(RestCodeGenerator.Language.PHP, "php"),
            target(RestCodeGenerator.Language.RUBY, "ruby"),
            target(RestCodeGenerator.Language.POWERSHELL, "powershell"));

    private static CodeGenTarget target(RestCodeGenerator.Language lang, String syntax) {
        return new CodeGenTarget(lang.name().toLowerCase(Locale.ROOT), lang.label(), syntax);
    }

    @Override
    public String protocolId() {
        return "rest";
    }

    @Override
    public String displayName() {
        return "REST";
    }

    @Override
    public boolean supports(Object request) {
        return request instanceof RestRequest;
    }

    @Override
    public List<CodeGenTarget> targets() {
        return TARGETS;
    }

    @Override
    public String generate(CodeGenTarget target, Object request) {
        if (!(request instanceof RestRequest rest)) {
            throw new IllegalArgumentException("not a REST request: " + request);
        }
        return RestCodeGenerator.generate(language(target), rest);
    }

    private static RestCodeGenerator.Language language(CodeGenTarget target) {
        for (RestCodeGenerator.Language lang : RestCodeGenerator.Language.values()) {
            if (lang.name().equalsIgnoreCase(target.id())) return lang;
        }
        throw new IllegalArgumentException("unknown REST code-gen target: " + target.id());
    }
}
