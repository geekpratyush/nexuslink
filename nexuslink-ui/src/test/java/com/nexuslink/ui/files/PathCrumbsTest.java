package com.nexuslink.ui.files;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

class PathCrumbsTest {

    /** Mirrors the POSIX parent semantics used by the SFTP/FTP file systems. */
    private static final UnaryOperator<String> POSIX_PARENT = p -> {
        if (p == null || p.equals("/") || p.isBlank()) return "/";
        String q = p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
        int slash = q.lastIndexOf('/');
        return slash <= 0 ? "/" : q.substring(0, slash);
    };

    private static List<String> labels(List<PathCrumbs.Crumb> c) {
        return c.stream().map(PathCrumbs.Crumb::label).toList();
    }

    private static List<String> paths(List<PathCrumbs.Crumb> c) {
        return c.stream().map(PathCrumbs.Crumb::path).toList();
    }

    @Test
    void splitsNestedPathRootFirst() {
        var crumbs = PathCrumbs.of("/home/user/docs", POSIX_PARENT);
        assertEquals(List.of("/", "home", "user", "docs"), labels(crumbs));
        assertEquals(List.of("/", "/home", "/home/user", "/home/user/docs"), paths(crumbs));
    }

    @Test
    void rootIsASingleCrumb() {
        var crumbs = PathCrumbs.of("/", POSIX_PARENT);
        assertEquals(List.of("/"), labels(crumbs));
        assertEquals(List.of("/"), paths(crumbs));
    }

    @Test
    void singleLevel() {
        var crumbs = PathCrumbs.of("/etc", POSIX_PARENT);
        assertEquals(List.of("/", "etc"), labels(crumbs));
        assertEquals(List.of("/", "/etc"), paths(crumbs));
    }

    @Test
    void trailingSlashIsHandled() {
        var crumbs = PathCrumbs.of("/var/log/", POSIX_PARENT);
        assertEquals(List.of("/", "var", "log"), labels(crumbs));
    }

    @Test
    void blankOrNullYieldsNoCrumbs() {
        assertTrue(PathCrumbs.of("", POSIX_PARENT).isEmpty());
        assertTrue(PathCrumbs.of(null, POSIX_PARENT).isEmpty());
    }
}
