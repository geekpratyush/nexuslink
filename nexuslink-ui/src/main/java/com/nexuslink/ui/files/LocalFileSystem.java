package com.nexuslink.ui.files;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * The local disk as a {@link FileSystem}, so the same browser pane can show local files next to a
 * remote SFTP/FTP listing (the WinSCP/MobaXterm two-pane layout).
 */
public final class LocalFileSystem implements FileSystem {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    @Override public String name() { return "Local"; }

    @Override public String home() {
        return System.getProperty("user.home", "/");
    }

    @Override public String parent(String path) {
        Path p = Path.of(path).toAbsolutePath().normalize();
        Path parent = p.getParent();
        return parent == null ? p.toString() : parent.toString();
    }

    @Override public String join(String dir, String name) {
        return Path.of(dir).resolve(name).toString();
    }

    @Override public List<FileItem> list(String path) throws IOException {
        Path dir = Path.of(path).toAbsolutePath().normalize();
        List<FileItem> out = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                boolean isDir = Files.isDirectory(p);
                long size = isDir ? 0 : safeSize(p);
                out.add(FileItem.of(p.getFileName().toString(), p.toString(), isDir, size,
                        modified(p), permissions(p)));
            }
        }
        out.sort(Comparator.comparing(FileItem::directory).reversed()
                .thenComparing(f -> f.name().toLowerCase()));
        return out;
    }

    @Override public boolean exists(String dir, String name) {
        return Files.exists(Path.of(dir).resolve(name));
    }

    @Override public boolean supportsContentAccess() { return true; }

    @Override public byte[] readFile(FileItem item, long maxBytes) throws IOException {
        try (var in = Files.newInputStream(Path.of(item.path()))) {
            return in.readNBytes((int) Math.min(maxBytes, Integer.MAX_VALUE));
        }
    }

    @Override public void writeFile(String dir, String name, byte[] data) throws IOException {
        Files.write(Path.of(dir).resolve(name), data);
    }

    @Override public boolean supportsCopy() { return true; }

    @Override public void copy(FileItem src, String destDir, String destName) throws IOException {
        Path from = Path.of(src.path());
        Path to = Path.of(destDir).resolve(destName);
        if (Files.isDirectory(from)) {
            try (var walk = Files.walk(from)) {
                for (Path p : (Iterable<Path>) walk::iterator) {
                    Path target = to.resolve(from.relativize(p).toString());
                    if (Files.isDirectory(p)) Files.createDirectories(target);
                    else Files.copy(p, target, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        } else {
            Files.copy(from, to, StandardCopyOption.COPY_ATTRIBUTES);
        }
    }

    @Override public void mkdir(String path) throws IOException {
        Files.createDirectories(Path.of(path));
    }

    @Override public void rename(String from, String to) throws IOException {
        Files.move(Path.of(from), Path.of(to));
    }

    @Override public void delete(FileItem item) throws IOException {
        Path p = Path.of(item.path());
        if (Files.isDirectory(p)) {
            try (var walk = Files.walk(p)) {
                walk.sorted(Comparator.reverseOrder()).forEach(child -> {
                    try { Files.delete(child); } catch (IOException ignored) { }
                });
            }
        } else {
            Files.deleteIfExists(p);
        }
    }

    private static long safeSize(Path p) {
        try { return Files.size(p); } catch (IOException e) { return 0; }
    }

    private static String modified(Path p) {
        try { return TS.format(Files.getLastModifiedTime(p).toInstant()); }
        catch (IOException e) { return ""; }
    }

    private static String permissions(Path p) {
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(p);
            return PosixFilePermissions(perms);
        } catch (Exception e) {
            return "";
        }
    }

    private static String PosixFilePermissions(Set<PosixFilePermission> perms) {
        StringBuilder sb = new StringBuilder("---------");
        PosixFilePermission[] order = {
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE};
        char[] chars = {'r', 'w', 'x', 'r', 'w', 'x', 'r', 'w', 'x'};
        for (int i = 0; i < order.length; i++) if (perms.contains(order[i])) sb.setCharAt(i, chars[i]);
        return sb.toString();
    }
}
