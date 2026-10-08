package com.stonewu.agenteam.service.workspace;

import com.stonewu.agenteam.configuration.tool.WorkspaceSettings;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 容器文件逐项校验后写入新的私有目录，拒绝链接、设备、越界路径和超量内容。
 */
public final class WorkspaceArchive {
    private WorkspaceArchive() {
    }

    public static void extract(InputStream stream, Path target, String area, WorkspaceSettings settings,
                               ToolCallControl control) throws IOException {
        if (!area.equals("work") && !area.equals("outputs")) {
            throw WorkspacePaths.invalid();
        }
        Files.createDirectories(target);
        long total = 0;
        int count = 0;
        try (var archive = new TarArchiveInputStream(
            new BoundedInput(stream, settings.workspaceBytes() + settings.maximumFiles() * 16384L, control))) {
            var entry = archive.getNextEntry();
            while (entry != null) {
                control.requireActive();
                String name = entry.getName();
                if (name.startsWith("/") || name.startsWith("\\")) {
                    throw WorkspacePaths.invalid();
                }
                if (name.startsWith("./")) {
                    name = name.substring(2);
                }
                if (name.endsWith("/")) {
                    name = name.substring(0, name.length() - 1);
                }
                String logical = WorkspacePaths.logical(name);
                if (!logical.equals(area) && !logical.startsWith(
                    area + "/") || entry.isSymbolicLink() || entry.isLink() || entry.isSparse()
                    || !(entry.getLinkFlag() == TarConstants.LF_DIR || entry.getLinkFlag() == TarConstants.LF_NORMAL || entry.getLinkFlag() == TarConstants.LF_OLDNORM)) {
                    throw WorkspacePaths.invalid();
                }
                if (++count > settings.maximumFiles() * 2 || entry.getSize() < 0 || entry.getSize() > settings.fileBytes()) {
                    throw WorkspacePaths.capacity();
                }
                Path path = WorkspacePaths.resolve(target, logical, false);
                if (entry.isDirectory()) {
                    Files.createDirectories(path);
                } else {
                    total += entry.getSize();
                    if (total > settings.workspaceBytes()) {
                        throw WorkspacePaths.capacity();
                    }
                    Files.createDirectories(path.getParent());
                    WorkspacePaths.requireInside(target, path);
                    long copied = 0;
                    try (var output = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                        byte[] buffer = new byte[8192];
                        int size;
                        while ((size = archive.read(buffer)) >= 0) {
                            control.requireActive();
                            copied += size;
                            if (copied > entry.getSize()) {
                                throw WorkspacePaths.capacity();
                            }
                            output.write(buffer, 0, size);
                        }
                    }
                    if (copied != entry.getSize()) {
                        throw new IOException("容器文件内容不完整");
                    }
                }
                entry = archive.getNextEntry();
            }
        }
    }

    private static final class BoundedInput extends FilterInputStream {
        private final long maximum;
        private final ToolCallControl control;
        private long consumed;

        private BoundedInput(InputStream input, long maximum, ToolCallControl control) {
            super(input);
            this.maximum = maximum;
            this.control = control;
        }

        private void account(int amount) {
            control.requireActive();
            if (amount > 0 && (consumed += amount) > maximum) {
                throw WorkspacePaths.capacity();
            }
        }

        @Override
        public int read() throws IOException {
            int value = in.read();
            account(value < 0 ? 0 : 1);
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int size) throws IOException {
            int read = in.read(bytes, offset, size);
            account(read);
            return read;
        }
    }
}
